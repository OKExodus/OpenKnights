package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.session.ServiceLog
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A backup cannot be made or used (the message is safe to show). */
class BackupError(message: String) : IllegalArgumentException(message)

/**
 * Backups of a data root (`save_management.py`): a `.okbackup` is a zip with `manifest.json` (format, kind, versions,
 * schemas, times, every file's size and SHA-256) and consistent page copies of every database. This part covers the
 * full backup, reading and checking a backup, and the automatic safety copies taken at service start (before an app
 * update, and the rolling daily copy of the device-local day, the last three kept). Restore, character backup,
 * delete and reset come with the save-management screens.
 */
object SaveManagement {
    const val FORMAT = "openknights_backup"
    const val FORMAT_VERSION = 1
    const val SUFFIX = ".okbackup"
    const val DAILY_KEEP = 3

    private fun generationFiles(generation: Path, driver: SqlDriver): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        val files = Files.walk(generation).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sqlite3") }.toList() }
        val tmp = Files.createTempDirectory(generation.parent, ".backup-")
        try {
            for ((index, path) in files.sortedBy { generation.relativize(it).joinToString("/") }.withIndex()) {
                val relative = generation.relativize(path).joinToString("/")
                if (relative.startsWith(".") || relative.contains("/.")) continue
                val copy = tmp.resolve("copy-$index.sqlite3")
                driver.backup(path, copy)
                out["world/$relative"] = Files.readAllBytes(copy)
            }
        } finally {
            Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        return out
    }

    private fun manifest(kind: String, files: Map<String, ByteArray>, schemas: JObj, clock: DeviceClock, extra: Map<String, Any?>): JObj {
        val epoch = clock.now()
        // isoformat(timespec="seconds") of the device-local time: "+00:00", never "Z"
        val local = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").format(clock.localDateTime(epoch))
        val document = jobj("format" to FORMAT, "format_version" to FORMAT_VERSION, "kind" to kind, "app_version" to DataRoot.APP_VERSION,
            "layout_version" to DataRoot.LAYOUT_VERSION, "schemas" to schemas, "created_utc" to PyTime.nowIsoMillis(),
            "created_local" to local,
            "files" to files.keys.sorted().map { jobj("path" to it, "bytes" to files.getValue(it).size, "sha256" to sha256Hex(files.getValue(it))) })
        extra.forEach { (k, v) -> document[k] = io.github.okexodus.openknights.exact.jvalue(v) }
        return document
    }

    private fun publishZip(entries: Map<String, ByteArray>, manifest: JObj, target: Path): Path {
        Files.createDirectories(target.toAbsolutePath().parent)
        val temporary = Publish.temporaryBeside(target, "init", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write((Json.dumps(manifest, sortKeys = true, indent = 1) + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (path in entries.keys.sorted()) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(entries.getValue(path))
                    zip.closeEntry()
                }
            }
            Publish.publishNew(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    /** The whole active world (registry, world, bots, every character and checkpoint) as one `.okbackup`. */
    fun backupFull(root: DataRoot, target: Path, driver: SqlDriver, clock: DeviceClock): Pair<Path, JObj> {
        val generation = root.active ?: throw BackupError("There is nothing to back up yet: no character has been created")
        val files = generationFiles(generation, driver)
        val world = generation.resolve(DataRoot.WORLD)
        val seed = if (Files.exists(world)) WorldDirectory(world, driver).worldSeed() else null
        val manifest = manifest("full", files, root.schemaVersions(generation.fileName.toString()), clock, mapOf("world_seed" to seed))
        return publishZip(files, manifest, target) to manifest
    }

    /** Read a backup: the manifest and every file, sizes and hashes checked; newer formats refused. */
    fun readBackup(path: Path): Pair<JObj, Map<String, ByteArray>> {
        val zip = try { ZipFile(path.toFile()) } catch (e: Exception) { throw BackupError("This file is not an OpenKnights backup (not a zip file)") }
        zip.use {
            val entry = zip.getEntry("manifest.json") ?: throw BackupError("This file is not an OpenKnights backup (no manifest)")
            val manifest = Json.loads(zip.getInputStream(entry).readBytes()).asObj
            if (manifest.strOrNull("format") != FORMAT) throw BackupError("This file is not an OpenKnights backup")
            if (manifest.long("format_version") > FORMAT_VERSION || manifest.long("layout_version") > DataRoot.LAYOUT_VERSION) {
                throw BackupError("This backup was made by a newer OpenKnights; update OpenKnights first")
            }
            val files = LinkedHashMap<String, ByteArray>()
            for (item in manifest.arr("files")) {
                val file = item.asObj
                val name = file.str("path")
                if (name.startsWith("/") || name.contains("..") || name.contains(":") || name.contains("\\")) throw BackupError("The backup names an unsafe path")
                val data = zip.getInputStream(zip.getEntry(name) ?: throw BackupError("The backup is incomplete ($name is missing)")).readBytes()
                if (data.size.toLong() != file.long("bytes") || sha256Hex(data) != file.str("sha256")) throw BackupError("The backup is damaged ($name)")
                files[name] = data
            }
            val listed = files.keys + "manifest.json"
            if (zip.entries().asSequence().any { it.name !in listed }) throw BackupError("The backup holds files its manifest does not list")
            return manifest to files
        }
    }

    /**
     * At service start: a copy before an app update (the manifest's app version differs) and the rolling daily copy
     * at the first start of a device-local day (the last [DAILY_KEEP] kept; older ones go to trash/).
     */
    fun startupCopies(root: DataRoot, driver: SqlDriver, clock: DeviceClock, log: ServiceLog? = null): List<String> {
        val made = ArrayList<String>()
        val manifest = root.readManifest() ?: JObj()
        if (root.active == null) {
            if (manifest.strOrNull("app_version") != DataRoot.APP_VERSION) root.writeManifest(null, "app version recorded")
            return made
        }
        val active = (manifest["active"] as JStr).value
        if (manifest.strOrNull("app_version") != DataRoot.APP_VERSION) {
            val target = root.autoBackups.resolve("pre-update-${manifest.strOrNull("app_version") ?: "unknown"}-${PyTime.nowStamp()}$SUFFIX")
            made.add(backupFull(root, target, driver, clock).first.fileName.toString())
            root.writeManifest(active, "app updated to ${DataRoot.APP_VERSION}")
        }
        val today = clock.localDay(clock.now())
        val current = root.readManifest()!!
        if (current.strOrNull("daily_backup_day") != today) {
            val target = root.autoBackups.resolve("daily-$today$SUFFIX")
            if (!Files.exists(target)) {
                backupFull(root, target, driver, clock)
                made.add(target.fileName.toString())
            }
            root.writeManifest(active, "daily safety copy", mapOf("daily_backup_day" to JStr(today)))
            val dailies = Files.list(root.autoBackups).use { s -> s.filter { it.fileName.toString().startsWith("daily-") && it.fileName.toString().endsWith(SUFFIX) }.sorted().toList() }
            for (old in dailies.dropLast(DAILY_KEEP)) made.add("trash:" + root.moveToTrash(old, "old-daily").fileName)
        }
        if (log != null && made.isNotEmpty()) log.log("safety_copies", "made" to made)
        return made
    }
}

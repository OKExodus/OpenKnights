package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** The data root cannot be used as it is (the message is safe to show). */
class DataRootError(message: String) : IllegalArgumentException(message)

/**
 * One folder holds every save of one install (`data_root.py`, release contract §7):
 *
 *     <root>/manifest.json            format, layout_version, app_version, the ACTIVE generation, schema versions
 *     <root>/worlds/<generation>/     registry.sqlite3, world.sqlite3, bots.sqlite3, characters/<id>/save.sqlite3 …
 *     <root>/trash/  auto-backups/  logs/  clock.json  service.lock
 *
 * `manifest.json` is the only commit record, replaced atomically; birth, full restore and reset switch `active` in
 * one replace. Generations that are not active are orphans and move to `trash/` at the next start (never deleted),
 * except one reusable unborn generation (a registry with sign-in sessions, no world, no character).
 */
class DataRoot(path: Path, private val driver: SqlDriver) {
    companion object {
        const val FORMAT = "openknights_data_root"
        const val LAYOUT_VERSION = 1
        const val APP_VERSION = "0.1.0.0"
        const val REGISTRY = "registry.sqlite3"
        const val WORLD = "world.sqlite3"
        const val BOTS = "bots.sqlite3"
        const val BOTS_SCHEMA_VERSION = 1
        val GENERATION = Regex("w-\\d{8}T\\d{6}Z-[0-9a-f]{8}")

        fun newGenerationName(): String = "w-${PyTime.nowStamp()}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
    }

    val root: Path = path.toAbsolutePath().normalize()
    val manifestPath: Path = root.resolve("manifest.json")
    val worlds: Path = root.resolve("worlds")
    val trash: Path = root.resolve("trash")
    val autoBackups: Path = root.resolve("auto-backups")
    val logs: Path = root.resolve("logs")
    val clockPath: Path = root.resolve("clock.json")
    val lock = ServiceLock(root.resolve("service.lock"))
    var recovered: List<String> = emptyList()
        private set

    // --- manifest ------------------------------------------------------------------------------------------------

    fun readManifest(): JObj? {
        if (!Files.isRegularFile(manifestPath)) return null
        val manifest = try { Json.loads(Files.readAllBytes(manifestPath)).asObj } catch (e: Exception) {
            throw DataRootError("manifest.json is unreadable")
        }
        if (manifest.strOrNull("format") != FORMAT) throw DataRootError("This folder is not an OpenKnights data root")
        val layout = manifest["layout_version"]
        if (layout !is io.github.okexodus.openknights.exact.JInt || layout.value.toLong() > LAYOUT_VERSION) {
            throw DataRootError("This data root was written by a newer OpenKnights (layout $layout; this version reads up to $LAYOUT_VERSION)")
        }
        val active = manifest["active"]
        if (active != null && active != JNull && (active !is JStr || !GENERATION.matches(active.value))) {
            throw DataRootError("manifest.json names an invalid world")
        }
        return manifest
    }

    fun writeManifest(active: String?, reason: String, extra: Map<String, JValue> = emptyMap()): JObj {
        val previous = readManifest() ?: JObj()
        val document = jobj("format" to FORMAT, "layout_version" to LAYOUT_VERSION, "app_version" to APP_VERSION,
            "active" to active, "updated_at_utc" to PyTime.nowIsoMillis(), "reason" to reason,
            "schemas" to (if (active != null) schemaVersions(active) else JObj()))
        for (key in listOf("created_at_utc", "daily_backup_day")) previous[key]?.let { document[key] = it }
        extra.forEach { (k, v) -> document[k] = v }
        if (!document.containsKey("created_at_utc")) document["created_at_utc"] = JStr(PyTime.nowIsoMillis())
        Publish.writeJsonAtomic(manifestPath, document)
        return document
    }

    fun schemaVersions(generation: String): JObj {
        val folder = worlds.resolve(generation)
        return jobj("registry" to databaseVersion(folder.resolve(REGISTRY)), "world" to databaseVersion(folder.resolve(WORLD)),
            "bots" to databaseVersion(folder.resolve(BOTS)), "save" to 1)
    }

    fun databaseVersion(path: Path): Int? {
        if (!Files.isRegularFile(path)) return null
        return driver.open(path, SqlDriver.Mode.READ_ONLY).use { it.userVersion() }
    }

    val activeName: String? get() = (readManifest()?.get("active") as? JStr)?.value

    val active: Path? get() = activeName?.let { worlds.resolve(it) }

    val born: Boolean get() = active != null

    // --- opening and recovery ----------------------------------------------------------------------------------------

    fun open(create: Boolean = true): DataRoot {
        if (!Files.exists(root)) {
            if (!create) throw DataRootError("The data root does not exist")
            Files.createDirectories(root)
        }
        for (folder in listOf(worlds, trash, autoBackups, logs)) Files.createDirectories(folder)
        val manifest = readManifest()
        if (manifest == null) {
            writeManifest(null, "data root created (no world before the first character)")
        } else {
            val active = (manifest["active"] as? JStr)?.value
            if (active != null && !Files.isDirectory(worlds.resolve(active))) throw DataRootError("The active world named by manifest.json is missing")
        }
        recovered = recover()
        return this
    }

    fun generationPaths(generation: String): Map<String, Path> {
        val folder = worlds.resolve(generation)
        return mapOf("folder" to folder, "registry" to folder.resolve(REGISTRY), "world" to folder.resolve(WORLD),
            "bots" to folder.resolve(BOTS), "characters" to folder.resolve("characters"), "checkpoints" to folder.resolve("checkpoints"))
    }

    private fun isUnbornStaging(folder: Path): Boolean {
        if (!Files.isDirectory(folder) || !Files.isRegularFile(folder.resolve(REGISTRY)) || Files.exists(folder.resolve(WORLD))) return false
        val characters = folder.resolve("characters")
        return !Files.exists(characters) || Files.list(characters).use { !it.findAny().isPresent }
    }

    fun moveToTrash(path: Path, tag: String): Path {
        var target = trash.resolve("${PyTime.nowStamp()}-$tag-${path.fileName}")
        while (Files.exists(target)) target = target.resolveSibling(target.fileName.toString() + "-" + UUID.randomUUID().toString().take(4))
        Files.move(path, target)
        return target
    }

    private fun sortedDirs(folder: Path): List<Path> =
        Files.list(folder).use { s -> s.filter { Files.isDirectory(it) }.sorted(compareBy { it.fileName.toString() }).toList() }

    fun recover(): List<String> {
        val active = (readManifest()?.get("active") as? JStr)?.value
        val moved = ArrayList<String>()
        var keptStaging: Path? = null
        for (folder in sortedDirs(worlds)) {
            if (folder.fileName.toString() == active) continue
            if (active == null && keptStaging == null && isUnbornStaging(folder)) { keptStaging = folder; continue }
            moved.add(root.relativize(moveToTrash(folder, "orphan")).joinToString("/"))
        }
        Files.list(root).use { s -> s.filter { it.fileName.toString().startsWith(".staging-") }.sorted().toList() }.forEach {
            moved.add(root.relativize(moveToTrash(it, "interrupted")).joinToString("/"))
        }
        if (active != null) {
            val generation = worlds.resolve(active)
            val subs = mutableListOf(generation, generation.resolve("characters"))
            if (Files.isDirectory(generation.resolve("characters"))) subs.addAll(sortedDirs(generation.resolve("characters")))
            for (sub in subs) {
                if (Files.isDirectory(sub)) {
                    moved += Publish.clearStaleClaims(sub, trash.resolve("${PyTime.nowStamp()}-claims")).map { "claim:$it" }
                }
            }
        }
        return moved
    }

    fun unbornStaging(): Path {
        if (born) throw DataRootError("The world is already born")
        for (folder in sortedDirs(worlds)) if (isUnbornStaging(folder)) return folder
        return Files.createDirectory(worlds.resolve(newGenerationName()))
    }

    // --- commits -------------------------------------------------------------------------------------------------

    fun commitActive(generation: String, reason: String, extra: Map<String, JValue> = emptyMap()): Path? {
        if (!GENERATION.matches(generation) || !Files.isDirectory(worlds.resolve(generation))) throw DataRootError("Unknown world generation")
        val previous = active
        writeManifest(generation, reason, extra)
        return previous
    }

    fun clearActive(reason: String): Path? {
        val previous = active
        writeManifest(null, reason)
        return previous
    }
}

/**
 * One service per data root: an OS lock on byte 0 of `service.lock` (released by the OS if the process dies).
 * On Windows this is the same byte-range lock the reference takes, so the two exclude each other; on Linux the
 * reference uses flock and Java uses record locks, which do not see each other (one server per root is the rule).
 */
class ServiceLock(val path: Path) {
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null

    fun acquire(): ServiceLock {
        val ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val lock = try { ch.tryLock(0, 1, false) } catch (e: OverlappingFileLockException) { null } catch (e: java.io.IOException) { null }
        if (lock == null) {
            ch.close()
            throw DataRootError("The data root is in use by a running service; stop it first")
        }
        ch.truncate(0)
        ch.write(java.nio.ByteBuffer.wrap(Json.dumps(jobj("pid" to ProcessHandle.current().pid(), "since_utc" to PyTime.nowIsoMillis())).toByteArray()), 0)
        ch.force(true)
        channel = ch
        fileLock = lock
        return this
    }

    fun release() {
        try { fileLock?.release() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        fileLock = null
        channel = null
    }
}

package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.ApkSource
import io.github.okexodus.openknights.gamedata.AssetCipher
import io.github.okexodus.openknights.gamedata.SummonPoolPolicy
import io.github.okexodus.openknights.gamedata.SupportedInput
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.patcher.dex.ServerRuntime
import io.github.okexodus.openknights.patcher.input.IdentifiedInput
import io.github.okexodus.openknights.patcher.patch.Branding
import io.github.okexodus.openknights.patcher.patch.CodePatch
import io.github.okexodus.openknights.patcher.patch.ManifestPatch
import io.github.okexodus.openknights.patcher.patch.NativePatchSet
import io.github.okexodus.openknights.patcher.patch.ResourcePatch
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.res.ResourceTable
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.ZipEntry
import io.github.okexodus.openknights.patcher.zip.ZipWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How the patched app reaches its server. */
enum class ServerMode {
    /** The server runs on a PC; the phone or emulator reaches it through `adb reverse` (ports 17777, 17778, 19121). */
    DEV_SERVER,

    /** The server runs inside the app process (server-android); the app plays fully offline, no PC. */
    ON_DEVICE,
}

/**
 * The on-device server payload the patcher adds to the APK in [ServerMode.ON_DEVICE]: the server `classes.dex`, the
 * release-data files and the sign-in page (added under `assets/openknights/`). Supplied by the build, not bundled in
 * the public repository (release-data stays private until P6).
 */
class ServerBundle(
    /** The server DEX files (the server plus its d8 core-library-desugaring output), added as extra classesN.dex. */
    val dexes: List<ByteArray>,
    /** file name -> bytes, added under `assets/openknights/release-data/`. */
    val releaseData: Map<String, ByteArray>,
    val signinPage: ByteArray,
    /**
     * Classpath resources the server reads at run time (d8 strips non-class files from the DEX), keyed by their
     * classpath path, e.g. `io/github/okexodus/openknights/gamedata/supported-input.json`. Added to the APK at that
     * path so the app classloader's `getResourceAsStream` finds them.
     */
    val resources: Map<String, ByteArray> = emptyMap(),
) {
    companion object {
        const val APPLICATION_CLASS = "io.github.okexodus.openknights.OpenKnightsApplication"
        const val RESTORE_ACTIVITY = "io.github.okexodus.openknights.server.android.RestoreActivity"
        const val ASSET_DIR = "assets/openknights"
    }
}

class PatchOptions(
    val version: AppVersion = BuildInfo.version,
    val mode: ServerMode = ServerMode.DEV_SERVER,
    val packageName: String = PACKAGE,
    val label: String = LABEL,
    /** Use the OpenKnights icon (otherwise the game keeps its own). */
    val icon: Boolean = true,
) {
    companion object {
        const val PACKAGE = "io.github.okexodus.openknights"
        const val LABEL = "OpenKnights"
    }
}

/**
 * Builds the unsigned OpenKnights APK from a checked input: every step reads the original, checks its patch sites and
 * writes new data; unchanged entries are copied without recompressing. The original files are only read.
 */
class ApkPatcher(
    private val options: PatchOptions = PatchOptions(),
    private val nativePatches: NativePatchSet = NativePatchSet.bundled,
    private val codePatch: CodePatch = CodePatch(),
    private val branding: Branding = Branding(),
) {
    /** Writes the unsigned APK to [output] and adds each step to [report]. */
    fun build(input: IdentifiedInput, output: Path, report: Report, serverBundle: ServerBundle? = null, log: (String) -> Unit = {}) {
        val base = input.base
        val onDevice = options.mode == ServerMode.ON_DEVICE
        require(!onDevice || serverBundle != null) { "ON_DEVICE mode needs a server bundle" }

        val supremeTables: Map<String, ByteArray> = if (onDevice && "supreme-summon-pool.json" in serverBundle!!.releaseData) {
            val source = ArchiveTables(base)
            val poolRaw = serverBundle.releaseData.getValue("supreme-summon-pool.json")
            val manifestRaw = serverBundle.releaseData["MANIFEST.json"]
                ?: throw IllegalArgumentException("Supreme pool needs a release-data manifest")
            val releaseManifest = Json.parseToJsonElement(manifestRaw.toString(Charsets.UTF_8)).jsonObject
            val expected = releaseManifest["files"]?.jsonObject?.get("supreme-summon-pool.json")
                ?.jsonObject?.get("sha256")?.jsonPrimitive?.content
            require(expected == Hashing.sha256(poolRaw)) { "Supreme pool is not bound to its release-data manifest" }
            val policy = SummonPoolPolicy.parse(poolRaw)
            val transformed = policy.transform(source)
            listOf("xinniudan.csv", "niudanhero.csv").associateWith { name ->
                AssetCipher(SupportedInput.bundled.tableKey).encrypt(transformed.raw(name))
            }
        } else emptyMap()

        log("Patching the app manifest")
        val tableBytes = base.archive.read("resources.arsc")
        val table = ResourceTable.read(tableBytes)
        val resources = ResourcePatch(table)
        val merges = input.densitySplits.map { split ->
            log("Merging the resources of ${split.manifest.split}")
            val splitTable = ResourceTable.read(split.archive.read("resources.arsc"))
            resources.merge(split.manifest.split ?: split.name, splitTable) to split
        }
        val icon = if (options.icon) branding.addIcon(resources) else null
        val manifest = ManifestPatch(options.packageName, options.label, options.version, icon?.iconId,
            applicationClass = if (onDevice) ServerBundle.APPLICATION_CLASS else null,
            restoreActivity = if (onDevice) ServerBundle.RESTORE_ACTIVITY else null)
            .apply(base.archive.read("AndroidManifest.xml"))
        val newTable = resources.encode()

        log("Patching the game's code")
        val dexNames = base.archive.entries.map { it.name }.filter { Regex("classes\\d*\\.dex").matches(it) }
        val code = codePatch.apply(dexNames.associateWith { base.archive.read(it) })

        log("Patching the program library")
        val (libApk, libEntry) = input.nativeLibrary
        val (library, sites) = nativePatches.apply(libApk.archive.read(libEntry))

        // Entries from the density splits: every file their merged resources point at.
        val splitFiles = LinkedHashMap<String, Pair<ApkSource, ZipEntry>>()
        for ((merge, split) in merges) {
            for (path in merge.files) {
                val entry = split.archive[path] ?: throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH,
                    "The ${split.manifest.split} part lists $path but does not contain it. Nothing was patched.")
                val inBase = base.archive[path]
                if (inBase != null) {
                    if (inBase.crc != entry.crc || inBase.size != entry.size) throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH,
                        "$path differs between the main APK and ${split.manifest.split}. Nothing was patched.")
                    continue
                }
                splitFiles.putIfAbsent(path, split to entry)
            }
        }

        log("Writing the patched app")
        val replaced = mapOf("AndroidManifest.xml" to manifest.manifest, "resources.arsc" to newTable) + code.dexFiles +
            supremeTables.mapKeys { (name, _) -> SupportedInput.bundled.tablePrefix + name }
        var copied = 0
        Files.newOutputStream(output).use { stream ->
            ZipWriter(stream).use { zip ->
                for (entry in base.archive.entries) {
                    // A universal APK carries the library itself; the patched copy is written below.
                    if (entry.isDirectory || isDropped(entry.name) || entry.name == nativePatches.file) continue
                    val data = replaced[entry.name]
                    if (data != null) zip.addStored(entry.name, data) else { zip.copy(base.archive, entry); copied++ }
                }
                code.dexFiles[code.addedDex]?.let { zip.addStored(code.addedDex, it) }
                zip.addStored(nativePatches.file, library, alignment = LIBRARY_ALIGNMENT)
                for ((name, _) in supremeTables) {
                    val original = base.archive[SupportedInput.bundled.tablePrefix + name]
                        ?: throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "Missing original $name table")
                    zip.addStored("${ServerBundle.ASSET_DIR}/original-tables/$name", base.archive.read(original))
                }
                for ((path, source) in splitFiles) zip.copy(source.first.archive, source.second)
                icon?.files?.forEach { (path, data) -> zip.addStored(path, data) }
                if (onDevice) {
                    var next = CodePatch.dexIndex(code.addedDex) + 1
                    val isolatedDexes = serverBundle!!.dexes.map(ServerRuntime::isolate)
                    val serverDexNames = isolatedDexes.map { data ->
                        val name = "classes$next.dex"; next++
                        zip.addStored(name, data); name
                    }
                    zip.addStored("${ServerBundle.ASSET_DIR}/signin.html", serverBundle.signinPage)
                    for ((name, data) in serverBundle.releaseData) zip.addStored("${ServerBundle.ASSET_DIR}/release-data/$name", data)
                    for ((path, data) in serverBundle.resources) zip.addStored(path, data)
                    report.step("on_device_server", mapOf(
                        "server_dexes" to serverDexNames, "server_dex_sizes" to isolatedDexes.map { it.size },
                        "release_data_files" to serverBundle.releaseData.keys.sorted(),
                        "classpath_resources" to serverBundle.resources.keys.sorted(),
                        "application_class" to ServerBundle.APPLICATION_CLASS))
                }
            }
        }

        report.step("manifest", mapOf(
            "package" to options.packageName, "label" to options.label,
            "version_name" to options.version.toString(), "version_code" to options.version.versionCode,
            "icon_resource" to icon?.iconId?.let { "0x%08x".format(it) },
            "removed" to manifest.removedComponents, "removed_attributes" to manifest.removedAttributes,
            "sha256" to Hashing.sha256(manifest.manifest),
        ))
        report.step("resources", mapOf(
            "merged" to merges.map { (m, _) -> mapOf("split" to m.split, "entries" to m.entries, "new_ids" to m.newIds.size,
                "new_configurations" to m.newConfigs, "files" to m.files.size) },
            "files_copied_from_splits" to splitFiles.size,
            "icon_files" to icon?.files?.keys?.toList(),
            "sha256" to Hashing.sha256(newTable),
        ))
        report.step("code", mapOf(
            "edited_classes" to code.edits.map { mapOf("class" to it.className, "dex" to it.dex, "replaced_methods" to it.replacedMethods,
                "inert_methods" to it.inertMethods, "replaced_strings" to it.replacedStrings,
                "original_smali_sha256" to it.originalSmaliSha256, "patched_smali_sha256" to it.editedSmaliSha256) },
            "added_dex" to code.addedDex, "added_classes" to code.addedClasses,
            "dex_sha256" to code.dexFiles.mapValues { Hashing.sha256(it.value) },
        ))
        report.step("native_library", mapOf(
            "file" to nativePatches.file, "source_sha256" to nativePatches.sourceSha256, "result_sha256" to Hashing.sha256(library),
            "sites" to sites.map { mapOf("id" to it.id, "offset" to it.offset, "before" to it.before, "after" to it.after) },
        ))
        report.step("assemble", mapOf(
            "entries_copied_unchanged" to copied, "dropped" to base.archive.entries.map { it.name }.filter(::isDropped),
            "unsigned_sha256" to Hashing.sha256(output), "unsigned_size" to Files.size(output),
        ))
    }

    companion object {
        /** Native libraries are stored uncompressed at a 16 KB boundary, which also suits 4 KB page devices. */
        const val LIBRARY_ALIGNMENT = 16384

        /** The original signature and its source stamp: they belong to the publisher's key, not ours. */
        fun isDropped(name: String): Boolean =
            name == "stamp-cert-sha256" || name == "META-INF/MANIFEST.MF" ||
                (name.startsWith("META-INF/") && name.count { it == '/' } == 1 &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")))
    }

    private class ArchiveTables(private val apk: ApkSource) : TableSource {
        override fun names(): List<String> = listOf("xinniudan.csv", "niudanhero.csv")
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            val entry = apk.archive[SupportedInput.bundled.tablePrefix + file]
                ?: throw NoSuchElementException("Missing table: $file")
            val plain = AssetCipher(SupportedInput.bundled.tableKey).decrypt(apk.archive.read(entry))
            val expected = SupportedInput.bundled.tables[file]
            require(expected != null && Hashing.sha256(plain) == expected) { "Original $file table does not match the supported APK" }
            return plain
        }
    }
}

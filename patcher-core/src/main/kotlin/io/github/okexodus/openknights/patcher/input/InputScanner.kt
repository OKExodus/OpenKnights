package io.github.okexodus.openknights.patcher.input

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.DataSource
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import io.github.okexodus.openknights.patcher.zip.ZipEntry
import io.github.okexodus.openknights.patcher.zip.ZipFormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

enum class InputKind { SPLIT_SET, UNIVERSAL }

/** A file the player supplied, as found on disk. */
data class InputFile(val name: String, val size: Long, val sha256: String)

/** The supported game, found and checked. Close it to release the files (and delete temporary copies). */
class IdentifiedInput internal constructor(
    val displayName: String,
    val kind: InputKind,
    val base: ApkSource,
    val abiSplit: ApkSource?,
    val densitySplits: List<ApkSource>,
    val unusedSplits: List<ApkSource>,
    /** The APK holding the native library, and the library's entry. */
    val nativeLibrary: Pair<ApkSource, ZipEntry>,
    val files: List<InputFile>,
    /** Entry name (or `tables`) to the SHA-256 that matched the supported definition. */
    val checks: Map<String, String>,
    notes: List<String>,
    /** A hash over everything that shapes the output, used to tell identical copies apart from different ones. */
    val fingerprint: String,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    private val noteList = notes.toMutableList()

    /** One line per other file or folder found, saying why it was not used. */
    val notes: List<String> get() = noteList

    internal fun addNotes(lines: List<String>) { noteList += lines }

    val apks: List<ApkSource> get() = listOfNotNull(base, abiSplit) + densitySplits

    override fun close() {
        resources.asReversed().forEach { runCatching { it.close() } }
    }
}

/**
 * Finds the game in a file or folder and checks it by content, not by container: a split set, a folder of splits,
 * one universal APK, or an XAPK / APKM / APKS bundle all work when their contents match the supported build.
 */
class InputScanner(
    private val supported: SupportedInput = SupportedInput.bundled,
    /** Where compressed APKs inside a bundle are unpacked while checking (the system temporary folder by default). */
    private val tempRoot: Path? = null,
) {
    private val label get() = supported.label

    fun identify(path: Path): IdentifiedInput {
        if (!Files.exists(path)) throw PatchFailure(FailureCode.INPUT_NOT_FOUND, "\"$path\" does not exist.")
        ignoredNotes = emptyList()
        val candidates = if (path.isDirectory()) folderCandidates(path) else listOf(fileCandidate(path))
        return choose(path, candidates)
    }

    // ---- Finding candidates -------------------------------------------------------------------------------------

    private sealed class Candidate(val label: String) {
        class Loose(label: String, val apks: List<Path>) : Candidate(label)
        class Bundle(label: String, val file: Path) : Candidate(label)
        class Rejected(label: String, val failure: PatchFailure) : Candidate(label)
    }

    private fun folderCandidates(folder: Path): List<Candidate> {
        val items = Files.list(folder).use { stream -> stream.filter { !isNoise(it) }.sorted().toList() }
        if (items.isEmpty()) {
            throw PatchFailure(FailureCode.EMPTY_FOLDER,
                "The folder \"${folder.name}\" is empty. Put your copy of $label in it: one APK, an XAPK or APKM file, " +
                    "or a folder with the split APKs.")
        }
        val candidates = ArrayList<Candidate>()
        val loose = ArrayList<Path>()
        val ignored = ArrayList<String>()
        for (item in items) {
            if (item.isDirectory()) {
                val apks = Files.list(item).use { s -> s.filter { it.isRegularFile() && looksLikeApk(it) }.sorted().toList() }
                if (apks.isEmpty()) ignored += "${item.name}/ (a folder without APK files)" else candidates += Candidate.Loose("${item.name}/", apks)
                continue
            }
            when (classify(item)) {
                FileClass.APK -> loose.add(item)
                FileClass.BUNDLE -> candidates += Candidate.Bundle(item.name, item)
                FileClass.ZIP_WITHOUT_APP -> candidates += Candidate.Rejected(item.name, PatchFailure(FailureCode.WRONG_FILE_TYPE,
                    "\"${item.name}\" is a zip file but holds no Android app."))
                FileClass.DAMAGED_ZIP -> candidates += Candidate.Rejected(item.name, PatchFailure(FailureCode.DAMAGED_FILE,
                    "\"${item.name}\" is damaged (it is not a complete zip file). Download it again."))
                FileClass.OTHER -> ignored += "${item.name} (not an Android app file)"
            }
        }
        if (loose.isNotEmpty()) candidates.addAll(0, groupLoose(loose))
        if (candidates.isEmpty()) {
            throw PatchFailure(FailureCode.NO_GAME_FILE,
                "No Android app file was found in \"${folder.name}\". Put your copy of $label there (APK, XAPK, APKM " +
                    "or a folder of split APKs).", ignored.map { "Not used: $it" })
        }
        ignoredNotes = ignored.map { "Not used: $it" }
        return candidates
    }

    private var ignoredNotes: List<String> = emptyList()

    private fun fileCandidate(file: Path): Candidate = when (classify(file)) {
        FileClass.APK -> Candidate.Loose(file.name, listOf(file))
        FileClass.BUNDLE -> Candidate.Bundle(file.name, file)
        FileClass.ZIP_WITHOUT_APP -> Candidate.Rejected(file.name, PatchFailure(FailureCode.WRONG_FILE_TYPE,
            "\"${file.name}\" is a zip file but holds no Android app. Supply your copy of $label."))
        FileClass.DAMAGED_ZIP -> Candidate.Rejected(file.name, PatchFailure(FailureCode.DAMAGED_FILE,
            "\"${file.name}\" is damaged (it is not a complete zip file). Download it again."))
        FileClass.OTHER -> Candidate.Rejected(file.name, PatchFailure(FailureCode.WRONG_FILE_TYPE,
            "\"${file.name}\" is not an Android app file. Supply your copy of $label (APK, XAPK or APKM)."))
    }

    /** Loose APK files in one folder: one candidate per base, with the splits of the same package and version. */
    private fun groupLoose(files: List<Path>): List<Candidate> {
        val info = LinkedHashMap<Path, ManifestInfo?>()
        for (file in files) info[file] = runCatching { withArchive(file) { ManifestInfo.read(it.read("AndroidManifest.xml")) } }.getOrNull()
        val bases = files.filter { info[it]?.isBase == true }
        if (bases.size <= 1) return listOf(Candidate.Loose(if (files.size == 1) files[0].name else "the ${files.size} APK files", files))
        return bases.map { base ->
            val b = info[base]!!
            val parts = listOf(base) + files.filter { f -> info[f]?.let { !it.isBase && it.packageName == b.packageName && it.versionCode == b.versionCode } == true }
            Candidate.Loose(base.name, parts)
        }
    }

    private enum class FileClass { APK, BUNDLE, ZIP_WITHOUT_APP, DAMAGED_ZIP, OTHER }

    private fun classify(file: Path): FileClass {
        val source = try { FileSource.open(file) } catch (_: IOException) { return FileClass.OTHER }
        source.use {
            if (!ZipArchive.looksLikeZip(it)) return FileClass.OTHER
            val zip = try { ZipArchive.open(it) } catch (_: ZipFormatException) { return FileClass.DAMAGED_ZIP }
            return when {
                zip.contains("AndroidManifest.xml") -> FileClass.APK
                zip.entries.any { e -> isApkEntry(e) } -> FileClass.BUNDLE
                else -> FileClass.ZIP_WITHOUT_APP
            }
        }
    }

    private fun looksLikeApk(file: Path): Boolean = classify(file) == FileClass.APK

    private fun isNoise(path: Path): Boolean {
        val name = path.name
        return name.startsWith(".") || name.equals("desktop.ini", true) || name.equals("Thumbs.db", true) || name == "__MACOSX"
    }

    private fun isApkEntry(e: ZipEntry): Boolean =
        !e.isDirectory && e.name.endsWith(".apk", ignoreCase = true) && !e.name.startsWith("__MACOSX/")

    private inline fun <T> withArchive(file: Path, block: (ZipArchive) -> T): T =
        FileSource.open(file).use { source -> block(ZipArchive.open(source)) }

    // ---- Choosing ------------------------------------------------------------------------------------------------

    private fun choose(path: Path, candidates: List<Candidate>): IdentifiedInput {
        val results = ArrayList<Pair<Candidate, Result<IdentifiedInput>>>()
        try {
            for (candidate in candidates) results += candidate to runCatching { evaluate(candidate) }
            val good = results.filter { it.second.isSuccess }.map { it.first to it.second.getOrThrow() }
            val bad = results.filter { it.second.isFailure }.map { it.first to failureOf(it.second.exceptionOrNull()!!) }
            val badNotes = bad.map { (c, f) -> "Not used: ${c.label}: ${f.message}" }
            when {
                good.size == 1 -> {
                    val chosen = good[0].second
                    return chosen.withNotes(badNotes + ignoredNotes)
                }
                good.size > 1 -> {
                    if (good.map { it.second.fingerprint }.distinct().size == 1) {
                        val chosen = good[0].second
                        good.drop(1).forEach { it.second.close() }
                        val duplicates = good.drop(1).map { "Not used: ${it.first.label}: the same copy as ${good[0].first.label}" }
                        return chosen.withNotes(duplicates + badNotes + ignoredNotes)
                    }
                    good.forEach { it.second.close() }
                    throw PatchFailure(FailureCode.SEVERAL_SUPPORTED,
                        "\"${path.name}\" holds more than one copy of $label, and they differ: " +
                            good.joinToString(", ") { it.first.label } + ". Keep only one of them.")
                }
                bad.size == 1 -> throw bad[0].second
                else -> throw PatchFailure(FailureCode.NO_SUPPORTED_FILE,
                    "None of the files in \"${path.name}\" is a supported copy of $label.", badNotes + ignoredNotes)
            }
        } catch (e: Throwable) {
            results.forEach { it.second.getOrNull()?.close() }
            throw e
        }
    }

    private fun failureOf(error: Throwable): PatchFailure = error as? PatchFailure
        ?: PatchFailure(FailureCode.DAMAGED_FILE, "The file could not be read: ${error.message}. Download it again.", cause = error)

    private fun IdentifiedInput.withNotes(extra: List<String>): IdentifiedInput = also { it.addNotes(extra) }

    // ---- Evaluating one candidate --------------------------------------------------------------------------------

    private fun evaluate(candidate: Candidate): IdentifiedInput {
        val resources = ArrayList<AutoCloseable>()
        try {
            val (apks, files) = when (candidate) {
                is Candidate.Rejected -> throw candidate.failure
                is Candidate.Loose -> openLoose(candidate, resources)
                is Candidate.Bundle -> openBundle(candidate, resources)
            }
            return check(candidate.label, apks, files, resources)
        } catch (e: Throwable) {
            resources.asReversed().forEach { runCatching { it.close() } }
            throw e
        }
    }

    private fun openLoose(candidate: Candidate.Loose, resources: MutableList<AutoCloseable>): Pair<List<ApkSource>, List<InputFile>> {
        val apks = candidate.apks.map { file -> openApk(file.name, FileSource.open(file).also { resources += it }) }
        val files = candidate.apks.map { InputFile(it.name, Files.size(it), Hashing.sha256(it)) }
        return apks to files
    }

    private fun openBundle(candidate: Candidate.Bundle, resources: MutableList<AutoCloseable>): Pair<List<ApkSource>, List<InputFile>> {
        val name = candidate.file.name
        val source = FileSource.open(candidate.file).also { resources += it }
        val outer = try { ZipArchive.open(source) } catch (e: ZipFormatException) {
            throw PatchFailure(FailureCode.DAMAGED_FILE, "\"$name\" is damaged (${e.message}). Download it again.", cause = e)
        }
        val entries = outer.entries.filter { isApkEntry(it) }
        if (entries.any { it.isEncrypted }) {
            throw PatchFailure(FailureCode.ENCRYPTED_FILE, "\"$name\" is encrypted and cannot be read. Export the app as a plain APK or XAPK first.")
        }
        var temp: Path? = null
        val apks = entries.map { entry ->
            val inner: DataSource = if (entry.method == ZipEntry.STORED) {
                outer.rawSource(entry)
            } else {
                val dir = temp ?: (if (tempRoot != null) Files.createTempDirectory(tempRoot, "openknights-input-") else Files.createTempDirectory("openknights-input-"))
                    .also { temp = it; resources += AutoCloseable { deleteTree(it) } }
                val target = dir.resolve("part-${entries.indexOf(entry)}.apk")
                try {
                    outer.openStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                } catch (e: ZipFormatException) {
                    throw PatchFailure(FailureCode.DAMAGED_FILE, "\"$name\" is damaged (${e.message}). Download it again.", cause = e)
                }
                FileSource.open(target).also { resources += it }
            }
            openApk("$name!${entry.name}", inner)
        }
        return apks to listOf(InputFile(name, Files.size(candidate.file), Hashing.sha256(candidate.file)))
    }

    private fun openApk(name: String, source: DataSource): ApkSource {
        val zip = try { ZipArchive.open(source) } catch (e: ZipFormatException) {
            throw PatchFailure(FailureCode.DAMAGED_FILE, "\"$name\" is damaged (${e.message}). Download it again.", cause = e)
        }
        val manifest = try { ManifestInfo.read(zip.read("AndroidManifest.xml")) } catch (e: Exception) {
            throw PatchFailure(FailureCode.DAMAGED_FILE, "\"$name\" is damaged: its app manifest cannot be read. Download it again.", cause = e)
        }
        return ApkSource(name, zip, manifest)
    }

    private fun check(label: String, apks: List<ApkSource>, files: List<InputFile>, resources: MutableList<AutoCloseable>): IdentifiedInput {
        val bases = apks.filter { it.manifest.isBase }
        if (bases.isEmpty()) {
            throw PatchFailure(FailureCode.INCOMPLETE_SPLITS,
                "\"$label\" holds only split APKs (${apks.joinToString { it.manifest.split ?: it.name }}); the main APK of the game is missing.")
        }
        if (bases.size > 1) {
            throw PatchFailure(FailureCode.MISMATCHED_SPLITS, "\"$label\" holds more than one main APK: ${bases.joinToString { it.name }}. Keep one game per file or folder.")
        }
        val base = bases.single()
        val info = base.manifest
        if (info.packageName != supported.packageName) {
            throw PatchFailure(FailureCode.WRONG_GAME,
                "\"$label\" is ${info.packageName} ${info.versionName ?: ""}".trimEnd() + ", not Pocket Knights. This patcher needs $label.")
        }
        if (info.versionCode != supported.versionCode) {
            throw PatchFailure(FailureCode.UNSUPPORTED_VERSION,
                "\"$label\" is Pocket Knights ${info.versionName ?: "(unknown version)"} (version code ${info.versionCode}), but this " +
                    "patcher supports ${supported.label} (version code ${supported.versionCode}) only. Nothing was written.")
        }
        val splits = apks.filter { !it.manifest.isBase }
        val foreign = splits.filter { it.manifest.packageName != info.packageName || it.manifest.versionCode != info.versionCode }
        if (foreign.isNotEmpty()) {
            throw PatchFailure(FailureCode.MISMATCHED_SPLITS, "\"$label\" mixes parts of different apps or versions: " +
                foreign.joinToString { "${it.name} is ${it.manifest.packageName} version code ${it.manifest.versionCode}" } + ".")
        }
        val abiSplits = splits.filter { "base__abi" in it.manifest.splitTypes }
        val densitySplits = splits.filter { "base__density" in it.manifest.splitTypes }
        val unused = splits - abiSplits.toSet() - densitySplits.toSet()
        val libraryHolders = (listOf(base) + abiSplits).mapNotNull { apk -> apk.archive[supported.nativeLibraryPath]?.let { apk to it } }
        val kind = if (info.requiredSplitTypes.isEmpty() && splits.isEmpty()) InputKind.UNIVERSAL else InputKind.SPLIT_SET
        if ("base__abi" in info.requiredSplitTypes && abiSplits.isEmpty()) {
            throw PatchFailure(FailureCode.INCOMPLETE_SPLITS,
                "\"$label\" is missing the arm64 part of the game (config.arm64_v8a), which holds its program code. Supply the complete set, " +
                    "for example the whole XAPK or APKM file.")
        }
        if ("base__density" in info.requiredSplitTypes && densitySplits.isEmpty()) {
            throw PatchFailure(FailureCode.INCOMPLETE_SPLITS,
                "\"$label\" is missing the screen-density part of the game (a config.*dpi split). Supply the complete set, for example " +
                    "the whole XAPK or APKM file.")
        }
        if (libraryHolders.isEmpty()) {
            throw PatchFailure(FailureCode.INCOMPLETE_SPLITS,
                "\"$label\" has no 64-bit ARM program library (${supported.nativeLibraryPath}). OpenKnights needs the arm64 build of the game.")
        }
        if (libraryHolders.size > 1) {
            throw PatchFailure(FailureCode.MISMATCHED_SPLITS, "\"$label\" holds the program library twice (${libraryHolders.joinToString { it.first.name }}).")
        }
        val abiSplit = libraryHolders.single().first.takeIf { it !== base }
        val used = listOfNotNull(base, abiSplit) + densitySplits

        // A damaged download shows up as a checksum error somewhere in the files we copy.
        for (apk in used) {
            val damaged = apk.archive.verifyAll()
            if (damaged.isNotEmpty()) {
                throw PatchFailure(FailureCode.DAMAGED_FILE,
                    "\"${apk.name}\" is damaged: ${damaged.size} of its files do not match their checksums (for example ${damaged.first()}). Download it again.")
            }
        }

        val checks = LinkedHashMap<String, String>()
        val differences = ArrayList<String>()
        val dexNames = base.archive.entries.map { it.name }.filter { Regex("classes\\d*\\.dex").matches(it) }.sorted()
        if (dexNames.toSet() != supported.dex.keys) differences += "program files ${dexNames.joinToString()} instead of ${supported.dex.keys.sorted().joinToString()}"
        for ((dexName, expected) in supported.dex.toSortedMap()) {
            val entry = base.archive[dexName] ?: continue
            val actual = Hashing.sha256(base.archive.openStream(entry))
            if (actual == expected) checks[dexName] = actual else differences += dexName
        }
        val (libApk, libEntry) = libraryHolders.single()
        val libHash = Hashing.sha256(libApk.archive.openStream(libEntry))
        if (libHash == supported.nativeLibrarySha256) checks[supported.nativeLibraryPath] = libHash else differences += supported.nativeLibraryPath
        val tableResult = checkTables(base)
        if (tableResult.isEmpty()) checks["tables"] = "${supported.tables.size} game tables match" else differences += tableResult
        if (differences.isNotEmpty()) {
            throw PatchFailure(FailureCode.MODIFIED_GAME,
                "\"$label\" says it is ${supported.label}, but its contents differ from the supported copy (" +
                    differences.take(3).joinToString("; ") + (if (differences.size > 3) "; and ${differences.size - 3} more" else "") +
                    "). The download may be damaged or modified; get a clean copy.")
        }
        val fingerprint = Hashing.sha256(used.joinToString("\n") { apk ->
            val manifestHash = Hashing.sha256(apk.archive.read("AndroidManifest.xml"))
            val arsc = apk.archive["resources.arsc"]?.let { Hashing.sha256(apk.archive.openStream(it)) } ?: "-"
            "${apk.manifest.split ?: "base"}:$manifestHash:$arsc:${apk.archive.entries.size}"
        }.toByteArray() + checks.values.joinToString().toByteArray())
        val notes = unused.map { "Not used: ${it.name} (split ${it.manifest.split} is not needed)" }
        return IdentifiedInput(label, kind, base, abiSplit, densitySplits, unused, libApk to libEntry, files, checks, notes, fingerprint, resources.toList())
    }

    /** Returns what differs from the supported tables (empty when every table matches). */
    private fun checkTables(base: ApkSource): List<String> {
        val cipher = AssetCipher(supported.tableKey)
        val present = base.archive.entries.filter { it.name.startsWith(supported.tablePrefix) && it.name.endsWith(".csv") }
            .associateBy { it.name.removePrefix(supported.tablePrefix) }
        val missing = supported.tables.keys.filter { it !in present }.sorted()
        val extra = present.keys.filter { it !in supported.tables }.sorted()
        val different = supported.tables.toSortedMap().filter { (name, expected) ->
            val entry = present[name] ?: return@filter false
            Hashing.sha256(cipher.decrypt(base.archive.read(entry))) != expected
        }.keys
        return buildList {
            if (missing.isNotEmpty()) add("${missing.size} game tables missing, e.g. ${missing.first()}")
            if (different.isNotEmpty()) add("${different.size} game tables changed, e.g. ${different.first()}")
            if (extra.isNotEmpty()) add("${extra.size} extra game tables, e.g. ${extra.first()}")
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

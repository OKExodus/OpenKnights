package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.IdentifiedInput
import io.github.okexodus.openknights.patcher.input.InputScanner
import io.github.okexodus.openknights.gamedata.SupportedInput
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.signing.ApkSigning
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import io.github.okexodus.openknights.patcher.util.Hashing
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Progress lines, each with a UTC time; kept for the log file and passed on to [sink] (the console). */
class PatchLog(private val sink: (String) -> Unit = {}) {
    private val lines = ArrayList<String>()

    fun info(message: String) {
        lines += "${Instant.now().truncatedTo(ChronoUnit.SECONDS)}  $message"
        sink(message)
    }

    fun text(): String = lines.joinToString("\n", postfix = "\n")
}

/** What a finished patch produced. */
class PatchResult(
    val apk: Path,
    val report: Path,
    val checksum: Path,
    val log: Path,
    val sha256: String,
    val size: Long,
    val inputName: String,
    val notes: List<String>,
    /** True when the same APK was already there (the files were left as they were, the report refreshed). */
    val unchanged: Boolean,
    val keyFile: Path,
    val keyCreated: Boolean,
    val certificateSha256: String,
)

/**
 * One patch: find and check the game in [input], then write `OpenKnights-<version>.apk`, its report, SHA-256 file and
 * log into [outputDir]. Nothing is written when the input is not supported. The APK is written under a temporary name
 * and renamed only when it is complete, signed and verified; the input is only ever read.
 */
class Patcher(
    private val options: PatchOptions = PatchOptions(),
    private val keys: KeyStorage = KeyStorage(),
    private val supported: SupportedInput = SupportedInput.bundled,
    private val apkPatcher: ApkPatcher = ApkPatcher(options),
    /** Free space the patch needs beyond the output, so the disk is never filled to the last byte. */
    private val spareBytes: Long = 64L shl 20,
) {
    val apkName: String get() = "OpenKnights-${options.version}.apk"

    fun run(input: Path, outputDir: Path, log: PatchLog): PatchResult {
        log.info("OpenKnights Patcher ${BuildInfo.version}")
        log.info("Checking ${input.fileName ?: input}")
        val identified = InputScanner(supported).identify(input)
        identified.use { game ->
            log.info("Found: ${supported.label} (${game.displayName}) - supported")
            game.notes.forEach { log.info(it) }
            prepareOutput(outputDir, game)
            val (key, created) = try { keys.loadOrCreate() } catch (e: PatchFailure) { throw e } catch (e: Exception) {
                throw PatchFailure(FailureCode.SIGNING_KEY_PROBLEM, "The signing key could not be made or read in ${keys.directory}: ${e.message}", cause = e)
            }
            log.info(if (created) "Created your signing key" else "Using your signing key")
            val final = outputDir.resolve(apkName)
            val unsignedTemp = outputDir.resolve(".$apkName.unsigned.tmp")
            val signedTemp = outputDir.resolve(".$apkName.tmp")
            val report = Report()
            try {
                report["patcher_version"] = BuildInfo.version.toString()
                report["created_utc"] = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
                report["mode"] = options.mode
                report["input"] = mapOf(
                    "name" to game.displayName, "kind" to game.kind, "supported" to supported.label,
                    "files" to game.files.map { mapOf("name" to it.name, "size" to it.size, "sha256" to it.sha256) },
                    "used" to game.apks.map { it.name }, "checks" to game.checks, "notes" to game.notes,
                )
                apkPatcher.build(game, unsignedTemp, report) { log.info(it) }
                log.info("Signing")
                ApkSigning.sign(unsignedTemp, signedTemp, key, "OpenKnights Patcher ${BuildInfo.version}")
                Files.delete(unsignedTemp)
                val check = ApkSigning.verify(signedTemp, key.certificateSha256)
                log.info("Signature checked: v1, v2 and v3")
                val sha256 = Hashing.sha256(signedTemp)
                val size = Files.size(signedTemp)
                val unchanged = Files.isRegularFile(final) && Files.size(final) == size && Hashing.sha256(final) == sha256
                if (unchanged) Files.delete(signedTemp) else moveIntoPlace(signedTemp, final)
                report.step("sign", mapOf("schemes" to listOf("v1", "v2", "v3"), "certificate_sha256" to key.certificateSha256,
                    "key_file" to keys.file.fileName.toString(), "key_created" to created, "warnings" to check.warnings.size))
                report["output"] = mapOf("file" to apkName, "size" to size, "sha256" to sha256,
                    "package" to options.packageName, "version_name" to options.version.toString(), "version_code" to options.version.versionCode)
                val reportFile = outputDir.resolve("$apkName.report.json")
                val checksumFile = outputDir.resolve("$apkName.sha256")
                val logFile = outputDir.resolve("$apkName.log")
                writeText(reportFile, report.encode())
                writeText(checksumFile, "$sha256  $apkName\n")
                log.info(if (unchanged) "Already up to date: $apkName (the same file as before)" else "Written: $apkName")
                writeText(logFile, log.text())
                return PatchResult(final, reportFile, checksumFile, logFile, sha256, size, game.displayName, game.notes, unchanged,
                    keys.file, created, key.certificateSha256)
            } catch (e: Throwable) {
                Files.deleteIfExists(unsignedTemp)
                Files.deleteIfExists(signedTemp)
                throw translate(e, outputDir)
            }
        }
    }

    private fun prepareOutput(outputDir: Path, game: IdentifiedInput) {
        try {
            Files.createDirectories(outputDir)
            val probe = Files.createTempFile(outputDir, ".openknights-write-test-", ".tmp")
            Files.delete(probe)
        } catch (e: IOException) {
            throw PatchFailure(FailureCode.OUTPUT_NOT_WRITABLE,
                "The patcher cannot write to \"${outputDir.fileName ?: outputDir}\" (${e.javaClass.simpleName}: ${e.message}). Check that the folder " +
                    "is not read-only and that you may write there, or move the patcher to a folder you own.", cause = e)
        }
        // The unsigned and the signed copy exist at the same time for a moment.
        val estimate = game.apks.sumOf { apk -> apk.archive.source.size } + game.nativeLibrary.second.size
        val needed = 2 * estimate + spareBytes
        val free = Files.getFileStore(outputDir).usableSpace
        if (free < needed) {
            throw PatchFailure(FailureCode.NOT_ENOUGH_SPACE,
                "There is not enough free disk space for the patched app: it needs about ${megabytes(needed)} MB while it works, and " +
                    "${megabytes(free)} MB are free. Free some space and run the patcher again.")
        }
    }

    private fun translate(e: Throwable, outputDir: Path): Throwable = when {
        e is PatchFailure -> e
        e is IOException && isOutOfSpace(e) -> PatchFailure(FailureCode.NOT_ENOUGH_SPACE,
            "The disk became full while writing the patched app. Free some space and run the patcher again. Nothing was left behind.", cause = e)
        e is AccessDeniedException -> PatchFailure(FailureCode.OUTPUT_NOT_WRITABLE,
            "The patcher could not write in \"${outputDir.fileName}\" (${e.message}). Close any program that uses the old app file and try again.", cause = e)
        else -> e
    }

    private fun isOutOfSpace(e: IOException): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return "no space" in text || "not enough space" in text || "disk full" in text || "there is not enough space" in text
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeText(target: Path, text: String) {
        val temp = target.resolveSibling(".${target.fileName}.tmp")
        Files.writeString(temp, text)
        moveIntoPlace(temp, target)
    }

    private fun megabytes(bytes: Long): Long = (bytes + (1L shl 20) - 1) shr 20
}

package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.InputScanner
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.signing.ApkSigning
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import io.github.okexodus.openknights.patcher.signing.SigningKey
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local-only: the real originals patched once per test run into build/tmp/real-build (unsigned + signed with a
 * throw-away test key), shared by the tests that inspect the result.
 */
object RealBuild {
    val directory: Path = Path.of("build", "tmp", "real-build")

    class Output(val unsigned: Path, val signed: Path, val report: Report, val key: SigningKey)

    private var cached: Output? = null

    val key: SigningKey by lazy {
        val storage = KeyStorage(directory.resolve("key"))
        storage.loadOrCreate().first
    }

    fun build(): Output {
        LocalOriginals.require()
        cached?.let { return it }
        Files.createDirectories(directory)
        val unsigned = directory.resolve("unsigned.apk")
        val signed = directory.resolve("signed.apk")
        Files.deleteIfExists(unsigned)
        Files.deleteIfExists(signed)
        val report = Report()
        InputScanner().identify(LocalOriginals.require()).use { input ->
            ApkPatcher().build(input, unsigned, report)
        }
        ApkSigning.sign(unsigned, signed, key, "OpenKnights Patcher test")
        return Output(unsigned, signed, report, key).also {
            Files.writeString(directory.resolve("report.json"), report.encode())
            cached = it
        }
    }
}

package io.github.okexodus.openknights.patcher.cli

import io.github.okexodus.openknights.patcher.BuildInfo
import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.PatchLog
import io.github.okexodus.openknights.patcher.PatchOptions
import io.github.okexodus.openknights.patcher.PatchResult
import io.github.okexodus.openknights.patcher.Patcher
import io.github.okexodus.openknights.patcher.ServerMode
import io.github.okexodus.openknights.patcher.signing.KeyStorage
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli(System.out, System.err).run(args.toList()))
}

/** The command line. [run] returns the exit code: 0 on success, the failure's code otherwise, 2 for bad usage. */
class Cli(
    private val out: PrintStream,
    private val err: PrintStream,
    private val keys: KeyStorage = KeyStorage(),
    /** The folder holding original/ and patched/ when none is given. */
    private val home: Path = defaultHome(),
    private val patcherFor: (PatchOptions, KeyStorage) -> Patcher = { options, keys -> Patcher(options, keys) },
) {
    fun run(args: List<String>): Int = try {
        when (args.firstOrNull()) {
            "--version", "-v" -> { out.println("OpenKnights Patcher ${BuildInfo.version}"); 0 }
            "--help", "-h", "help" -> { out.print(HELP); 0 }
            "key" -> key(args.drop(1))
            "patch" -> patch(args.drop(1))
            null -> patch(emptyList())
            else -> if (args.first().startsWith("--")) patch(args) else usage("Unknown command \"${args.first()}\".")
        }
    } catch (e: PatchFailure) {
        err.println()
        err.println(e.message)
        e.details.forEach { err.println("  $it") }
        e.code.exitCode
    } catch (e: UsageError) {
        usage(e.message ?: "")
    } catch (e: Exception) {
        err.println()
        err.println("Something went wrong inside the patcher: ${e.javaClass.simpleName}: ${e.message}")
        err.println("Please report it with the log from the patched folder.")
        FailureCode.INTERNAL_ERROR.exitCode
    }

    private class UsageError(message: String) : Exception(message)

    private fun usage(message: String): Int {
        err.println(message)
        err.println("Run \"openknights-patcher --help\" for the options.")
        return 2
    }

    private fun patch(args: List<String>): Int {
        var input: Path? = null
        var output: Path? = null
        var report: Path? = null
        var folder: Path? = null
        var i = 0
        fun value(): String = args.getOrNull(++i) ?: throw UsageError("${args[i - 1]} needs a value.")
        while (i < args.size) {
            when (val arg = args[i]) {
                "--input" -> input = Path.of(value())
                "--output" -> output = Path.of(value())
                "--report" -> report = Path.of(value())
                "--folder" -> folder = Path.of(value())
                "--dev-server" -> Unit     // the only mode of this version; kept for the later standalone mode
                else -> throw UsageError("Unknown option \"$arg\".")
            }
            i++
        }
        val root = folder ?: home
        val original = input ?: root.resolve("original")
        val patched = output ?: root.resolve("patched")
        if (input == null && !Files.isDirectory(original)) {
            Files.createDirectories(original)
            Files.createDirectories(patched)
            out.println("OpenKnights Patcher ${BuildInfo.version}")
            out.println()
            out.println("Made the folders \"original\" and \"patched\" in $root.")
            out.println("Put your copy of Pocket Knights 4.4.9 in \"original\" (one APK, an XAPK or APKM file, or a folder with the")
            out.println("split APKs), then run the patcher again.")
            return FailureCode.EMPTY_FOLDER.exitCode
        }
        val patcher = patcherFor(PatchOptions(mode = ServerMode.DEV_SERVER), keys)
        val result = patcher.run(original, patched, PatchLog { out.println(it) })
        report?.let { Files.copy(result.report, it, StandardCopyOption.REPLACE_EXISTING) }
        printResult(result)
        return 0
    }

    private fun printResult(r: PatchResult) {
        out.println()
        out.println("Done: ${r.apk}")
        out.println("  Size: ${(r.size + (1L shl 20) - 1) shr 20} MB   SHA-256: ${r.sha256}")
        out.println("  Report: ${r.report.fileName}   Log: ${r.log.fileName}")
        out.println()
        out.println("This version of OpenKnights plays against the OpenKnights server running on a PC. Connect the phone or emulator")
        out.println("with adb and forward the ports 17777, 17778 and 19121 (adb reverse tcp:17777 tcp:17777, and so on). The app with")
        out.println("the server inside comes in a later version.")
        out.println()
        out.println("Installing and updating:")
        out.println("  - Install the app like any APK (allow installing from this source when Android asks).")
        out.println("  - To update, patch again with a newer patcher and install over the old app. Your saves stay, because every")
        out.println("    app you patch is signed with your own signing key.")
        out.println("  - Android only updates an app signed with the same key. An app signed with another key must be uninstalled")
        out.println("    first, and uninstalling deletes the app's saves.")
        out.println()
        if (r.keyCreated) {
            out.println("Your personal signing key was created: ${r.keyFile}")
            out.println("Back it up now (\"openknights-patcher key export <file>\") and keep the copy safe. Without it, a new PC or")
            out.println("a reinstalled system cannot make updates that install over this app.")
        } else {
            out.println("Signed with your signing key: ${r.keyFile}")
        }
    }

    private fun key(args: List<String>): Int {
        when (args.firstOrNull()) {
            "show", null -> {
                if (!keys.exists()) {
                    out.println("There is no signing key yet. It is made the first time you patch, in: ${keys.directory}")
                } else {
                    val key = keys.load()
                    out.println("Signing key: ${keys.file}")
                    out.println("Certificate SHA-256: ${key.certificateSha256}")
                }
            }
            "export" -> {
                val target = Path.of(args.getOrNull(1) ?: throw UsageError("key export needs the backup file name."))
                keys.export(target)
                out.println("Your signing key was copied to $target. Keep it somewhere safe; anyone with it can sign apps as you.")
            }
            "import" -> {
                val source = Path.of(args.getOrNull(1) ?: throw UsageError("key import needs the backup file."))
                val replace = args.drop(2).let { rest ->
                    rest.forEach { if (it != "--replace") throw UsageError("Unknown option \"$it\".") }
                    "--replace" in rest
                }
                val key = keys.import(source, replace)
                out.println("This signing key is now used: ${keys.file}")
                out.println("Certificate SHA-256: ${key.certificateSha256}")
            }
            else -> throw UsageError("Unknown key command \"${args.first()}\" (use show, export or import).")
        }
        return 0
    }

    companion object {
        /** Next to the patcher (the folder above app/ when run from the release folder), else the current folder. */
        fun defaultHome(): Path {
            val location = runCatching { Path.of(Cli::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            val parent = location?.parent
            if (location != null && location.toString().endsWith(".jar") && parent?.fileName?.toString() == "app") return parent.parent
            return Path.of("").toAbsolutePath()
        }

        val HELP = """
            OpenKnights Patcher ${BuildInfo.version}

            Turns your own copy of Pocket Knights 4.4.9 into the OpenKnights app.

            Usage:
              openknights-patcher                    patch the game in the "original" folder; the app appears in "patched"
              openknights-patcher patch [options]    the same, with options:
                  --input <file or folder>           the game to patch (APK, XAPK, APKM, or a folder of split APKs)
                  --output <folder>                  where to write the app
                  --folder <folder>                  use the "original" and "patched" folders in this folder
                  --report <file>                    also copy the patch report (JSON) to this file
                  --dev-server                       an app that plays against a server on a PC (the only kind for now)
              openknights-patcher key show           where your signing key is, and its fingerprint
              openknights-patcher key export <file>  copy your signing key to a backup file
              openknights-patcher key import <file> [--replace]
                                                     use a signing key from a backup (for example on a new PC)
              openknights-patcher --version

        """.trimIndent() + "\n"
    }
}

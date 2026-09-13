package io.github.okexodus.openknights.server.pc

import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """OpenKnights server (PC)

    openknights-server --apk <the game's APK> --release-data <folder> --data-root <folder>
                       [--bind 127.0.0.1] [--login-port 17777] [--game-port 19121] [--auth-port 17778]
                       [--device-game-port 19121] [--log-file <file>]

Runs the same server the app will carry, on this PC: a phone or emulator reaches it through `adb reverse` on the
three ports. Everything it keeps lives in the data root; the APK is only read."""

fun main(args: Array<String>) {
    val options = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--") || i + 1 >= args.size) { System.err.println(USAGE); exitProcess(2) }
        options[key.removePrefix("--")] = args[i + 1]
        i += 2
    }
    val apk = options["apk"]
    val releaseData = options["release-data"]
    val dataRoot = options["data-root"]
    if (apk == null || releaseData == null || dataRoot == null || options.keys.any { it !in setOf("apk", "release-data", "data-root", "bind",
            "login-port", "game-port", "auth-port", "device-game-port", "log-file") }) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val logFile = options["log-file"]?.let { Path.of(it) } ?: Path.of(dataRoot, "logs", "service-${PyTime.nowStamp()}.jsonl")
    val log = ServiceLog(logFile)
    val service = try {
        Service.release(JdbcSqlDriver(), Path.of(dataRoot), Path.of(apk), Path.of(releaseData), log)
    } catch (e: IllegalArgumentException) {
        log.log("start_refused", "reason" to e.message)
        System.err.println(e.message)
        exitProcess(1)
    }
    val gamePort = options["game-port"]?.toInt() ?: 19121
    val listeners = Listeners(service, InetAddress.getByName(options["bind"] ?: "127.0.0.1"), options["login-port"]?.toInt() ?: 17777,
        gamePort, options["auth-port"]?.toInt() ?: 17778, options["device-game-port"]?.toInt() ?: gamePort).start()
    service.settle("start")
    log.log("ready", "mode" to "release", "login_port" to (options["login-port"] ?: "17777").toInt(), "game_port" to gamePort,
        "auth_port" to (options["auth-port"] ?: "17778").toInt(), "data_root" to "(given)", "born" to (service.world != null))
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        listeners.close()
        service.close()
        log.log("stopped")
        log.close()
        stopped.countDown()
    })
    // Stop with Ctrl+C, or type "stop" on standard input (handy when started by Gradle).
    Thread.ofVirtual().start {
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
            if (line.trim() == "stop") exitProcess(0)
        }
    }
    stopped.await()
}

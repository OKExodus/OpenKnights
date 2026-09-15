package io.github.okexodus.openknights.server.android

import android.content.Context
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Boots the OpenKnights server inside the app process, so the game plays fully offline (Airplane Mode) with no PC.
 *
 * The patched app calls [boot] once from its `Application.onCreate` (the patcher's start hook). The server keeps
 * everything in app-private storage (`filesDir/data-root`), reads the game tables from the app's own APK, and serves
 * the same three loopback listeners the client already connects to (17777 login, 19121 game, 17778 sign-in) — so no
 * client change and no `adb reverse` are needed on the device.
 *
 * The release-data files and the sign-in page ship as APK assets under `assets/openknights/` (added by the patcher);
 * the release-data is unpacked to `filesDir/release-data` on the first boot.
 */
object AndroidServerHost {
    private const val ASSET_ROOT = "openknights"
    @Volatile private var started = false
    @Volatile var listeners: AndroidListeners? = null
        private set

    /** Idempotent: safe to call again; the server boots only once per process. Never throws to the caller. */
    @Synchronized
    fun boot(context: Context) {
        if (started) return
        started = true
        Thread({
            try {
                start(context.applicationContext)
            } catch (e: Throwable) {
                // A boot failure must not crash the app; the client will simply fail to connect and show its own error.
                android.util.Log.e("OpenKnights", "server boot failed", e)
            }
        }, "openknights-boot").apply { isDaemon = true }.start()
    }

    private fun start(context: Context) {
        val files = context.filesDir
        val dataRoot = File(files, "data-root").toPath().also { Files.createDirectories(it) }
        val releaseData = File(files, "release-data").toPath()
        unpackReleaseData(context, releaseData)
        val apk = Path.of(context.applicationInfo.sourceDir)
        val logFile = File(files, "logs/service-${PyTime.nowStamp()}.jsonl").toPath()
        Files.createDirectories(logFile.parent)
        val log = ServiceLog(logFile)
        val service = Service.release(AndroidSqlDriver(), dataRoot, apk, releaseData, log)
        val page = readAsset(context, "$ASSET_ROOT/signin.html")
        val started = AndroidListeners(service, page, InetAddress.getByName("127.0.0.1")).start()
        listeners = started
        service.settle("start")
        log.log("ready", "mode" to "release", "login_port" to started.boundLoginPort, "game_port" to started.boundGamePort,
            "auth_port" to started.boundAuthPort, "on_device" to true, "born" to (service.world != null))
    }

    /** Copy every `assets/openknights/release-data` file into `target` (overwrite keeps it current across app updates). */
    private fun unpackReleaseData(context: Context, target: Path) {
        Files.createDirectories(target)
        val names = context.assets.list("$ASSET_ROOT/release-data") ?: emptyArray()
        require(names.isNotEmpty()) { "No release-data assets in the APK (patcher did not add them)" }
        for (name in names) {
            context.assets.open("$ASSET_ROOT/release-data/$name").use { input ->
                Files.copy(input, target.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun readAsset(context: Context, path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}

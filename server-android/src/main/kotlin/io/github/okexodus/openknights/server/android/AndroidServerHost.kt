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
        // Auto-export a rolling backup of the born world to Download/OpenKnights (no permission, no root), before the
        // server accepts clients, so it is a consistent snapshot. A fresh (unborn) root has nothing to back up.
        val root = service.dataRoot
        if (root != null && service.world != null) {
            val name = Backups.exportRolling(context, root, service.driver, service.clock) { message, e ->
                log.log("auto_backup_error", "message" to message)
                android.util.Log.w("OpenKnights", message, e)
            }
            if (name != null) log.log("auto_backup", "file" to name)
        }
        val page = readAsset(context, "$ASSET_ROOT/signin.html")
        val started = AndroidListeners(service, page, InetAddress.getByName("127.0.0.1")).start()
        listeners = started
        service.settle("start")
        log.log("ready", "mode" to "release", "login_port" to started.boundLoginPort, "game_port" to started.boundGamePort,
            "auth_port" to started.boundAuthPort, "on_device" to true, "born" to (service.world != null))
    }

    /** Copy the `assets/openknights/release-data` tree (including subdirectories) into `target`. */
    private fun unpackReleaseData(context: Context, target: Path) {
        Files.createDirectories(target)
        var copied = 0
        fun walk(assetDir: String, into: Path) {
            val names = context.assets.list(assetDir) ?: emptyArray()
            for (name in names) {
                val assetPath = "$assetDir/$name"
                val children = context.assets.list(assetPath)
                if (children != null && children.isNotEmpty()) {
                    walk(assetPath, into.resolve(name).also { Files.createDirectories(it) })
                } else {
                    context.assets.open(assetPath).use { input ->
                        Files.copy(input, into.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                        copied++
                    }
                }
            }
        }
        walk("$ASSET_ROOT/release-data", target)
        require(copied > 0) { "No release-data assets in the APK (patcher did not add them)" }
    }

    private fun readAsset(context: Context, path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}

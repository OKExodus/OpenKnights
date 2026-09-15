package io.github.okexodus.openknights.server.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Restore a world from a `.okbackup` (a plain zip) the user opens with OpenKnights from any file manager, no permission
 * and no root. The manifest points a VIEW intent-filter for `application/zip` here; the chosen file arrives as a
 * content URI with a temporary read grant. The bytes are staged in app-private storage and applied on the next boot,
 * before the server opens the data root (see [AndroidServerHost]), so restore never races the live databases.
 */
class RestoreActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: intentStream()
        if (uri == null) { finish(); return }
        try {
            val bytes = contentResolver.openInputStream(uri).use { input ->
                (input ?: throw IllegalStateException("cannot read the chosen file")).readBytes()
            }
            if (!looksLikeFullBackup(bytes)) {
                toast("That file is not an OpenKnights world backup.")
                finish(); return
            }
            File(filesDir, PENDING).writeBytes(bytes)
            toast("Backup imported. Restarting OpenKnights to restore it...")
            restart()
        } catch (e: Throwable) {
            android.util.Log.e("OpenKnights", "restore import failed", e)
            toast("Could not import this backup.")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun intentStream(): Uri? = intent?.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    /** Cheap sanity check so an unrelated zip does not force a pointless restart; restoreFull validates fully later. */
    private fun looksLikeFullBackup(bytes: ByteArray): Boolean = try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    val text = zip.readBytes().decodeToString()
                    return text.contains("openknights_backup") && text.contains("\"full\"")
                }
                entry = zip.nextEntry
            }
            false
        }
    } catch (e: Throwable) { false }

    /** Cold-restart so the pending restore is applied at boot. If this does not relaunch, reopening the app still applies it. */
    private fun restart() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val PENDING = "pending-restore.okbackup"
    }
}

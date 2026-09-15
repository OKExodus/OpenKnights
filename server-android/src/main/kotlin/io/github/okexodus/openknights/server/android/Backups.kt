package io.github.okexodus.openknights.server.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.SaveManagement
import io.github.okexodus.openknights.server.store.SqlDriver
import java.io.File
import java.nio.file.Files

/**
 * Auto-exports the whole active world as one `.okbackup` into the phone's public `Download/OpenKnights` folder, which
 * any file manager (and a PC over USB) can browse, copy and delete with no permission and no root. A rolling set is
 * kept (the newest [KEEP]); one snapshot is written on each launch, before the server accepts clients, so it is a
 * consistent point-in-time copy. Restoring is a separate, explicit action; wiping to a fresh start is Android's own
 * "Clear data". The live save databases stay in app-private internal storage, so they keep full integrity.
 */
object Backups {
    private const val RELATIVE_PATH = "Download/OpenKnights"
    private const val KEEP = 5
    private const val MIME = "application/zip"

    /**
     * Write a snapshot of [root] to `Download/OpenKnights` and prune old ones. Returns the file name written, or null
     * when nothing was written (a platform older than scoped Downloads, or a failure). Never throws.
     */
    fun exportRolling(context: Context, root: DataRoot, driver: SqlDriver, clock: DeviceClock,
                      onError: (String, Throwable) -> Unit): String? {
        // MediaStore's no-permission write to public Downloads is API 29+; older devices simply get no auto-export.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val temp = File(context.cacheDir, "export-${PyTime.nowStamp()}.okbackup").toPath()
        return try {
            SaveManagement.backupFull(root, temp, driver, clock)
            // A .zip name (the snapshot is a zip); restore reads it by content, and a plain zip is easy to inspect.
            val name = "openknights-${PyTime.nowStamp()}.zip"
            writeToDownloads(context, name, Files.readAllBytes(temp))
            prune(context)
            name
        } catch (e: Throwable) {
            onError("auto-backup export failed", e)
            null
        } finally {
            try { Files.deleteIfExists(temp) } catch (_: Exception) {}
        }
    }

    private fun writeToDownloads(context: Context, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)   // hidden from other apps until the bytes are fully written
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused a Downloads entry")
        resolver.openOutputStream(uri).use { out ->
            (out ?: throw IllegalStateException("MediaStore gave no output stream")).write(bytes)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Keep only the newest [KEEP] backups this app wrote to the folder (never the user's own copies). */
    private fun prune(context: Context) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        // OWNER_PACKAGE_NAME limits this to files THIS app created, so a copy the user made is never deleted.
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf("%OpenKnights%", context.packageName)
        val ids = ArrayList<Long>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args,
            "${MediaStore.Downloads.DISPLAY_NAME} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) ids.add(cursor.getLong(idColumn))
        }
        // The names carry a sortable timestamp, so DESC lists newest first; drop everything past the newest KEEP.
        ids.drop(KEEP).forEach { id ->
            resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
        }
    }
}

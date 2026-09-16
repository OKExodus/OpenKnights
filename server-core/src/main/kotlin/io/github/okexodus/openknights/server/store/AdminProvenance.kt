package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex

/**
 * Save-local evidence that an administrative command has been used.
 *
 * This is deliberately tamper-evident rather than a security boundary. The marker is kept
 * separately from the player row and is echoed into every subsequent history detail after it
 * becomes true. A pre-command save has neither marker nor evidence and therefore remains clean.
 */
object AdminProvenance {
    const val TABLE = "admin_provenance"
    const val SCHEMA_VERSION = 1L
    private const val DOMAIN = "openknights-admin-provenance-v1"

    const val DDL = """CREATE TABLE admin_provenance (
        id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL,
        used INTEGER NOT NULL CHECK(used IN (0,1)), last_revision INTEGER NOT NULL,
        checksum TEXT NOT NULL)"""

    private fun checksum(used: Boolean, revision: Long): String =
        sha256Hex("$DOMAIN|$SCHEMA_VERSION|${if (used) 1 else 0}|$revision".toByteArray())

    fun ensure(db: SqlConnection, used: Boolean = false, revision: Long = 0L) {
        db.execute("CREATE TABLE IF NOT EXISTS $TABLE (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, used INTEGER NOT NULL CHECK(used IN (0,1)), last_revision INTEGER NOT NULL, checksum TEXT NOT NULL)")
        if (db.queryOne("SELECT 1 FROM $TABLE WHERE id=1") == null) {
            db.execute("INSERT INTO $TABLE VALUES(1,?,?,?,?)", SCHEMA_VERSION, if (used) 1 else 0, revision, checksum(used, revision))
        }
    }

    /** Returns true for any evidence of command use, including a malformed or tampered marker. */
    fun used(db: SqlConnection): Boolean {
        return try {
            val marker = db.queryOne("SELECT schema_version,used,last_revision,checksum FROM $TABLE WHERE id=1")
                ?: return true
            val schema = marker.long("schema_version")
            val used = marker.long("used")
            val revision = marker.long("last_revision")
            val valid = schema == SCHEMA_VERSION && used == 1L && revision >= 1L &&
                marker.string("checksum") == checksum(true, revision)
            !valid || used == 1L
        } catch (_: Exception) {
            true
        }
    }

    /** Updates the marker inside the caller's transaction and returns its checksum. */
    fun mark(db: SqlConnection, revision: Long): String {
        ensure(db)
        val checksum = checksum(true, revision)
        db.execute("UPDATE $TABLE SET schema_version=?,used=1,last_revision=?,checksum=? WHERE id=1",
            SCHEMA_VERSION, revision, checksum)
        return checksum
    }

    /** Carries the marker state in history without adding fields to the public player state. */
    fun historyCrumb(used: Boolean, revision: Long): JObj =
        jobj("used" to JBool(used), "revision" to revision, "checksum" to checksum(used, revision))

    fun hasAdminHistory(db: SqlConnection): Boolean =
        db.queryOne("SELECT 1 FROM state_history WHERE action=? OR detail_json LIKE ? LIMIT 1", "admin_command", "%admin_provenance%") != null
}

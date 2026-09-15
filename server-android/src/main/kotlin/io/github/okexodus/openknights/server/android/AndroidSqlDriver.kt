package io.github.okexodus.openknights.server.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.okexodus.openknights.server.store.SqlConnection
import io.github.okexodus.openknights.server.store.SqlDriver
import io.github.okexodus.openknights.server.store.SqlRow
import java.nio.file.Path

/**
 * The on-device implementation of the store's [SqlDriver], backed by Android's own SQLite
 * (`android.database.sqlite`). The store issues raw `BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK` and binds typed
 * parameters, exactly like the JDBC driver on the PC; Android manages transactions through its own API and does not
 * accept those statements through `execSQL`, so this driver intercepts them and maps to
 * `beginTransactionNonExclusive` / `setTransactionSuccessful` + `endTransaction`. Reads bind through `rawQuery`
 * (integer keys are compared under the column's numeric affinity); writes bind through a compiled statement so BLOBs
 * and every other type keep their SQLite type. The harness compares table contents, not raw page bytes, so Android's
 * default WAL journal is fine.
 */
class AndroidSqlDriver(private val busyTimeoutMillis: Int = 10_000) : SqlDriver {

    override fun open(path: Path, mode: SqlDriver.Mode): SqlConnection {
        val flags = when (mode) {
            SqlDriver.Mode.READ_ONLY -> SQLiteDatabase.OPEN_READONLY
            SqlDriver.Mode.READ_WRITE -> SQLiteDatabase.OPEN_READWRITE
            SqlDriver.Mode.CREATE -> SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        }
        val db = SQLiteDatabase.openDatabase(path.toAbsolutePath().toString(), null, flags)
        if (mode != SqlDriver.Mode.READ_ONLY) db.execSQL("PRAGMA busy_timeout=$busyTimeoutMillis")
        return AndroidConnection(db)
    }

    override fun backup(source: Path, target: Path) {
        // A consistent single-file copy through SQLite's VACUUM INTO (the store's page-level backup equivalent).
        val db = SQLiteDatabase.openDatabase(source.toAbsolutePath().toString(), null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("VACUUM INTO ?", arrayOf<Any?>(target.toAbsolutePath().toString()))
        } finally {
            db.close()
        }
    }
}

private class AndroidConnection(private val db: SQLiteDatabase) : SqlConnection {

    override fun execute(sql: String, vararg args: Any?): Int {
        val head = sql.trimStart().takeWhile { !it.isWhitespace() }.uppercase()
        when (head) {
            "BEGIN" -> { db.beginTransactionNonExclusive(); return 0 }
            "COMMIT", "END" -> { db.setTransactionSuccessful(); db.endTransaction(); return 0 }
            "ROLLBACK" -> { db.endTransaction(); return 0 }   // no setTransactionSuccessful => rolled back
        }
        if (args.isEmpty() && head == "BACKUP") {              // the JDBC driver's "backup to <path>"; never on-device
            db.execSQL(sql)
            return 0
        }
        if (head == "INSERT" || head == "UPDATE" || head == "DELETE" || head == "REPLACE") {
            db.compileStatement(sql).use { st ->
                bind(st, args)
                return st.executeUpdateDelete()
            }
        }
        if (args.isEmpty()) {
            db.execSQL(sql)
            return 0
        }
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.execute()
            return 0
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        db.rawQuery(sql, selectionArgs(args)).use { cursor ->
            return rows(cursor)
        }
    }

    override fun script(sql: String) {
        for (statement in splitStatements(sql)) {
            val head = statement.trimStart().takeWhile { !it.isWhitespace() }.uppercase()
            when (head) {
                "BEGIN" -> db.beginTransactionNonExclusive()
                "COMMIT", "END" -> { db.setTransactionSuccessful(); db.endTransaction() }
                "ROLLBACK" -> db.endTransaction()
                else -> db.execSQL(statement)
            }
        }
    }

    override fun close() {
        try { db.close() } catch (_: Exception) {}
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    /** Bind typed parameters (1-based) onto a compiled statement, matching the store's SQLite types. */
    private fun bind(statement: android.database.sqlite.SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> statement.bindNull(index)
                is Long -> statement.bindLong(index, value)
                is Int -> statement.bindLong(index, value.toLong())
                is String -> statement.bindString(index, value)
                is ByteArray -> statement.bindBlob(index, value)
                is Double -> statement.bindDouble(index, value)
                is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
                else -> throw IllegalArgumentException("no SQLite type for ${value::class.simpleName}")
            }
        }
    }

    /**
     * `rawQuery` selection args are bound as text; the store only ever binds integer keys and text in a SELECT, and an
     * integer column's numeric affinity converts the text back before the comparison. A BLOB / Double / null bind in a
     * read would be wrong here, so it is rejected loudly (never reached by the store).
     */
    private fun selectionArgs(args: Array<out Any?>): Array<String>? {
        if (args.isEmpty()) return null
        return Array(args.size) { i ->
            when (val v = args[i]) {
                is String -> v
                is Long -> v.toString()
                is Int -> v.toString()
                is Boolean -> if (v) "1" else "0"
                else -> throw IllegalArgumentException("read bind of ${v?.let { it::class.simpleName } ?: "null"} is not supported")
            }
        }
    }

    private fun rows(cursor: Cursor): List<SqlRow> {
        val columns = cursor.columnNames.toList()
        val out = ArrayList<SqlRow>(cursor.count)
        while (cursor.moveToNext()) {
            val values = ArrayList<Any?>(columns.size)
            for (c in columns.indices) {
                values.add(when (cursor.getType(c)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(c)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(c)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(c)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(c)
                    else -> cursor.getString(c)
                })
            }
            out.add(SqlRow(columns, values))
        }
        return out
    }

    /** Split on semicolons outside quotes (enough for the store's own scripts). */
    private fun splitStatements(sql: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in sql) {
            if (quote != null) { current.append(c); if (c == quote) quote = null; continue }
            when (c) {
                '\'', '"' -> { quote = c; current.append(c) }
                ';' -> { if (current.isNotBlank()) out.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out
    }
}

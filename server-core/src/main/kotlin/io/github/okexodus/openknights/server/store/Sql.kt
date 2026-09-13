package io.github.okexodus.openknights.server.store

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

/**
 * The SQLite access the store needs, behind an interface: on the PC it is JDBC with the sqlite-jdbc driver
 * ([JdbcSqlDriver]); on a device (P5) Android's own SQLite implements the same interface.
 *
 * Connections run in autocommit mode and the store writes its own `BEGIN IMMEDIATE` / `COMMIT`, exactly like the
 * reference (isolation_level=None), so both see the same transaction boundaries. Values are SQLite's own types:
 * Long (INTEGER), String (TEXT), ByteArray (BLOB), Double (REAL) and null.
 */
interface SqlDriver {
    enum class Mode { READ_ONLY, READ_WRITE, CREATE }

    fun open(path: Path, mode: Mode): SqlConnection

    /** A consistent page copy of `source` into a new file `target` (the reference's `Connection.backup`). */
    fun backup(source: Path, target: Path)
}

interface SqlConnection : AutoCloseable {
    /** Run one statement; returns the number of changed rows (0 for DDL). */
    fun execute(sql: String, vararg args: Any?): Int

    fun query(sql: String, vararg args: Any?): List<SqlRow>

    fun queryOne(sql: String, vararg args: Any?): SqlRow? = query(sql, *args).firstOrNull()

    /** Run several statements separated by semicolons (the reference's `executescript`, no implicit commit). */
    fun script(sql: String)

    fun userVersion(): Int = (queryOne("PRAGMA user_version")!![0] as Long).toInt()

    fun tableExists(name: String): Boolean =
        queryOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", name) != null

    /** BEGIN IMMEDIATE … COMMIT, rolled back when the block throws. */
    fun <T> immediate(block: (SqlConnection) -> T): T {
        execute("BEGIN IMMEDIATE")
        try {
            val result = block(this)
            execute("COMMIT")
            return result
        } catch (e: Throwable) {
            try { execute("ROLLBACK") } catch (_: Exception) {}
            throw e
        }
    }

    /** A read transaction (BEGIN … COMMIT): every read in the block sees one snapshot. */
    fun <T> snapshot(block: (SqlConnection) -> T): T {
        execute("BEGIN")
        try {
            return block(this)
        } finally {
            try { execute("COMMIT") } catch (_: Exception) {}
        }
    }
}

/** One result row; values by column index or name. */
class SqlRow(val columns: List<String>, val values: List<Any?>) {
    operator fun get(index: Int): Any? = values[index]
    operator fun get(name: String): Any? {
        val i = columns.indexOf(name)
        require(i >= 0) { "no column $name" }
        return values[i]
    }

    fun long(name: String): Long = (get(name) as Number).toLong()
    fun longOrNull(name: String): Long? = (get(name) as Number?)?.toLong()
    fun string(name: String): String = get(name) as String
    fun stringOrNull(name: String): String? = get(name) as String?
    fun bytes(name: String): ByteArray = get(name) as ByteArray
}

/** SQLite through JDBC (sqlite-jdbc on the classpath at run time). */
class JdbcSqlDriver(private val busyTimeoutMillis: Int = 10_000) : SqlDriver {
    override fun open(path: Path, mode: SqlDriver.Mode): SqlConnection {
        val properties = Properties()
        // sqlite-jdbc open flags: READONLY 0x01, READWRITE 0x02, CREATE 0x04 (SQLite's own values).
        properties["open_mode"] = when (mode) {
            SqlDriver.Mode.READ_ONLY -> "1"
            SqlDriver.Mode.READ_WRITE -> "2"
            SqlDriver.Mode.CREATE -> "6"
        }
        properties["busy_timeout"] = busyTimeoutMillis.toString()
        val connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().toString(), properties)
        connection.autoCommit = true
        return JdbcConnection(connection)
    }

    override fun backup(source: Path, target: Path) {
        open(source, SqlDriver.Mode.READ_ONLY).use { c ->
            // sqlite-jdbc's page-level backup (the SQLite online backup API).
            c.execute("backup to " + quoteSqlPath(target))
        }
    }

    private fun quoteSqlPath(path: Path): String = "'" + path.toAbsolutePath().toString().replace("'", "''") + "'"
}

private class JdbcConnection(private val connection: Connection) : SqlConnection {
    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1
            when (value) {
                null -> statement.setNull(index, java.sql.Types.NULL)
                is Long -> statement.setLong(index, value)
                is Int -> statement.setLong(index, value.toLong())
                is String -> statement.setString(index, value)
                is ByteArray -> statement.setBytes(index, value)
                is Double -> statement.setDouble(index, value)
                is Boolean -> statement.setLong(index, if (value) 1 else 0)
                else -> throw IllegalArgumentException("no SQLite type for ${value::class.simpleName}")
            }
        }
    }

    override fun execute(sql: String, vararg args: Any?): Int {
        if (args.isEmpty() && sql.startsWith("backup to ")) {
            // sqlite-jdbc runs its "backup to" command outside any statement: executeUpdate, no update count to read
            connection.createStatement().use { s -> s.executeUpdate(sql) }
            return 0
        }
        if (args.isEmpty()) {
            connection.createStatement().use { s ->
                return if (s.execute(sql)) 0 else s.updateCount.coerceAtLeast(0)
            }
        }
        connection.prepareStatement(sql).use { s ->
            bind(s, args)
            return if (s.execute()) 0 else s.updateCount.coerceAtLeast(0)
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        connection.prepareStatement(sql).use { s ->
            bind(s, args)
            s.executeQuery().use { rs -> return rows(rs) }
        }
    }

    private fun rows(rs: ResultSet): List<SqlRow> {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
        val out = ArrayList<SqlRow>()
        while (rs.next()) {
            out.add(SqlRow(columns, (1..columns.size).map { i ->
                when (val v = rs.getObject(i)) {
                    is Int -> v.toLong()
                    is Short -> v.toLong()
                    is Byte -> v.toLong()
                    is Float -> v.toDouble()
                    else -> v
                }
            }))
        }
        return out
    }

    override fun script(sql: String) {
        connection.createStatement().use { s ->
            for (statement in splitStatements(sql)) s.execute(statement)
        }
    }

    override fun close() {
        try { connection.close() } catch (_: SQLException) {}
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

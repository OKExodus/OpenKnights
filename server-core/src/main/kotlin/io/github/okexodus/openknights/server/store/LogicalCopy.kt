package io.github.okexodus.openknights.server.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a database from another one's logical content: every table is created from [Schemas] (this port's own
 * CREATE statements, not the source's) and every row is inserted with its row id, then `user_version` and the
 * AUTOINCREMENT counters are set. Used to prove that files this port writes carry exactly the reference's schema
 * and values (the reference must open the copy and see the same fingerprint), and a base for schema migrations.
 */
object LogicalCopy {
    class UnknownTable(name: String) : IllegalArgumentException("No schema for table $name")

    fun copyDatabase(source: Path, target: Path, driver: SqlDriver) {
        require(!Files.exists(target)) { "$target already exists" }
        Files.createDirectories(target.toAbsolutePath().parent)
        driver.open(source, SqlDriver.Mode.READ_ONLY).use { src ->
            driver.open(target, SqlDriver.Mode.CREATE).use { dst ->
                src.snapshot {
                    val tables = src.query("SELECT name FROM sqlite_master WHERE type='table' AND name<>'sqlite_sequence' ORDER BY rowid").map { it.string("name") }
                    dst.immediate {
                        for (table in tables) {
                            dst.execute(Schemas.ddl(table) ?: throw UnknownTable(table))
                            // Only the schema's own indexes (the reference creates one, for sessions).
                            Schemas.REGISTRY_INDEXES.filter { it.contains(" ON $table(") }.forEach { dst.execute(it) }
                            val info = src.query("SELECT name,type,pk FROM pragma_table_info(?) ORDER BY cid", table)
                            val columns = info.map { it.string("name") }
                            val rowidAlias = info.count { it.long("pk") > 0 } == 1 &&
                                info.single { it.long("pk") > 0 }.string("type").equals("INTEGER", ignoreCase = true)
                            val names = (if (rowidAlias) columns else listOf("rowid") + columns).joinToString(",") { "\"$it\"" }
                            val marks = (if (rowidAlias) columns else listOf("rowid") + columns).joinToString(",") { "?" }
                            for (row in src.query("SELECT rowid AS \"_rowid_\", * FROM \"$table\" ORDER BY rowid")) {
                                val values = row.values.drop(1)
                                val args = if (rowidAlias) values else listOf(row.values[0]) + values
                                dst.execute("INSERT INTO \"$table\" ($names) VALUES($marks)", *args.toTypedArray())
                            }
                        }
                        if (src.tableExists("sqlite_sequence")) {
                            for (seq in src.query("SELECT name,seq FROM sqlite_sequence ORDER BY rowid")) {
                                val updated = dst.execute("UPDATE sqlite_sequence SET seq=? WHERE name=?", seq.long("seq"), seq.string("name"))
                                if (updated == 0) dst.execute("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)", seq.string("name"), seq.long("seq"))
                            }
                        }
                        dst.execute("PRAGMA user_version=${src.userVersion()}")
                    }
                }
            }
        }
    }

    /** Every table's stored CREATE statement of `a` equals `b`'s (normalized); returns the differing table names. */
    fun schemaDifferences(a: Path, b: Path, driver: SqlDriver): List<String> {
        fun schemas(p: Path) = driver.open(p, SqlDriver.Mode.READ_ONLY).use { db ->
            db.query("SELECT name,sql FROM sqlite_master WHERE type IN ('table','index') AND sql IS NOT NULL").associate { it.string("name") to Schemas.normalize(it.string("sql")) }
        }
        val sa = schemas(a)
        val sb = schemas(b)
        return (sa.keys + sb.keys).sorted().filter { sa[it] != sb[it] }
    }
}

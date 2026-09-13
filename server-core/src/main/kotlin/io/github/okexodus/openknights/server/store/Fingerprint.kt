package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JFloat
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import java.nio.file.Files
import java.nio.file.Path

/**
 * The logical fingerprint of a data root: what the saves hold, independent of SQLite's page layout, so a root written
 * by one implementation and read by the other can be compared. The same definition exists on the reference side.
 *
 *     {"profile", "manifest": {format, layout_version, app_version, active, schemas},
 *      "databases": {"<path in the generation>": {"user_version", "tables": {"<table>": {"rows", "sha256"}}}}}
 *
 * A table's hash covers `{"columns": [...], "rows": [[rowid, value, ...], ...]}` in rowid order, written canonically;
 * INTEGER → number, TEXT → string, REAL → number, NULL → null, BLOB → `{"blob": hex}`. The databases are every
 * `*.sqlite3` of the active generation (of the unborn one before the first character), temporary files excluded.
 */
object Fingerprint {
    const val PROFILE = "openknights_data_root_fingerprint_v1"

    fun compute(root: DataRoot, driver: SqlDriver): JObj {
        val manifest = root.readManifest() ?: throw DataRootError("The data root has no manifest")
        val active = (manifest["active"] as? JStr)?.value
        val generation = if (active != null) root.worlds.resolve(active) else unborn(root)
        val databases = JObj()
        if (generation != null) {
            val files = Files.walk(generation).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sqlite3") }.toList() }
            val relative = files.map { generation.relativize(it).joinToString("/") }.filter { !it.startsWith(".") && !it.contains("/.") }.sorted()
            for (name in relative) databases[name] = database(generation.resolve(name), driver)
        }
        return jobj("profile" to PROFILE,
            "manifest" to jobj("format" to manifest["format"], "layout_version" to manifest["layout_version"],
                "app_version" to manifest["app_version"], "active" to (manifest["active"] ?: JNull), "schemas" to (manifest["schemas"] ?: JObj())),
            "databases" to databases)
    }

    private fun unborn(root: DataRoot): Path? {
        if (!Files.isDirectory(root.worlds)) return null
        return Files.list(root.worlds).use { s -> s.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve(DataRoot.REGISTRY)) }.sorted().toList() }.firstOrNull()
    }

    fun database(path: Path, driver: SqlDriver): JObj = driver.open(path, SqlDriver.Mode.READ_ONLY).use { db ->
        db.snapshot {
            val tables = JObj()
            for (name in db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").map { it.string("name") }) {
                val rows = db.query("SELECT rowid AS \"_rowid_\", * FROM \"$name\" ORDER BY rowid")
                val columns = db.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", name).map { it.string("name") }
                val out = JArr()
                for (row in rows) {
                    val values = JArr()
                    values.add(JInt(row.long("_rowid_")))
                    // "SELECT rowid AS _rowid_, *": the table's columns follow the row id in declaration order.
                    for (i in 1 until row.values.size) values.add(value(row.values[i]))
                    out.add(values)
                }
                val body = jobj("columns" to columns, "rows" to out)
                tables[name] = jobj("rows" to rows.size, "sha256" to sha256Hex(Json.canonical(body).toByteArray()))
            }
            jobj("user_version" to db.userVersion(), "tables" to tables)
        }
    }

    private fun value(v: Any?): JValue = when (v) {
        null -> JNull
        is Long -> JInt(v)
        is String -> JStr(v)
        is Double -> JFloat(v)
        is ByteArray -> jobj("blob" to v.toHexString())
        else -> throw IllegalArgumentException("unexpected SQLite value ${v::class.simpleName}")
    }

    fun sha256(fingerprint: JObj): String = sha256Hex(Json.canonical(fingerprint).toByteArray())
}

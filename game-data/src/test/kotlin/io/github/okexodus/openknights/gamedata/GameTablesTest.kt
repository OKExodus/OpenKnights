package io.github.okexodus.openknights.gamedata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class GameTablesTest {
    @Test
    fun `the CSV reader matches the reference on every vector`() {
        val stream = javaClass.getResourceAsStream("/vectors/csv.json") ?: error("csv.json is missing")
        val doc = Json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject
        var checked = 0
        val failures = ArrayList<String>()
        for (v in doc["vectors"]!!.jsonArray) {
            val case = v.jsonObject
            val text = case["text"]!!.jsonPrimitive.content
            val source = GameTables.decodeUtf8Sig(text.toByteArray(Charsets.UTF_8))
            val got = try { Csv.parse(source).map { it } } catch (e: Csv.CsvError) { null }
            checked++
            val want = case["rows"]?.jsonArray?.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            if (want == null && got != null) failures.add("expected an error for <${text.take(40)}>, got $got")
            if (want != null && got != want) failures.add("<${text.take(40)}>: expected $want, got $got")
        }
        println("csv: ${checked - failures.size}/$checked match")
        assertTrue(failures.isEmpty()) { failures.take(10).joinToString("\n") }
    }

    @Test
    fun `catalog rules refuse duplicate headers and ragged rows`() {
        assertThrows(IllegalArgumentException::class.java) { GameTables.parse("t.csv", "a,a\n1,2\n".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { GameTables.parse("t.csv", "a,b\n1\n".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { GameTables.parse("t.csv", ByteArray(0)) }
        val table = GameTables.parse("t.csv", (0xFEFF.toChar() + "101,102\n7,x\n7,y\n8,\"q,r\"\n").toByteArray())
        assertEquals(listOf("101", "102"), table.headers)
        assertEquals(listOf("x", "y"), table.lookup("7").map { it.field("102") })
        assertEquals(4, table.rows.last().csvRow)
        assertEquals("q,r", table.lookup(8).single().field(102))
    }

    /** Local only: the 194 tables of the player's APK parse exactly as the reference's catalog parses them. */
    @Test
    fun `the real tables match the reference catalog`() {
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val apk = originals?.resolve("com.enjoygame.hero2d.apk")
        val index = dev?.resolve("tables-index.json")
        assumeTrue(apk != null && Files.isRegularFile(apk) && index != null && Files.isRegularFile(index),
            "OPENKNIGHTS_ORIGINALS / OPENKNIGHTS_DEV_DIR not set: local-only test skipped")
        val expected = Json.parseToJsonElement(Files.readString(index!!)).jsonObject["tables"]!!.jsonObject
        val tables = GameTables(ApkTables(apk!!))
        assertEquals(expected.keys, tables.names().toSet())
        val failures = ArrayList<String>()
        var rows = 0
        for (name in tables.names()) {
            val want = expected[name]!!.jsonObject
            val table = tables.table(name)
            rows += table.rows.size
            val body = buildJsonObject {
                put("headers", JsonArray(table.headers.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("rows", buildJsonArray {
                    table.rows.forEach { row -> add(JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(row.csvRow)) + row.cells.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
                })
            }
            // The reference writes this with ensure_ascii, so compare through its own escaping rules.
            val text = io.github.okexodus.openknights.exact.Json.compact(io.github.okexodus.openknights.exact.Json.loads(body.toString()))
            val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            if (want["result"]!!.jsonPrimitive.content != "ok" || sha != want["sha256"]!!.jsonPrimitive.content ||
                table.rows.size != want["rows"]!!.jsonPrimitive.int) failures.add(name)
        }
        println("real tables: ${tables.names().size - failures.size}/${tables.names().size} match ($rows rows)")
        assertTrue(failures.isEmpty()) { "tables differ: $failures" }
    }
}

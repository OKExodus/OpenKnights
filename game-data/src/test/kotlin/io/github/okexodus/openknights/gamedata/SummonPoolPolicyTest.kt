package io.github.okexodus.openknights.gamedata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SummonPoolPolicyTest {
    @TempDir lateinit var temporary: Path
    private val lotHeader = "101,102,103,104,105,106,107,108,200,201,202,211,212,221,222,241,242,400,401,402,403,404,901,902,903,991"
    private val heroHeader = "101,102,103,104,105,106,301,302"
    private fun source(lots: String, heroes: String = "1,700001,111,10,0,0,,\n") = object : TableSource {
        private val data = mapOf("xinniudan.csv" to (lotHeader + "\n" + lots).toByteArray(), "niudanhero.csv" to (heroHeader + "\n" + heroes).toByteArray(), "other.csv" to "a\nb\n".toByteArray())
        override fun names() = data.keys.toList()
        override fun raw(name: String) = data[name.removeSuffix(".csv") + ".csv"]!!
    }
    private fun policyJson(schema: String = "1", groups: String = "900401,900501,900601") = """
        {"profile":"supreme_summon_pool_v1","schema_version":$schema,"hero_groups":[
        {"group":${groups.split(',')[0]},"entries":[{"hero":111001,"weight":100}]},
        {"group":${groups.split(',')[1]},"entries":[{"hero":222001,"weight":100}]},
        {"group":${groups.split(',')[2]},"entries":[{"hero":333001,"weight":100}]}],
        "bonus_group":{"group":920009,"entries":[{"item":880001,"count":1,"weight":165}]},"lucky_item":770001,"lucky_count":5}
    """.trimIndent()

    @Test fun `transforms every lot 3 row and preserves other fields and groups`() {
        val lots = listOf(0, 1, 2, 3, 3, 3).mapIndexed { index, lot ->
            "${index + 1},$lot,7,${100 + index},${index + 1},${index},${index},0,${500 + index},1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17"
        }.joinToString("\n", postfix = "\n")
        val original = source(lots)
        val transformed = SummonPoolPolicy.parse(policyJson().toByteArray()).transform(original)
        val t = GameTables(transformed)
        val changed = setOf("201", "202", "211", "212", "221", "222", "241", "242", "991", "901", "902")
        for ((before, after) in GameTables(original).table("xinniudan").rows.zip(t.table("xinniudan").rows)) {
            if (before.field(102) != "3") assertEquals(before.fields(), after.fields())
            else {
                assertEquals(before.fields().filterKeys { it !in changed }, after.fields().filterKeys { it !in changed })
                assertEquals("900401", after.field(201))
                assertEquals("920009", after.field(902))
            }
        }
        val lot3 = t.table("xinniudan").rows[3]
        assertEquals("900401", lot3.field(201)); assertEquals("900501", lot3.field(211)); assertEquals("900601", lot3.field(221))
        assertEquals("10000", lot3.field(901)); assertEquals("920009", lot3.field(902)); assertEquals("0", lot3.field(991)); assertEquals("103", lot3.field(104))
        assertEquals("a\nb", transformed.raw("other").toString(Charsets.UTF_8).trim())
        val heroes = t.table("niudanhero").rows
        assertEquals(5, heroes.size)
        assertEquals(GameTables(original).table("niudanhero").rows.first().fields(), heroes.first().fields())
        assertEquals(listOf("900401", "900501", "900601", "920009"), heroes.takeLast(4).map { it.field(102) })
        assertEquals("880001", heroes.last().field(105)); assertEquals("770001", heroes.last().field(301)); assertEquals("", heroes[1].field(105))
        assertEquals("", heroes.last().field(103))
    }

    @Test fun `validation rejects malformed schema values and collisions`() {
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson(schema = "\"1\"").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson(groups = "900401,900401,900601").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson().replace("\"weight\":100", "\"weight\":0").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson().replace("\"hero\":111001", "\"hero\":\"111001\"").toByteArray()) }
        val lots = "1,3,7,100,9,1,0,0,500,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17\n"
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson().replace("900401", "700001").toByteArray()).transform(source(lots)) }
        assertThrows(IllegalArgumentException::class.java) {
            SummonPoolPolicy.parse(policyJson().toByteArray()).transform(source(lots, "1,900401,111,10,0,0,,\n"))
        }
        for (invalid in listOf("-1", "2147483648", "true", "1.5")) {
            assertThrows(IllegalArgumentException::class.java) {
                SummonPoolPolicy.parse(policyJson().replace("\"weight\":100", "\"weight\":$invalid").toByteArray())
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SummonPoolPolicy.parse(policyJson(schema = "2").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            SummonPoolPolicy.parse(policyJson().replace("222001", "111001").toByteArray())
        }
    }

    @Test fun `preserved encrypted originals are accepted only with matching supported hashes`() {
        val original = "101,102\n1,2\n".toByteArray()
        val modified = "101,102\n1,3\n".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        val supported = SupportedInput("Test", "test", "1", 1, emptyMap(), "", "", "assets/data/config/",
            "synthetic-key".toByteArray(), mapOf("xinniudan.csv" to hash))
        val cipher = AssetCipher(supported.tableKey)
        fun archive(name: String, preserved: ByteArray): Path {
            val path = temporary.resolve(name)
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                for ((entry, raw) in mapOf("assets/data/config/xinniudan.csv" to modified,
                    "assets/openknights/original-tables/xinniudan.csv" to preserved)) {
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(cipher.encrypt(raw))
                    zip.closeEntry()
                }
            }
            return path
        }
        val good = archive("good.apk", original)
        assertEquals(original.toList(), ApkTables(good, supported, preferPreservedTables = true).raw("xinniudan").toList())
        assertThrows(UnsupportedGameFile::class.java) { ApkTables(good, supported) }
        assertThrows(UnsupportedGameFile::class.java) {
            ApkTables(archive("bad.apk", modified), supported, preferPreservedTables = true)
        }
    }
}

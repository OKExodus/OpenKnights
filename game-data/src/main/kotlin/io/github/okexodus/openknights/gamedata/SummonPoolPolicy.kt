package io.github.okexodus.openknights.gamedata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Private release-data description of the additional Supreme summon pools. */
class SummonPoolPolicy private constructor(
    val heroGroups: List<HeroGroup>,
    val bonusGroup: BonusGroup,
    val luckyItem: Long,
    val luckyCount: Long,
) {
    data class HeroEntry(val hero: Long, val weight: Long)
    data class HeroGroup(val group: Long, val entries: List<HeroEntry>)
    data class BonusEntry(val item: Long, val count: Long, val weight: Long)
    data class BonusGroup(val group: Long, val entries: List<BonusEntry>)

    /** Returns a view with the Supreme fields overlaid on the two summon tables. */
    fun transform(source: TableSource): TableSource {
        val lots = source.raw("xinniudan.csv")
        val heroes = source.raw("niudanhero.csv")
        val lotTable = GameTables.parse("xinniudan.csv", lots)
        val heroTable = GameTables.parse("niudanhero.csv", heroes)
        val lotHeaders = lotTable.headers.withIndex().associate { it.value to it.index }
        require("102" in lotHeaders) { "xinniudan.csv has no lot field 102" }
        val lotRows = Csv.parse(GameTables.decodeUtf8Sig(lots)).map { it.toMutableList() }.toMutableList()
        val fields = mapOf("201" to "900401", "202" to "3000", "211" to "900501", "212" to "6000",
            "221" to "900601", "222" to "1000", "241" to "0", "242" to "0", "991" to "0", "901" to "10000", "902" to "920009")
        val indices = fields.keys.associateWith { lotHeaders[it] ?: throw IllegalArgumentException("xinniudan.csv has no field $it") }
        for (row in lotRows.drop(1)) if (row[lotHeaders.getValue("102")] == "3") {
            for ((field, value) in fields) row[indices.getValue(field)] = value
        }

        val heroHeaders = heroTable.headers.withIndex().associate { it.value to it.index }
        val required = listOf("101", "102", "103", "104", "105", "106", "301", "302")
        required.forEach { require(it in heroHeaders) { "niudanhero.csv has no field $it" } }
        val heroRows = Csv.parse(GameTables.decodeUtf8Sig(heroes)).map { it.toMutableList() }.toMutableList()
        val existingGroups = heroRows.drop(1).mapNotNull { it[heroHeaders.getValue("102")].toLongOrNull() }.toSet()
        require((heroGroups.map { it.group } + bonusGroup.group).none { it in existingGroups }) {
            "Supreme summon group collides with an existing niudanhero group"
        }
        val ids = heroRows.drop(1).mapNotNull { it[heroHeaders.getValue("101")].toLongOrNull() }
        var nextId = (ids.maxOrNull() ?: 0L) + 1L
        fun append(group: Long, hero: Long, weight: Long, item: Long, count: Long, lucky: Boolean) {
            val row = MutableList(heroTable.headers.size) { "" }
            row[heroHeaders.getValue("101")] = (nextId++).toString()
            row[heroHeaders.getValue("102")] = group.toString()
            row[heroHeaders.getValue("104")] = weight.toString()
            if (lucky) {
                row[heroHeaders.getValue("105")] = item.toString()
                row[heroHeaders.getValue("106")] = count.toString()
                row[heroHeaders.getValue("301")] = luckyItem.toString()
                row[heroHeaders.getValue("302")] = luckyCount.toString()
            } else {
                row[heroHeaders.getValue("103")] = hero.toString()
            }
            heroRows += row
        }
        heroGroups.forEach { group -> group.entries.forEach { append(group.group, it.hero, it.weight, 0, 0, false) } }
        bonusGroup.entries.forEach { append(bonusGroup.group, 0, it.weight, it.item, it.count, true) }
        val out = mapOf("xinniudan.csv" to csv(lotRows), "niudanhero.csv" to csv(heroRows))
        return object : TableSource {
            override fun names(): List<String> = source.names()
            override fun raw(name: String): ByteArray = out[name.removeSuffix(".csv") + ".csv"] ?: source.raw(name)
        }
    }

    private fun csv(rows: List<List<String>>): ByteArray = rows.joinToString("\n", postfix = "\n") { row ->
        row.joinToString(",") { value -> if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value }
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val PROFILE = "supreme_summon_pool_v1"
        fun parse(bytes: ByteArray): SummonPoolPolicy = parse(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
        fun parse(element: JsonElement): SummonPoolPolicy {
            val root = element.jsonObject
            require(root["profile"]?.jsonPrimitive?.content == PROFILE) { "Unsupported summon pool profile" }
            val schema = root["schema_version"]?.jsonPrimitive
            require(schema != null && !schema.isString && schema.content == "1") { "Unsupported summon pool schema_version" }
            fun positive(o: kotlinx.serialization.json.JsonObject, key: String): Long {
                val p = o[key]?.jsonPrimitive ?: throw IllegalArgumentException("Missing or invalid $key")
                require(!p.isString) { "$key must be a JSON number" }
                val value = p.content.toLongOrNull() ?: throw IllegalArgumentException("Missing or invalid $key")
                require(value in 1..Int.MAX_VALUE) { "$key must be positive and fit in Int" }; return value
            }
            val groups = root["hero_groups"]?.jsonArray ?: throw IllegalArgumentException("Missing hero_groups")
            val seenGroups = HashSet<Long>()
            val seenHeroes = HashSet<Long>()
            val expectedGroups = listOf(900401L, 900501L, 900601L)
            require(groups.size == expectedGroups.size) { "hero_groups must contain exactly three groups" }
            val heroGroups = groups.mapIndexed { groupIndex, g ->
                val o = g.jsonObject; val id = positive(o, "group"); require(seenGroups.add(id)) { "Duplicate hero group $id" }
                require(id == expectedGroups[groupIndex]) { "Hero groups must be ordered 900401, 900501, 900601" }
                val entries = o["entries"]?.jsonArray ?: throw IllegalArgumentException("Missing entries")
                require(entries.isNotEmpty()) { "Hero group $id has no entries" }
                HeroGroup(id, entries.map { e -> val x = e.jsonObject; val hero = positive(x, "hero"); require(seenHeroes.add(hero)) { "Duplicate hero $hero" }; HeroEntry(hero, positive(x, "weight")) })
            }
            val bonus = root["bonus_group"]?.jsonObject ?: throw IllegalArgumentException("Missing bonus_group")
            val bonusId = positive(bonus, "group"); require(seenGroups.add(bonusId)) { "Duplicate group $bonusId" }
            require(bonusId == 920009L) { "Bonus group must be 920009" }
            val seenItems = HashSet<Long>(); val bonusArray = bonus["entries"]?.jsonArray ?: throw IllegalArgumentException("Missing bonus entries"); require(bonusArray.isNotEmpty()) { "Bonus group has no entries" }; val bonusEntries = bonusArray.map { e ->
                val x = e.jsonObject; val item = positive(x, "item"); require(seenItems.add(item)) { "Duplicate item $item" }; BonusEntry(item, positive(x, "count"), positive(x, "weight"))
            }
            val lucky = positive(root, "lucky_item")
            val count = positive(root, "lucky_count")
            return SummonPoolPolicy(heroGroups, BonusGroup(bonusId, bonusEntries), lucky, count)
        }

    }
}

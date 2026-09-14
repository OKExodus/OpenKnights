package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * The owned-hero helpers and the alternate-team codec of `secondary_team.py` that fresh characters use: a hero is a
 * typed field list whose field 0 is its owned UID; the S3745 payload is `u8 n, n × (u8 position, fields), u8 max
 * open positions`; the client's lineup rules the alternate team's C3779 checks. (The captured three-position import and
 * replacement policies of derived characters are dev-only.)
 */
object SecondaryTeam {
    /** A local preservation rejection; answered with the generic S6 102. */
    open class Rejected(message: String, val clientCheck: Int? = null) : IllegalArgumentException(message) {
        open val code: Int get() = 102
    }

    fun heroUid(fields: JArr, complete: Boolean = false): Long {
        val ids = fields.map { it.asObj.long("id") }
        require(ids.toSet().size == ids.size) { "Duplicate hero property ID" }
        require(!complete || ids.toSet() == (0L..24L).toSet()) { "Secondary-team hero requires all 25 evidenced fields" }
        val values = fields.map { it.asObj }.filter { it.long("id") == 0L }.map { it.obj("value") }
        require(values.size == 1 && (values[0]["tag"] as? JInt)?.value?.toInt() in setOf(5, 6)) { "Expected one uint32 owned hero UID property" }
        val uid = (values[0]["bits"] as? JInt)?.value?.toLong()
        require(uid != null && uid in 1..0xFFFFFFFFL) { "Owned hero UID must be a positive uint32" }
        return uid
    }

    /** {uid: fields} of the owned heroes, in save order. */
    fun ownedHeroes(state: JObj): LinkedHashMap<Long, JArr> {
        val heroes = LinkedHashMap<Long, JArr>()
        for (fields in state.arr("heroes")) {
            val f = fields as JArr
            val uid = heroUid(f)
            require(uid !in heroes) { "Duplicate owned hero UID" }
            heroes[uid] = f
        }
        return heroes
    }

    fun validateOwner(characterId: String) {
        require(characterId.startsWith("char_") && characterId.length in 6..100) { "A local registry character ID is required, never a wire ID" }
    }

    private fun byte(value: Long, label: String): Int {
        require(value in 0..255) { "$label must be a byte" }
        return value.toInt()
    }

    /** `decode_secondary_team`: the S3745 entries and max open count; the payload must round-trip. */
    fun decodeSecondaryTeam(payload: ByteArray): JObj {
        val r = WireReader(payload)
        val entries = JArr()
        repeat(r.u8()) { entries.add(jobj("position" to r.u8(), "hero" to TypedValues.readFields(r))) }
        val maxOpen = r.u8()
        if (r.offset != payload.size) throw PyValues.ValueError("Trailing bytes in secondary-team payload")
        val again = encodeSecondaryTeam(entries.map { it.asObj.long("position") to it.asObj.arr("hero") }, maxOpen.toLong())
        if (!again.contentEquals(payload)) throw PyValues.ValueError("Secondary-team payload does not round-trip")
        return jobj("entries" to entries, "max_open_positions" to maxOpen)
    }

    private fun uint32(value: JValue?, label: String): Long {
        val v = (value as? JInt)?.value
        if (v == null || v.signum() <= 0 || v.bitLength() > 32) throw Rejected("$label must be positive uint32")
        return v.toLong()
    }

    /** `hero_config_id(fields)`: the one u32 field 1 (the packed template). */
    fun heroConfigId(fields: JArr): Long {
        val values = fields.map { it.asObj }.filter { it["id"] == JInt(1) }.map { it.obj("value") }
        if (values.size != 1 || (values[0]["tag"] as? JInt)?.value?.toInt() !in setOf(5, 6)) throw Rejected("Expected one owned hero configuration ID")
        return uint32(values[0]["bits"], "Hero configuration ID")
    }

    /**
     * The client's CheckHeroLineup type 2 (`NativeLineupRules`): HeroConfig field 500 per base (0 bypasses every
     * duplicate scan) and the RebornConfig (102, 103, 104) triples that relate bases. A related base in the main lineup
     * is the client check 70600, in another alternate slot 70601 (both answered with the generic 102).
     */
    class NativeLineupRules(val heroFlags: Map<Long, Long>, val rebornRows: List<Triple<Long, Long, Long>>) {
        fun check(state: JObj, references: List<JObj>, hero: JArr, position: Long) {
            val heroes = ownedHeroes(state)
            val baseId = heroConfigId(hero) / 1000      // Formula::GetHeroBaseId
            val flag = heroFlags[baseId] ?: throw Rejected("Hero base is absent from verified HeroConfig", clientCheck = 100)
            // Field 500 = 0 bypasses ALL duplicate scans; never a blanket family equivalence.
            if (flag == 0L) return
            val related = mutableListOf(baseId, 0L, 0L)
            for ((first, second, third) in rebornRows) {
                if (baseId == first) {
                    related[1] = second
                    related[2] = third
                    break
                }
                if (baseId == second || baseId == third) {
                    related[1] = first
                    break
                }
            }
            for (e in state.arr("formation")) {
                val uid = e.asObj.getValue("hero_uid")
                if (io.github.okexodus.openknights.server.game.Py.truthy(uid)) {
                    val fields = heroes[(uid as JInt).value.toLong()] ?: throw PyDocs.KeyError(uid)
                    if (heroConfigId(fields) / 1000 in related) throw Rejected("Native primary-lineup conflict", clientCheck = 70600)
                }
            }
            for (entry in references) {
                if (entry["position"] != JInt(position)) {
                    val uid = entry.getValue("hero_uid")
                    val fields = heroes[(uid as JInt).value.toLong()] ?: throw PyDocs.KeyError(uid)
                    if (heroConfigId(fields) / 1000 in related) throw Rejected("Native alternate-lineup conflict", clientCheck = 70601)
                }
            }
        }
    }

    /** The two byte-verified bundled tables the lineup rules are audited for (the supported APK's hero / zhuansheng). */
    val LINEUP_SOURCES = listOf("hero.csv" to "e90e7b1e39fc390e0abaf3a4016bad15ea6c3b5643c9a79f6f65071a1de1919f",
        "zhuansheng.csv" to "c1caeb196aa7da0148225d645ef8edfa1933ec1c0213eba3fc2c1228a16d7495")

    /**
     * `load_native_lineup_rules()`: only the byte-verified versions. The tables come from the player's APK, whose
     * loader already requires every table to hash to the supported definition (`tableHashes`); the rules require that
     * definition to be the audited one.
     */
    fun loadNativeLineupRules(tables: GameTables,
                              tableHashes: Map<String, String> = io.github.okexodus.openknights.gamedata.SupportedInput.bundled.tables): NativeLineupRules {
        val rows = ArrayList<List<io.github.okexodus.openknights.gamedata.GameTable.Row>>()
        for ((name, expected) in LINEUP_SOURCES) {
            if (tableHashes[name] != expected) throw PyValues.ValueError("Verified native lineup configuration hash changed")
            rows.add(tables.table(name).rows)
        }
        fun cell(row: io.github.okexodus.openknights.gamedata.GameTable.Row, key: String): String = row.field(key) ?: throw PyDocs.KeyError("'$key'")
        fun intOr0(text: String): Long = if (text.isEmpty()) 0L else PyValues.parseLong(text)
        val flags = LinkedHashMap<Long, Long>()
        for (row in rows[0]) flags[PyValues.parseLong(cell(row, "101"))] = intOr0(cell(row, "500"))
        val reborn = rows[1].map { row -> Triple(intOr0(cell(row, "102")), intOr0(cell(row, "103")), intOr0(cell(row, "104"))) }
            .sortedWith(compareBy<Triple<Long, Long, Long>> { it.first }.thenBy { it.second }.thenBy { it.third })
        if (flags.size != rows[0].size || reborn.map { it.first }.toSet().size != reborn.size) throw PyValues.ValueError("Duplicate native lineup configuration keys")
        return NativeLineupRules(flags, reborn)
    }

    /** `encode_secondary_team`: entries `[{"position", "hero"}]` and `max_open_positions`. */
    fun encodeSecondaryTeam(entries: List<Pair<Long, JArr>>, maxOpenPositions: Long): ByteArray {
        val w = WireWriter().u8(byte(entries.size.toLong(), "Entry count"))
        for ((position, hero) in entries) {
            w.u8(byte(position, "Position"))
            TypedValues.encodeFields(hero, w)
        }
        w.u8(byte(maxOpenPositions, "Max open positions"))
        return w.bytes()
    }
}

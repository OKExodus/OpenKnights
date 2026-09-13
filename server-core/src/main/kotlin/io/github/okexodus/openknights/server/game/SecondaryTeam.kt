package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * The owned-hero helpers and the alternate-team codec of `secondary_team.py` that fresh characters use: a hero is a
 * typed field list whose field 0 is its owned UID; the S3745 payload is `u8 n, n × (u8 position, fields), u8 max
 * open positions`. (The captured three-position import and replacement policies of derived characters are dev-only.)
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

package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.math.BigInteger

/**
 * Shared-world participants (`world_participants.py`): every local character, and later bot players, as one list
 * interface. Lists that show other players read participants through here. A participant is a read-only snapshot;
 * characters are read from their own save. No bot exists in release yet (the bot list is empty).
 */
object WorldParticipants {
    const val ROLE_ACCOUNT_ID = 0
    const val ROLE_USER_NAME = 2
    const val ROLE_LEVEL = 3
    const val ROLE_REPUTATION = 12
    const val ROLE_GENDER = 26
    const val ROLE_VIP = 27
    const val HERO_UID = 0
    const val HERO_TEMPLATE = 1
    const val HERO_LEVEL = 2
    const val HERO_REBORN_LEVEL = 19
    const val HERO_AWAKEN = 24

    class LineupEntry(val position: Long, val template: Long, val level: Long, val awaken: Long = 0, val rebornLevel: Long = 0, val uid: Long = 0)

    class Participant(
        /** The wire role id shown to other players (characters: role 0; bots: the bot range). */
        val participantId: Long,
        val kind: String,
        val nameRaw: ByteArray,
        val level: Long,
        val vip: Long = 0,
        val reputation: Long = 0,
        /** The display Power (characters: the universal Power); null when not computable (lists then show 0). */
        val power: BigInteger? = null,
        val leaderTemplate: Long = 0,
        val lineup: List<LineupEntry> = emptyList(),
        val characterId: String? = null,
        val created: String = "",
        val gender: Long = 0,
        val extra: JObj = JObj(),
    ) {
        val name: String get() = PyBytes.decodeReplace(nameRaw)
    }

    /** `{f["id"]: f["value"]}` of the role properties (a later duplicate id wins). */
    private fun roleMap(state: JObj): Map<Long, JObj> {
        val out = LinkedHashMap<Long, JObj>()
        for (f in state.arr("role_properties")) out[(f as JObj).long("id")] = f.obj("value")
        return out
    }

    private fun heroValues(fields: io.github.okexodus.openknights.exact.JArr): Map<Long, Long?> {
        val out = LinkedHashMap<Long, Long?>()
        for (f in fields) out[(f as JObj).long("id")] = f.obj("value").longOrNull("bits")
        return out
    }

    /** Formation slots with a hero, in slot order, joined with the owned hero fields. */
    fun lineupOf(state: JObj): List<LineupEntry> {
        val heroes = LinkedHashMap<Long?, Map<Long, Long?>>()
        for (fields in state.arr("heroes")) {
            val values = heroValues(fields as io.github.okexodus.openknights.exact.JArr)
            heroes[values[HERO_UID.toLong()]] = values
        }
        val entries = ArrayList<LineupEntry>()
        val formation = (state["formation"] as? io.github.okexodus.openknights.exact.JArr) ?: io.github.okexodus.openknights.exact.JArr()
        for (slot in formation.map { it as JObj }.sortedBy { it.long("slot_id") }) {
            val uid = slot.longOrNull("hero_uid")
            val values = heroes[uid]
            if (uid == null || uid == 0L || values == null) continue
            entries.add(LineupEntry(slot.long("slot_id"), values[HERO_TEMPLATE.toLong()] ?: 0, values[HERO_LEVEL.toLong()] ?: 0,
                values[HERO_AWAKEN.toLong()] ?: 0, values[HERO_REBORN_LEVEL.toLong()] ?: 0, values[HERO_UID.toLong()]!!))
        }
        return entries
    }

    /** One world-directory member and its current save read; [powerOf] is the list's Power source. */
    fun characterParticipant(member: JObj, current: StateStore.Current, powerOf: ((StateStore.Current) -> BigInteger?)? = null): Participant {
        val state = current.state
        val role = roleMap(state)
        val lineup = lineupOf(state)
        val captain = state.longOrNull("captain_slot")
        val leader = lineup.firstOrNull { it.position == captain }?.template ?: (lineup.firstOrNull()?.template ?: 0)
        val name = (role[ROLE_USER_NAME.toLong()]?.get("raw_hex") as? JStr)?.value ?: ""
        return Participant(role[ROLE_ACCOUNT_ID.toLong()]?.longOrNull("bits") ?: 0, "character", name.hexBytes(),
            role[ROLE_LEVEL.toLong()]?.longOrNull("bits") ?: 1, role[ROLE_VIP.toLong()]?.longOrNull("bits") ?: 0,
            role[ROLE_REPUTATION.toLong()]?.longOrNull("bits") ?: 0, powerOf?.invoke(current), leader, lineup,
            member.strOrNull("character_id"), member.strOrNull("created_at_utc") ?: "", role[ROLE_GENDER.toLong()]?.longOrNull("bits") ?: 0)
    }

    /** Every active world character (read from its own save) plus the bot list, in world creation order. */
    fun worldParticipants(world: WorldDirectory?, registry: AccountRegistry, powerOf: ((StateStore.Current) -> BigInteger?)?,
                          bots: List<Participant> = emptyList()): List<Participant> {
        val out = ArrayList<Participant>()
        if (world != null) {
            for (member in world.characters()) {
                val store = registry.resolveStateStore(member.str("character_id"))
                out.add(characterParticipant(member, store.read(), powerOf))
            }
        }
        if (bots.isNotEmpty()) throw NotPorted("prestige.with_progress (bot participants)")
        return out
    }
}

/** Python `bytes.decode("utf-8", "replace")`. */
object PyBytes {
    fun decodeReplace(raw: ByteArray): String = String(raw, Charsets.UTF_8)
}

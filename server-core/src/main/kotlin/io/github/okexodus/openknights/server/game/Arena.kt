package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger

/**
 * The arena outside combat, query side (`arena.py`): the panel (C417 → S448) and the ladder top list (C421 → S450). The
 * ladder is the shared world's participants, kept in the world document `arena_ladder`; the daily rank reward (C423)
 * with its 22:00 settlement belongs to the arena actions port. Challenges (C419) are combat.
 */
object Arena {
    const val C_ARENA_OPEN = 417
    const val C_ARENA_TOP = 421
    const val C_ARENA_REWARD = 423
    const val C_ARENA_BUY = 425
    const val S_ARENA_INFO = 448
    const val S_ARENA_TOP = 450
    /** Challenges shown (captured 10/10, server constant). */
    const val CHALLENGES = 10L
    /** "Add up Rewards at 22:00 every day" — device local time (operator policy). */
    const val SETTLEMENT_HOUR = 22
    const val MAX_OPPONENTS = 10
    const val TOP_ROWS = 6
    const val BELOW_ROWS = 4
    const val ARENA_PROFILE = "arena_state_v1"

    /** Epoch of the latest 22:00 (device local, the offset of `now`) at or before now (`last_settlement`). */
    fun lastSettlement(now: Long): Long {
        val moment = Shops.localDatetime(now)
        var settle = moment.withHour(SETTLEMENT_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (settle.isAfter(moment)) settle = settle.minusDays(1)
        return settle.toEpochSecond()
    }

    /** `_arena_seed`: the sort key (seed is None, seed or 0) of an unranked bot. */
    private fun arenaSeed(participant: Participant): Pair<Boolean, JValue> {
        val seed = participant.extra["arena_seed"]?.takeIf { it != JNull }
        return (seed == null) to (if (seed != null && Py.truthy(seed)) seed else JInt(0))
    }

    private val SEED_ORDER = Comparator<Participant> { a, b ->
        val x = arenaSeed(a)
        val y = arenaSeed(b)
        val c = x.first.compareTo(y.first)
        if (c != 0) c else PyDocs.compare(x.second, y.second)
    }

    /** A stored rank entry as a participant id (null when it can equal no participant id). */
    private fun rankId(value: JValue): Long? = (value as? JInt)?.value?.takeIf { it.bitLength() < 64 }?.toLong()

    /**
     * Ranks = the stored order, then any participant not yet ranked appended at the bottom in world order (a new
     * character joins last); unranked bots join before unranked characters, by the roster's optional `arena_seed`, then
     * roster order (`ladder_ranks`). Returns (ids in rank order, changed).
     */
    fun ladderRanks(ladderDocument: JObj?, participants: List<Participant>): Pair<List<Long>, Boolean> {
        val stored = when (val raw = if (Py.truthy(ladderDocument)) (ladderDocument!!["ranks"] ?: JArr()) else JArr()) {
            is JArr -> raw
            is JObj -> JArr(raw.keys.mapTo(ArrayList()) { JStr(it) })                       // list(dict): its keys
            is JStr -> JArr(raw.value.codePoints().toArray().mapTo(ArrayList()) { JStr(String(Character.toChars(it))) })
            else -> throw PyDocs.TypeError("'${if (raw == JNull) "NoneType" else "int"}' object is not iterable")
        }
        val present = participants.mapTo(HashSet()) { it.participantId }
        val kept = ArrayList<Long>()
        for (value in stored) rankId(value)?.takeIf { it in present }?.let { kept.add(it) }
        val ranked = HashSet(kept)
        val newcomers = participants.filter { it.participantId !in ranked }
        val bots = newcomers.filter { it.kind == "bot" }.sortedWith(SEED_ORDER)
        for (p in bots + newcomers.filter { it.kind != "bot" }) {
            if (p.participantId !in ranked) {
                kept.add(p.participantId)
                ranked.add(p.participantId)
            }
        }
        val same = kept.size == stored.size && kept.indices.all { stored[it] == JInt(kept[it]) }
        return kept to !same
    }

    /**
     * When the character joined the Arena ladder (`joined_at_of`): an in-game-created character at its creation, a
     * character derived from the preserved save before any settlement (0).
     */
    fun joinedAtOf(current: StateStore.Current?): Long {
        val profile = current?.characterProfile?.takeIf { Py.truthy(it) } ?: JObj()
        val document = profile["document"] ?: JObj()
        if (document !is JObj) throw PyDocs.TypeError("'${if (document == JNull) "NoneType" else "object"}' object has no attribute 'get'")
        val created = document["created_at_utc"]
        if (!Py.truthy(created)) return 0
        if (created !is JStr) throw PyDocs.TypeError("fromisoformat: argument must be str")
        return try {
            Summon.isoTimestamp(created.value)
        } catch (e: PyValues.ValueError) {
            0
        }
    }

    /**
     * The per-character arena document after settlement (`arena_view`): at every 22:00 the reward rank becomes the rank
     * held then and the claim reopens; a character that joined after the last settlement has reward rank 0. `joinedAt`
     * dates a character without a stored document (null keeps `now`).
     */
    fun arenaView(document: JValue?, rank: Long, now: Long, joinedAt: Long? = null): JObj {
        val doc = if (Py.truthy(document)) document!!.deepCopy().asObj else jobj("profile" to ARENA_PROFILE,
            "joined_at" to (joinedAt ?: now), "settled_at" to null, "reward_rank" to 0, "claimed" to 0, "history" to JArr())
        val settle = lastSettlement(now)
        if (PyDocs.compare(PyDocs.at(doc, "joined_at"), JInt(settle)) <= 0 && PyDocs.get(doc, "settled_at") != JInt(settle)) {
            doc["settled_at"] = JInt(settle)
            doc["reward_rank"] = JInt(rank)
            doc["claimed"] = JInt(0)
        }
        return doc
    }

    /** `_cstring`: a name with its NUL (a NUL inside is a ValueError). */
    fun cstring(nameRaw: ByteArray): ByteArray {
        if (nameRaw.contains(0.toByte())) throw PyValues.ValueError("Names must not contain NUL")
        return nameRaw + byteArrayOf(0)
    }

    /**
     * Up to ten rows (`opponents`): the top six, then the next four below the own rank (live shape at rank 7); a small
     * world lists everybody but the requester. `ownId` null = not on the ladder.
     */
    fun opponents(ranks: List<Long>, byId: Map<Long, Participant>, ownId: Long?): List<Pair<Long, Participant>> {
        val at = if (ownId == null) -1 else ranks.indexOf(ownId)
        val own = if (at >= 0) at else ranks.size
        var picked = (0 until minOf(TOP_ROWS, ranks.size)).filter { it != own }.toMutableList()
        picked.addAll((own + 1 until ranks.size).filter { it !in picked }.take(BELOW_ROWS))
        if (ranks.size <= MAX_OPPONENTS + 1) picked = (ranks.indices).filter { it != own }.toMutableList()
        return picked.sorted().take(MAX_OPPONENTS).map { i -> (i + 1).toLong() to byId.getValue(ranks[i]) }
    }

    /**
     * S448 `u32 rank, u32 10, u32 10, i8 0, u32 0, i8 claimed, u32 reward rank, u8 h, h × (name, i8 attacker, i8 result,
     * u32 rank, i8 trend), u8 n, n × (u32 id, name, u32 level, u32 position, u64 power, u32 leader)` (`info_payload`).
     */
    fun infoPayload(document: JObj, rank: Long, rows: List<Pair<Long, Participant>>): ByteArray {
        val all = PyDocs.at(document, "history") as JArr
        val history = all.subList(maxOf(0, all.size - 6), all.size)
        val w = WireWriter().number('I', rank).number('I', CHALLENGES).number('I', CHALLENGES).number('b', 0L).number('I', 0L)
            .number('b', PyDocs.at(document, "claimed")).number('I', PyDocs.at(document, "reward_rank"))
        w.raw(PyDocs.bytes(listOf(history.size.toLong())))
        for (h in history) {
            val entry = h.asObj
            w.raw(cstring(PyDocs.str(PyDocs.at(entry, "name_hex")).hexBytes()))
            w.number('b', PyDocs.at(entry, "attacker")).number('b', PyDocs.at(entry, "result")).number('I', PyDocs.at(entry, "rank"))
                .number('b', PyDocs.at(entry, "trend"))
        }
        w.raw(PyDocs.bytes(listOf(rows.size.toLong())))
        for ((position, p) in rows) {
            w.number('I', p.participantId).raw(cstring(p.nameRaw)).number('I', p.level).number('I', position)
                .number('Q', p.power ?: BigInteger.ZERO).number('I', p.leaderTemplate)
        }
        return w.bytes()
    }

    /** S450 `u8 n, n × (u32 role, name, u32 level, u64 power, u8 gender)` (rank = index + 1, the top 50; `top_payload`). */
    fun topPayload(ranks: List<Long>, byId: Map<Long, Participant>): ByteArray {
        val rows = ranks.take(50).map { byId.getValue(it) }
        val w = WireWriter().raw(PyDocs.bytes(listOf(rows.size.toLong())))
        for (p in rows) {
            w.number('I', p.participantId).raw(cstring(p.nameRaw)).number('I', p.level).number('Q', p.power ?: BigInteger.ZERO)
                .number('B', p.gender)
        }
        return w.bytes()
    }
}

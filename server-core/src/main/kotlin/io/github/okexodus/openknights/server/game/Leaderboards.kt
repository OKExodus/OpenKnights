package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Leaderboard activity derived from committed history, without changing saved gameplay state. */
object Leaderboards {
    val WEEKLY_TYPES = setOf(8L, 9L, 10L)
    val EXTENDED_TYPES = setOf(2L, 5L, 7L, 8L, 9L, 10L, 12L, 13L)

    /** Personal columns retain overall Power; Leader shows the formation captain's Power and hero name. */
    fun playerRows(type: Long, people: List<WorldParticipants.Participant>, states: Map<Long, StateStore.Current>,
                   weekly: Map<Long, Map<String, Long>>, leaderPower: (StateStore.Current) -> BigInteger?): List<WorldDirectory.Companion.RankRow> =
        people.mapNotNull { p ->
            val current = states[p.participantId] ?: return@mapNotNull null
            fun role(id: Long): Long = current.state.arr("role_properties").map { it.asObj }
                .lastOrNull { it.long("id") == id }?.obj("value")?.longOrNull("bits") ?: 0
            val power = if (type == 7L) leaderPower(current) else p.power
            val value = when (type) {
                2L -> role(13)
                5L -> role(18)
                7L -> p.leaderTemplate
                8L -> weekly[p.participantId]?.get("helper") ?: 0
                9L -> weekly[p.participantId]?.get("praise") ?: 0
                10L -> weekly[p.participantId]?.get("collect") ?: 0
                else -> return@mapNotNull null
            }
            val metric = if (type == 7L) power ?: return@mapNotNull null else BigInteger.valueOf(value)
            WorldDirectory.Companion.RankRow(p.participantId, p.nameRaw, p.level, p.reputation, p.created, power, metric, value)
        }

    /** Guild Points are zero until Guild War scoring exists; popularity is not a war score. */
    fun guildRows(type: Long, document: JObj, people: List<WorldParticipants.Participant>, inputs: DailyInputs): List<WorldDirectory.Companion.RankRow> {
        val powers = people.associate { it.participantId to (it.power ?: BigInteger.ZERO) }
        return document.obj("guilds").values.map { entry ->
            val guild = entry.asObj
            val power = guild.arr("members").fold(BigInteger.ZERO) { total, member -> total + (powers[member.asObj.long("role")] ?: BigInteger.ZERO) }
            val level = Guild.levelOf(guild, inputs)
            WorldDirectory.Companion.RankRow(guild.long("id"), guild.str("name_hex").hexBytes(), level, 0,
                guild.long("created_at").toString().padStart(20, '0'), power,
                BigInteger.valueOf(if (type == 13L) level else 0),
                valueB = if (type == 13L) guild.long("popularity") else 0,
                valueA = BigInteger.valueOf(if (type == 13L) guild.arr("members").size.toLong() else 0))
        }
    }

    fun captainPower(current: StateStore.Current, inputs: AcquisitionInputs, freshSystems: Map<Int, List<ByteArray>>?,
                     evolutionInputs: EvolutionInputs): BigInteger? {
        val lineup = WorldParticipants.lineupOf(current.state)
        val captain = lineup.firstOrNull { it.position == current.state.longOrNull("captain_slot") } ?: lineup.firstOrNull() ?: return null
        return BattleStats.slotStats(current.state, captain.position, inputs,
            BattleStats.statWorldOf(freshSystems, evolutionInputs, current)).int("score")
    }

    /** Read successful actions from the existing audit tables, including activity before this feature existed. */
    fun readWeeklyCounts(world: WorldDirectory?, stores: Map<Long, StateStore>, now: Long): Map<Long, Map<String, Long>> {
        val collections = ArrayList<JObj>()
        for ((role, store) in stores) store.connect(readOnly = true).use { db ->
            for (row in db.query("SELECT timestamp_utc, detail_json FROM state_history WHERE action=? ORDER BY revision", "castle_collect")) {
                collections.add(jobj("role_id" to role, "action" to "castle_collect", "timestamp_utc" to row.string("timestamp_utc"),
                    "detail" to Json.loads(row.string("detail_json"))))
            }
        }
        val social = world?.connect(readOnly = true)?.use { db ->
            db.query("SELECT timestamp_utc, action, detail_json FROM world_history WHERE action IN (?, ?) ORDER BY sequence",
                "friend_praise", "campaign_helper").map { row ->
                jobj("action" to row.string("action"), "timestamp_utc" to row.string("timestamp_utc"),
                    "detail" to Json.loads(row.string("detail_json")))
            }
        } ?: emptyList()
        return weeklyCounts(collections, social, now)
    }

    /** Monday at local midnight, following the device clock used by other weekly systems. */
    fun weekStart(now: Long): Long {
        val local = Shops.localDatetime(now)
        return local.minusDays(local.dayOfWeek.value - 1L).withHour(0).withMinute(0).withSecond(0).withNano(0).toEpochSecond()
    }

    private fun timestamp(text: String): Long? = try {
        OffsetDateTime.parse(text.replace(' ', 'T')).toEpochSecond()
    } catch (_: java.time.format.DateTimeParseException) {
        try { LocalDateTime.parse(text.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC) }
        catch (_: java.time.format.DateTimeParseException) { null }
    }

    /** Collect counts resource types; Helper and Praise credit the receiver, not the sender. */
    fun weeklyCounts(collectionEvents: List<JObj>, worldEvents: List<JObj>, now: Long): Map<Long, Map<String, Long>> {
        val start = weekStart(now)
        val counts = linkedMapOf<Long, MutableMap<String, Long>>()
        fun add(role: Long?, kind: String, amount: Long) {
            if (role == null || role <= 0 || amount <= 0) return
            val entry = counts.getOrPut(role) { linkedMapOf("collect" to 0L, "helper" to 0L, "praise" to 0L) }
            entry[kind] = entry.getValue(kind) + amount
        }
        fun inWeek(event: JObj): Boolean {
            val at = timestamp(event.strOrNull("timestamp_utc") ?: return false) ?: return false
            return at in start..now
        }
        for (event in collectionEvents) {
            if (!inWeek(event) || event.strOrNull("action") != "castle_collect") continue
            val detail = event["detail"] as? JObj ?: continue
            val types = detail["types"] as? JArr ?: continue
            add(event.longOrNull("role_id"), "collect", types.size.toLong())
        }
        for (event in worldEvents) {
            if (!inWeek(event)) continue
            val detail = event["detail"] as? JObj ?: continue
            when (event.strOrNull("action")) {
                "friend_praise" -> add(detail.longOrNull("target"), "praise", 1)
                "campaign_helper" -> {
                    val pair = detail["pair"] as? JArr ?: continue
                    if (pair.size == 2) add(PyDocs.long(pair[1]), "helper", 1)
                }
            }
        }
        return counts
    }
}

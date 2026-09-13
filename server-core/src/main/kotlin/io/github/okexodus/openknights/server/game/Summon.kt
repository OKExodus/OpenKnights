package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.SqlConnection
import io.github.okexodus.openknights.server.store.StateStore
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The login parts of `summon.py`: the free-draw timers S354 (`u32 a, u32 b` remaining seconds of the lot-2 / lot-3
 * free single), the summon document seeded at the first use, and the service clock (`now_epoch`: the device clock
 * with its high-water mark in release mode).
 */
object Summon {
    const val S_FREE_CD = 354
    const val DOCUMENT_PROFILE = "summon_state_v1"
    val LOT_ACHIEVEMENT = linkedMapOf(1L to 27L, 2L to 28L, 3L to 29L)

    /** The service clock: the device clock in release mode, else the host epoch. */
    fun nowEpoch(): Long = DeviceClock.active?.now() ?: Now.epoch()

    fun freeCdPayload(remainingA: Long, remainingB: Long): ByteArray =
        WireWriter().number('I', maxOf(0L, remainingA)).number('I', maxOf(0L, remainingB)).bytes()

    /**
     * Seed the summon state the first time (labeled policy): fresh characters start both free timers from their
     * creation (`created_at_utc` + 86,400 / 259,200 s); first-draw rows count as used when the lot's cumulative draw
     * counter (achievement kinds 27 / 28 / 29) is positive.
     */
    fun initialDocument(current: StateStore.Current, now: Long): JObj {
        val counters = LinkedHashMap<JValue, JValue>()
        for (e in current.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            counters[wire[0]] = wire[2]
        }
        val profile = current.characterProfile
        val freeNext = if (profile != null) {
            val stamp = PyDocs.get(profile.obj("document"), "created_at_utc")
            val created = if (Py.truthy(stamp)) isoTimestamp(PyDocs.str(stamp)) else now
            jobj("2" to created + 86400, "3" to created + 259200)
        } else jobj("2" to 0, "3" to 0)
        val firstUsed = JObj()
        for ((lot, kind) in LOT_ACHIEVEMENT) {
            val count = counters[JInt(kind)] ?: JInt(0)
            firstUsed[lot.toString()] = JBool(PyDocs.compare(count, JInt(0)) > 0)
        }
        return jobj("profile" to DOCUMENT_PROFILE, "free_next_epoch" to freeNext, "first_used" to firstUsed,
            "seeded_at_epoch" to now, "seed_rule" to "derived: free now; fresh: creation + CD; first rows by counters")
    }

    fun readSummonState(db: SqlConnection): JValue? {
        if (!db.tableExists("summon_state")) return null
        val row = db.queryOne("SELECT document_json FROM summon_state WHERE id=1") ?: return null
        return Json.loads(row.string("document_json"))
    }

    fun writeSummonState(db: SqlConnection, document: JValue) = Shops.writeState(db, "summon_state", document)

    fun freeCdRemaining(document: JObj, now: Long): List<Long> {
        val next = PyDocs.at(document, "free_next_epoch") as JObj
        return listOf("2", "3").map { maxOf(0L, PyDocs.long(PyDocs.at(next, it)) - now) }
    }

    /**
     * `int(datetime.fromisoformat(stamp).timestamp())`: an aware stamp maps through its own offset, a naive one
     * through the host's local offset; the fractional seconds are truncated toward zero.
     */
    fun isoTimestamp(stamp: String): Long {
        val parsed = parseIso(stamp)
        val epoch = parsed.first
        val nanos = parsed.second
        return if (epoch < 0 && nanos > 0) epoch + 1 else epoch
    }

    private val NAIVE = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun parseIso(stamp: String): Pair<Long, Int> {
        val text = stamp.replace(' ', 'T')
        try {
            val aware = OffsetDateTime.parse(text)
            return aware.toEpochSecond() to aware.nano
        } catch (_: DateTimeParseException) {
        }
        try {
            val naive = LocalDateTime.parse(text, NAIVE)
            val guess = naive.toEpochSecond(ZoneOffset.UTC)
            val offset = Now.offset(guess)
            return naive.toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) to naive.nano
        } catch (_: DateTimeParseException) {
        }
        try {
            val date = java.time.LocalDate.parse(stamp)
            val guess = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            return date.atStartOfDay().toEpochSecond(ZoneOffset.ofTotalSeconds(Now.offset(guess))) to 0
        } catch (_: DateTimeParseException) {
        }
        throw PyValues.ValueError("Invalid isoformat string: '$stamp'")
    }
}

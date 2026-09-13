package io.github.okexodus.openknights.exact

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The reference's timestamp texts. */
object PyTime {
    private val MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private val SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** `datetime.now(timezone.utc).isoformat(timespec="milliseconds")`, e.g. `2026-09-13T17:09:39.123+00:00`. */
    fun isoMillisUtc(instant: Instant): String = MILLIS.format(instant.atOffset(ZoneOffset.UTC))
    fun isoMillisUtc(epochMillis: Long): String = isoMillisUtc(Instant.ofEpochMilli(epochMillis))
    fun nowIsoMillis(): String = isoMillisUtc(fromTimestamp(Now.seconds()))

    /**
     * `datetime.fromtimestamp(t)` as an instant: CPython splits the double into whole seconds and a fraction, rounds
     * the fraction × 10^6 half-to-even to microseconds and carries a full second (`_PyTime_DoubleToDenominator`).
     */
    fun fromTimestamp(t: Double): Instant {
        require(t.isFinite()) { "timestamp out of range" }
        var whole = if (t < 0) Math.ceil(t) else Math.floor(t)
        var micros = Math.rint((t - whole) * 1e6)
        if (micros >= 1e6) { micros -= 1e6; whole += 1.0 } else if (micros < 0) { micros += 1e6; whole -= 1.0 }
        return Instant.ofEpochSecond(whole.toLong(), micros.toLong() * 1000)
    }

    /** `isoformat(timespec="seconds")` in UTC, e.g. `2026-09-13T17:09:39+00:00`. */
    fun isoSecondsUtc(epochSeconds: Long): String = SECONDS.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))

    /** `datetime.now(timezone.utc).isoformat(timespec="seconds")`. */
    fun nowIsoSeconds(): String = SECONDS.format(fromTimestamp(Now.seconds()).atOffset(ZoneOffset.UTC))

    /** `strftime("%Y%m%dT%H%M%SZ")` in UTC. */
    fun stampUtc(instant: Instant): String = STAMP.format(instant.atOffset(ZoneOffset.UTC))
    fun nowStamp(): String = stampUtc(fromTimestamp(Now.seconds()))

    /** The local day `%Y-%m-%d` of an epoch at a fixed GMT offset. */
    fun localDay(epochSeconds: Long, offsetSeconds: Int): String =
        DAY.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.ofTotalSeconds(offsetSeconds)))

    /** `weekday()` (Monday = 0) at a fixed GMT offset. */
    fun weekday(epochSeconds: Long, offsetSeconds: Int): Int =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.ofTotalSeconds(offsetSeconds)).dayOfWeek.value - 1
}

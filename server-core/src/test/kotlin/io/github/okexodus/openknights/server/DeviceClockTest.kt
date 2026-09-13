package io.github.okexodus.openknights.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The next local midnight takes the GMT offset in force at that midnight (made-up zones switching at 02:00). */
class DeviceClockTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = OffsetDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC).toEpochSecond()

    private fun clock(switch: Long, before: Int, after: Int) = DeviceClock(null, { switch }, { e -> if (e < switch) before else after })

    @Test
    fun `a fall-back day ends at the new offset's midnight`() {
        val clock = clock(utc(2026, 11, 1, 6), -4 * 3600, -5 * 3600)
        assertEquals(utc(2026, 11, 2, 5), clock.nextDayStart(utc(2026, 11, 1, 4, 30)))
    }

    @Test
    fun `a spring-forward day ends at the new offset's midnight`() {
        val clock = clock(utc(2026, 3, 8, 7), -5 * 3600, -4 * 3600)
        assertEquals(utc(2026, 3, 9, 4), clock.nextDayStart(utc(2026, 3, 8, 5, 30)))
    }

    @Test
    fun `a fixed offset is unchanged`() {
        val clock = DeviceClock(null, { 0 }, { -18000 })
        assertEquals(utc(2026, 9, 14, 5), clock.nextDayStart(utc(2026, 9, 13, 20)))
    }
}

package io.github.okexodus.openknights.server.game

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/** The Arena's join time reads a creation stamp without a zone as UTC (`arena.joined_at_of`). */
class G7ArenaJoinedTest {
    @Test
    fun zoneLessStampIsUtc() {
        assertEquals(1_600_000_000L, Summon.isoTimestamp("2020-09-13T12:26:40", naiveAsUtc = true))
        assertEquals(1_600_000_000L, Summon.isoTimestamp("2020-09-13T12:26:40+00:00", naiveAsUtc = true))
        assertEquals(1_600_000_000L, Summon.isoTimestamp("2020-09-13T14:26:40+02:00", naiveAsUtc = true))
        assertEquals(1_599_955_200L, Summon.isoTimestamp("2020-09-13", naiveAsUtc = true))
    }
}

package io.github.okexodus.openknights.server.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class ActivityProgressCompatibilityTest {
    @Test
    fun `activity patterns preserve Unicode digits and newline rules without unsupported Android flags`() {
        val pattern = ActivityProgress.LADDERS.getValue("diamond_spend").first
        assertEquals(0, pattern.flags() and Pattern.UNICODE_CHARACTER_CLASS)
        assertTrue(pattern.matcher("Used 12/30 Diamonds").lookingAt())
        assertTrue(pattern.matcher("Used \u0661\u0662/\u0663\u0660 Diamonds").lookingAt())
        assertTrue(pattern.matcher("Used 12/30 Diamonds\n").lookingAt())
        assertFalse(pattern.matcher("Used 12/30 Diamonds\r").lookingAt())
        assertFalse(pattern.matcher("Used 12/30 Diamonds extra").lookingAt())
    }
}

package io.github.okexodus.openknights.server.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A target already at its level cap is refused with its own code: 1008 for heroes, 6010 for gear and jewelry. */
class G3ItemFortifyCapTest {
    private val requirement: (Long) -> Long = { 100L }

    @Test
    fun `a hero at the cap keeps the hero code`() {
        val e = assertThrows<ItemFortify.ItemFortifyRejected> { ItemFortify.planConsumption(emptyList(), 30, 0, 30, requirement) }
        assertEquals(ItemFortify.ERROR_MAX_LEVEL, e.code)
    }

    @Test
    fun `a gear or jewelry record at the cap answers the gear code`() {
        val e = assertThrows<ItemFortify.ItemFortifyRejected> {
            ItemFortify.planConsumption(emptyList(), 30, 0, 30, requirement, maxLevelCode = ItemFortify.ERROR_GEAR_MAX_LEVEL)
        }
        assertEquals(ItemFortify.ERROR_GEAR_MAX_LEVEL, e.code)
    }
}

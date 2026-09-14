package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** CI checks of the shops slice with made-up values (the local vectors test replays the reference's own outputs). */
class G4ShopsMadeUpTest {
    private fun hx(text: String) = text.toByteArray(Charsets.UTF_8).toHexString()

    private fun row(text: String, flag: Int, button: String? = null) = jobj("cstring_hex" to hx(text),
        "pairs" to jobj("count" to 1, "entries" to listOf(jobj("wire_values" to jarr(11, 3)))), "wire_u8_flag" to flag,
        "conditional_cstring_hex" to (button?.let { hx(it) } ?: JNull))

    private fun texts(activity: JObj) = activity.obj("rows").arr("entries").map {
        Triple(String(it.asObj.str("cstring_hex").hexBytes()), it.asObj.long("wire_u8_flag"),
            (it.asObj["conditional_cstring_hex"] as? JStr)?.let { b -> String(b.value.hexBytes()) })
    }

    @Test
    fun `a Diamond spend advances the ladder and a claim moves to the next tier`() {
        val activity = jobj("wire_u32_1" to 4242, "cstring_hex" to listOf("61", "30", "30", "72"), "wire_u32_after_strings" to 1000,
            "rows" to jobj("count" to 3, "entries" to listOf(row("Use 10Diamonds", 1, "Claimed"), row("Used 7/20 Diamonds", 1, "Claim Reward"),
                row("Use 50 Diamonds", 0))))
        val state = jobj("subsystems" to jobj("game_activities" to jobj("first_list" to jobj("count" to 1, "entries" to listOf(activity)))))
        val frames = ActivityProgress.advance(state, "diamond_spend", 15, 999)
        assertEquals(1, frames.size)
        assertEquals(1188, frames[0].first)
        assertEquals("92100000035573652031304469616d6f6e647300010b0000000300000001436c61696d656400557365642032322f3230204469616d6f6e647300" +
            "010b0000000300000002436c61696d2052657761726400557365203530204469616d6f6e647300010b0000000300000000", frames[0].second.toHexString())
        val live = state.obj("subsystems").obj("game_activities").obj("first_list").arr("entries")[0].asObj
        val claimed = ActivityProgress.claimRow(live)!!
        assertEquals(1, claimed.first)
        assertEquals(jarr(jarr(11, 3)), claimed.second)
        assertEquals(listOf(Triple("Use 10Diamonds", 1L, "Claimed"), Triple("Use 20 Diamonds", 1L, "Claimed"),
            Triple("Used 22/50 Diamonds", 1L, "Claim Reward")), texts(live))
        // the activity's end has passed: nothing advances
        assertEquals(0, ActivityProgress.advance(state, "diamond_spend", 15, 1000).size)
        assertNull(ActivityProgress.claimRow(live))
    }
}

package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Gear / jewelry evolve codecs and frames on made-up values. */
class G3EvolveMadeUpTest {
    @Test
    fun `request, record and result codecs`() {
        assertEquals(1_234_567L, EquipEvolve.decodeRequest(byteArrayOf(0x87.toByte(), 0xd6.toByte(), 0x12, 0)).long("target_uid"))
        assertEquals("Evolve request must be exactly one u32 uid",
            assertThrows<EquipEvolve.EquipEvolveRejected> { EquipEvolve.decodeRequest(ByteArray(5)) }.message)
        assertEquals("0500000006000000070000000800000009010b000000", EquipEvolve.recordPayload(listOf(5, 6, 7, 8, 9, 1, 11)).toHexString())
        assertEquals("0700000064000000", EquipEvolve.resultPayload(7, 100).toHexString())
        assertEquals(listOf(listOf(5L, 6L, 7L, 8L, 9L, 1L, 11L)),
            EquipEvolve.decodeJewelList("01000000".hexToByteArray() + EquipEvolve.recordPayload(listOf(5, 6, 7, 8, 9, 1, 11))))
    }

    @Test
    fun `only the grade byte of a jewel block changes`() {
        val raw = ByteArray(40) { it.toByte() }
        val out = EquipEvolve.jewelBlockWithGrade(raw, 200)
        assertEquals(200, out[16].toInt() and 0xFF)
        assertEquals(raw.toList().filterIndexed { i, _ -> i != 16 }, out.toList().filterIndexed { i, _ -> i != 16 })
        assertThrows<PyValues.ValueError> { EquipEvolve.jewelBlockWithGrade(ByteArray(39), 1) }
    }

    @Test
    fun `gear frames follow the observed order`() {
        val plan = io.github.okexodus.openknights.exact.jobj("kind" to "gear", "target_uid" to 7, "value_increase" to 25,
            "after_record" to listOf(7L, 6L, 30L, 0L, 3L, 0L, 0L),
            "item_changes" to listOf(io.github.okexodus.openknights.exact.jobj("packet" to listOf(66, "0109000000"))))
        assertEquals(listOf(66, 106, 2208, 128), EquipEvolve.evolvePackets(plan, TransactionPackets.goldPropertyPayload(10)).map { it.first })
        assertThrows<PyValues.ValueError> { EquipEvolve.evolvePackets(plan, null) }
    }
}

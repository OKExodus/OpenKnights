package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.toHexString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class TransactionPacketsTest {
    @Test
    fun `payloads match the reference encoders`() {
        assertEquals("01060887d6120000000000", TransactionPackets.goldPropertyPayload(1_234_567).toHexString())
        assertEquals("01e110000007000000", TransactionPackets.itemCountPayload(4321, 7).toHexString())
        assertEquals("01e1100000", TransactionPackets.itemRemovePayload(4321).toHexString())
        val reward = TransactionPackets.goldRewardPayload(99)
        assertEquals(98, reward.size)
        assertEquals("0e000000000000000000000000000000" + "6300000000000000" + "00".repeat(74), reward.toHexString())
        val packets = TransactionPackets.goldCardUpdatePackets(55, 0, BigInteger.TEN, BigInteger.valueOf(5))
        assertEquals(listOf(128, 66, 70), packets.map { it.first })
        assertEquals("0106080a00000000000000", packets[0].second.toHexString())
        assertEquals("0137000000", packets[1].second.toHexString())
    }

    @Test
    fun `out-of-range values are refused like the reference`() {
        assertEquals("Gold balance must fit uint64",
            assertThrows<PyValues.ValueError> { TransactionPackets.goldPropertyPayload(BigInteger.ONE.shiftLeft(64)) }.message)
        assertEquals("Item UID must fit uint32", assertThrows<PyValues.ValueError> { TransactionPackets.itemRemovePayload(-1) }.message)
        assertEquals("Item count must fit uint32", assertThrows<PyValues.ValueError> { TransactionPackets.itemCountPayload(1, 1L shl 32) }.message)
    }
}

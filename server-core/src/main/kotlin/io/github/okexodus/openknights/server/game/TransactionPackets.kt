package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.protocol.WireWriter
import java.math.BigInteger

/**
 * Payload encoders shared by the transactions (`transaction_packets.py`): the Gold-only reward, the Gold property
 * update, item count / removal and the error reply. Encoding only; the store validates and commits first.
 */
object TransactionPackets {
    const val INVALID_DATA = 102

    private fun unsigned(value: BigInteger, width: Int, label: String): BigInteger {
        if (value.signum() < 0 || value.bitLength() > width) throw PyValues.ValueError("$label must fit uint$width")
        return value
    }

    private fun unsigned(value: Long, width: Int, label: String): Long = unsigned(BigInteger.valueOf(value), width, label).toLong()

    /** Reward version 14 with Gold only (98 bytes): version at 0, the u64 Gold at byte 16, everything else zero. */
    fun goldRewardPayload(amount: BigInteger): ByteArray {
        val gold = unsigned(amount, 64, "Gold reward")
        val payload = ByteArray(98)
        WireWriter().number('I', 14).bytes().copyInto(payload, 0)
        WireWriter().number('Q', gold).bytes().copyInto(payload, 16)
        return payload
    }

    fun goldRewardPayload(amount: Long): ByteArray = goldRewardPayload(BigInteger.valueOf(amount))

    /** Opcode 128: one dynamic field, id 6, the uint64-width tag 8. */
    fun goldPropertyPayload(balance: BigInteger): ByteArray =
        WireWriter().number('B', 1).number('B', 6).number('B', 8).number('Q', unsigned(balance, 64, "Gold balance")).bytes()

    fun goldPropertyPayload(balance: Long): ByteArray = goldPropertyPayload(BigInteger.valueOf(balance))

    /** Opcode 68: one existing item UID and its absolute new count. */
    fun itemCountPayload(itemUid: Long, newCount: Long): ByteArray =
        WireWriter().number('B', 1).number('I', unsigned(itemUid, 32, "Item UID")).number('I', unsigned(newCount, 32, "Item count")).bytes()

    /** Opcode 66: a byte count followed by the item UID to remove. */
    fun itemRemovePayload(itemUid: Long): ByteArray = WireWriter().number('B', 1).number('I', unsigned(itemUid, 32, "Item UID")).bytes()

    /** Opcode 6: an int32 code; a nonzero code ends the client's waiting layer and shows text 8000000 + code. */
    fun errorPayload(code: Int): ByteArray = WireWriter().i32(code).bytes()

    /** The Gold Card use: Gold, the item count (or removal), then the reward presentation. */
    fun goldCardUpdatePackets(itemUid: Long, newCount: Long, newGold: BigInteger, goldGranted: BigInteger): List<Frame> {
        unsigned(newCount, 32, "Item count")
        val inventory = if (newCount != 0L) 68 to itemCountPayload(itemUid, newCount) else 66 to itemRemovePayload(itemUid)
        return listOf(128 to goldPropertyPayload(newGold), inventory, 70 to goldRewardPayload(goldGranted))
    }
}

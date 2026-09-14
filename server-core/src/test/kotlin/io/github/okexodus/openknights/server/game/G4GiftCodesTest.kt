package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Gift-code tables hold salted PBKDF2-SHA256 digests only (made-up code and salt; expected digests from the reference). */
class G4GiftCodesTest {
    private val salt = "00112233445566778899aabbccddeeff"

    @Test
    fun `requests are normalised and derived like the reference`() {
        assertEquals("example-code-one", GiftCodes.decodeRequest("  Example-CODE-One ".toByteArray() + byteArrayOf(0)))
        assertEquals("d6db3fdd5dc5be1b34429da3c1a06b5286946a82131b1d82b103b652b419388e",
            GiftCodes.derive("example-code-one", salt.hexToByteArray(), 1000))
        assertEquals("e12d6ee2a7f80bc47b634673234fab4f640358f056f5bf8d0e89a405c1afff86", GiftCodes.derive("", salt.hexToByteArray(), 1000))
        assertThrows<Acquisition.Rejected> { GiftCodes.decodeRequest("no-terminator".toByteArray()) }
        assertThrows<Acquisition.Rejected> { GiftCodes.decodeRequest(byteArrayOf(65, 0, 66, 0)) }
    }

    @Test
    fun `a table is looked up by digest and a malformed one is refused`() {
        val digest = "d6db3fdd5dc5be1b34429da3c1a06b5286946a82131b1d82b103b652b419388e"
        val document = jobj("profile" to GiftCodes.TABLE_PROFILE, "schema_version" to 1,
            "kdf" to jobj("algorithm" to GiftCodes.ALGORITHM, "iterations" to 1000, "salt" to salt),
            "codes" to jarr(jobj("hash" to digest, "reward" to jobj("diamond" to 5))))
        val table = GiftCodes.loadTable(document, "made-up")
        val (found, reward) = GiftCodes.lookup("example-code-one", listOf(table))
        assertEquals(digest, found)
        assertEquals(jobj("diamond" to 5), reward)
        assertEquals(null to null, GiftCodes.lookup("another-code", listOf(table)))
        document.arr("codes")[0].let { (it as io.github.okexodus.openknights.exact.JObj)["hash"] = JStr("not-a-digest") }
        assertThrows<PyValues.ValueError> { GiftCodes.loadTable(document, "broken") }
    }
}

package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Gift / redeem codes (`gift_codes.py`, C1537 → S1664): each code once per character; its amounts drawn by a seeded
 * RNG recorded in the history; a full bag (or, for a hero code, a full hero list) refuses the whole code.
 *
 * Codes are secrets: a code table (`gift-codes.json` of the release data) holds a PBKDF2-SHA256 salt, the iteration
 * count and, per code, only the hex digest of the normalised code (strip + casefold, UTF-8) with its reward. A request
 * is normalised, derived once per table and looked up by digest; `gift_codes.redeemed` is keyed by the digest (a save
 * keyed by the code text is migrated at the next redeem), and nothing written by the server holds a code.
 */
object GiftCodes {
    const val C_GIFT_CODE = 1537
    const val S_GIFT_CODE = 1664
    const val OK = 1
    const val ERROR = 2
    const val USED = 3
    const val PROFILE = "gift_codes_v1"
    const val TABLE_PROFILE = "openknights_gift_codes_v1"
    const val ALGORITHM = "pbkdf2-sha256"
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val REWARD_FIELDS = listOf("diamond" to Acquisition.DIAMOND, "gold" to Acquisition.GOLD,
        "stamina" to Acquisition.STAMINA, "energy" to Acquisition.ENERGY)

    /** A request that changes nothing: answered with these frames (unknown or already redeemed code). */
    class Unchanged(val packets: List<Frame>, reason: String) : Exception(reason)

    /** A loaded code table: its label, salt, iteration count and {digest: reward}. */
    class Table(val label: String, val salt: ByteArray, val iterations: Int, val byHash: Map<String, JObj>)

    /** `decode_request`: one NUL-terminated code → the normalised code (strip + casefold). */
    fun decodeRequest(payload: ByteArray): String {
        if (payload.isEmpty() || payload.last() != 0.toByte() || payload.dropLast(1).any { it == 0.toByte() }) {
            throw Acquisition.Rejected("C1537 carries one NUL-terminated code")
        }
        return PyText.casefold(PyText.strip(io.github.okexodus.openknights.exact.Utf8Lenient.decodeReplace(payload.copyOfRange(0, payload.size - 1))))
    }

    /** S1664 `u8 code` (+ the Reward on success). */
    fun reply(code: Int, reward: JObj? = null): List<Frame> =
        listOf(S_GIFT_CODE to (byteArrayOf(code.toByte()) + (reward?.let { BattleReport.encodeReward(it) } ?: ByteArray(0))))

    /** `derive(normalised, salt, iterations)`: the table digest (hex) of a normalised code. */
    fun derive(normalised: String, salt: ByteArray, iterations: Int): String =
        (if (normalised.isEmpty()) pbkdf2Empty(salt, iterations) else AccountRegistry.pbkdf2(normalised, salt, iterations)).toHexString()

    /** PBKDF2-HMAC-SHA256 of the empty password (the JCE refuses an empty key; the reference derives it). */
    private fun pbkdf2Empty(salt: ByteArray, iterations: Int): ByteArray {
        fun hmac(message: ByteArray): ByteArray {
            val inner = MessageDigest.getInstance("SHA-256")
            inner.update(ByteArray(64) { 0x36 })
            inner.update(message)
            val outer = MessageDigest.getInstance("SHA-256")
            outer.update(ByteArray(64) { 0x5c })
            outer.update(inner.digest())
            return outer.digest()
        }
        var u = hmac(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = hmac(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    /** `load_table(document, label)`: a well-formed table, else refused. */
    fun loadTable(document: JObj, label: String): Table {
        val kdf = document["kdf"] as? JObj ?: JObj()
        val iterations = (kdf["iterations"] as? JInt)?.value
        if (document["profile"] != JStr(TABLE_PROFILE) || document["schema_version"] != JInt(1) || kdf["algorithm"] != JStr(ALGORITHM) ||
            iterations == null || iterations < BigInteger.ONE) {
            throw PyValues.ValueError("Unsupported gift code table ($label)")
        }
        val byHash = LinkedHashMap<String, JObj>()
        for (e in (document["codes"] as? JArr) ?: JArr()) {
            val digest = (e.asObj["hash"] as? JStr)?.value
            if (digest == null || !DIGEST.matches(digest) || digest in byHash) throw PyValues.ValueError("Malformed gift code table entry ($label)")
            byHash[digest] = e.asObj.obj("reward")
        }
        return Table(label, (kdf["salt"] as JStr).value.hexBytes(), iterations.toInt(), byHash)
    }

    /** `lookup(normalised, tables)`: (digest, reward) or (null, null) — one derivation per table, in table order. */
    fun lookup(normalised: String, tables: List<Table>): Pair<String?, JObj?> {
        for (table in tables) {
            val digest = derive(normalised, table.salt, table.iterations)
            table.byHash[digest]?.let { return digest to it }
        }
        return null to null
    }

    /** `_migrated`: `redeemed` keyed by digests only (a code-text key becomes its digest; the text is never kept). */
    private fun migrated(redeemed: JObj, tables: List<Table>): JObj {
        val out = JObj()
        for ((key, value) in redeemed) {
            var k = key
            if (!DIGEST.matches(key) && tables.isNotEmpty()) {
                val normalised = PyText.casefold(PyText.strip(key))
                k = lookup(normalised, tables).first ?: derive(normalised, tables[0].salt, tables[0].iterations)
            }
            if (!out.containsKey(k)) out[k] = value
        }
        return out
    }

    /** `plan_redeem(payload, owned, current, inputs, seed, now, tables)`. */
    fun planRedeem(payload: ByteArray, owned: Owned, current: StateStore.Current, inputs: AcquisitionInputs, seed: BigInteger, now: Long,
                   tables: List<Table>): Plan {
        val code = decodeRequest(payload)
        val (digest, definition) = lookup(code, tables)
        if (definition == null) throw Unchanged(reply(ERROR), "unknown code")
        val stored = PyDocs.get(current, "gift_codes")
        val document = if (Py.truthy(stored)) PyDocs.shallow(stored as JObj) else jobj("profile" to PROFILE, "redeemed" to JObj())
        document["redeemed"] = migrated(PyDocs.shallow((document["redeemed"] as? JObj) ?: JObj()), tables)
        if (document.obj("redeemed").containsKey(digest)) throw Unchanged(reply(USED), "already redeemed")
        val rng = PyRandom.seeded(seed)
        val reward = BattleReport.emptyReward()
        val frames = ArrayList<Frame>()
        val grants = JArr()
        for ((name, field) in REWARD_FIELDS) {
            val value = definition[name]
            if (Py.truthy(value)) {
                frames.add(owned.roleAdd(field, PyDocs.int(value)))
                reward[name] = value!!
                grants.add(jarr(name, value))
            }
        }
        val items = (definition["items"] as? JArr) ?: JArr()
        for (t in items) {
            val template = PyDocs.long(t)
            if (inputs.item(template) == null) continue
            val amount = rng.randint(definition.long("min"), definition.long("max"))
            frames.add(owned.grantItem(template, amount))
            reward.arr("items").add(jarr(template, amount))
            grants.add(jarr(template, amount))
        }
        // Hero codes: whole base heroes, granted as a Mail Reward's heroes are; a full hero list refuses the whole code.
        for (t in (definition["heroes"] as? JArr) ?: JArr()) {
            val template = PyDocs.long(t)
            if (!inputs.heroExists(template)) continue
            val groups = owned.grantHero(template).second
            for (key in listOf("add", "book", "god", "activity")) frames.addAll(groups.getValue(key))
            reward.arr("heroes").add(jarr(template))
            grants.add(jarr("hero", template))
        }
        document.obj("redeemed")[digest!!] = jobj("at" to now, "items" to grants.size)
        return Plan(jobj("code_hash" to digest, "grants" to grants, "seed" to seed, "gift_codes_after" to document,
            "evidence_class" to "native_use_policy_local_code"), frames + reply(OK, reward))
    }
}

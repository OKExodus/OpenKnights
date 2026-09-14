package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Utf8Lenient
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.PlayerSections
import java.math.BigInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Progress ladders of the served event activities (S1188, `activity_progress.py`). The live server formats event
 * progress into the activity row titles (row = title, reward pairs, u8 flag, [button text]) and re-serves one activity
 * per change with S1188 (`u32 activity id`, the row list).
 *
 * Observed live ("Diamond spending"): claimed tiers "Use 60Diamonds", flag 1, "Claimed"; the first unclaimed tier
 * "Used X/T Diamonds", flag 1 while X < T and 2 once X >= T, button "Claim Reward"; later tiers "Use T Diamonds", flag 0,
 * no button. The recharge ladders carry the same layout; the per-transaction ladder is a labeled candidate.
 */
object ActivityProgress {
    const val S_ACTIVITY_UPDATE = 1188
    val CLAIM: String = "Claim Reward".toByteArray(Charsets.UTF_8).toHexString()

    /** The reference's `re` rules: `\d` is any Unicode decimal digit, `$` also matches before a final "\n". */
    private fun re(pattern: String): Pattern = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNIX_LINES)

    val LADDERS: Map<String, Pair<Pattern, (BigInteger, BigInteger) -> String>> = mapOf(
        "diamond_spend" to (re("^Used (\\d+)/(\\d+) Diamonds$") to { x, t -> "Used $x/$t Diamonds" }),
        "diamond_recharge" to (re("^Refilled (\\d+)/(\\d+) Diamonds$") to { x, t -> "Refilled $x/$t Diamonds" }))
    val TRANSACTION: Pattern = re("^Each Transaction: (\\d+)\\.(\\d\\d) dollars \\((\\d+)/(\\d+)\\)$")
    val U32: BigInteger = BigInteger.ONE.shiftLeft(32) - BigInteger.ONE
    val VIP_LEVEL_ROW: Pattern = re("^VIP Level:(\\d+)/(\\d+)$")
    val PROGRESS: Pattern = re("(\\d+)/(\\d+)")
    val NUMBER: Pattern = re("\\d+")
    val CLAIMED: String = "Claimed".toByteArray(Charsets.UTF_8).toHexString()

    /** `re.match`: the pattern anchored at the start of the text (null when it does not match). */
    private fun match(pattern: Pattern, text: String): Matcher? = pattern.matcher(text).takeIf { it.lookingAt() }

    /** `re.search`: the first match anywhere (null when none). */
    private fun search(pattern: Pattern, text: String): Matcher? = pattern.matcher(text).takeIf { it.find() }

    private fun int(text: String): BigInteger = PyValues.parseInt(text)

    private fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).toHexString()

    private fun text(row: JObj): String = Utf8Lenient.decodeReplace(PyDocs.str(PyDocs.at(row, "cstring_hex")).hexBytes())

    /** An activity takes progress while the served time is before its end (the u32 after its strings). */
    private fun active(activity: JObj, serverTime: Long?): Boolean =
        serverTime != null && PyDocs.compare(JInt(serverTime), PyDocs.at(activity, "wire_u32_after_strings")) < 0

    private fun activities(state: JObj): JArr = state.obj("subsystems").obj("game_activities").obj("first_list").arr("entries")

    private fun update(activity: JObj): Frame =
        S_ACTIVITY_UPDATE to PlayerSections.encodeActivityUpdate(PyDocs.long(PyDocs.at(activity, "wire_u32_1")), activity.obj("rows"))

    /** Add `amount` to every active ladder of `kind`; the S1188 frames of the changed activities. */
    fun advance(state: JObj, kind: String, amount: Long, serverTime: Long?): List<Frame> {
        val (pattern, template) = LADDERS[kind] ?: throw PyDocs.KeyError("'$kind'")
        val frames = ArrayList<Frame>()
        for (a in activities(state)) {
            val activity = a.asObj
            if (!active(activity, serverTime)) continue
            var changed = false
            for (r in activity.obj("rows").arr("entries")) {
                val row = r.asObj
                val m = match(pattern, text(row)) ?: continue
                val total = (int(m.group(1)) + BigInteger.valueOf(amount)).min(U32)
                val threshold = int(m.group(2))
                row["cstring_hex"] = JStr(hex(template(total, threshold)))
                row["wire_u8_flag"] = JInt(if (total >= threshold) 2 else 1)
                row["conditional_cstring_hex"] = if (Py.truthy(row["conditional_cstring_hex"])) row["conditional_cstring_hex"]!! else JStr(CLAIM)
                changed = true
            }
            if (changed) frames.add(update(activity))
        }
        return frames
    }

    /**
     * Per-transaction recharge ladder (candidate): a pack whose price equals the row's makes the row claimable (flag 2,
     * "Claim Reward") while fewer than its limit have been claimed.
     */
    fun countTransaction(state: JObj, priceCents: Long, serverTime: Long?): List<Frame> {
        val frames = ArrayList<Frame>()
        for (a in activities(state)) {
            val activity = a.asObj
            if (!active(activity, serverTime)) continue
            var changed = false
            for (r in activity.obj("rows").arr("entries")) {
                val row = r.asObj
                val m = match(TRANSACTION, text(row)) ?: continue
                if (int(m.group(1)) * BigInteger.valueOf(100) + int(m.group(2)) != BigInteger.valueOf(priceCents)) continue
                if (int(m.group(3)) >= int(m.group(4)) || PyDocs.at(row, "wire_u8_flag") == JInt(2)) continue
                row["wire_u8_flag"] = JInt(2)
                row["conditional_cstring_hex"] = JStr(CLAIM)
                changed = true
            }
            if (changed) frames.add(update(activity))
        }
        return frames
    }

    /** VIP Rewards ladder ("VIP Level:5/1"): the counter is the VIP level (capture observed). */
    fun setVipLevel(state: JObj, level: BigInteger, serverTime: Long?): List<Frame> {
        val frames = ArrayList<Frame>()
        for (a in activities(state)) {
            val activity = a.asObj
            if (!active(activity, serverTime)) continue
            var changed = false
            for (r in activity.obj("rows").arr("entries")) {
                val row = r.asObj
                val m = match(VIP_LEVEL_ROW, text(row)) ?: continue
                if (int(m.group(1)) == level) continue
                val threshold = int(m.group(2))
                row["cstring_hex"] = JStr(hex("VIP Level:$level/$threshold"))
                row["wire_u8_flag"] = JInt(if (level >= threshold) 2 else 1)
                row["conditional_cstring_hex"] = JStr(CLAIM)
                changed = true
            }
            if (changed) frames.add(update(activity))
        }
        return frames
    }

    fun setVipLevel(state: JObj, level: Long, serverTime: Long?): List<Frame> = setVipLevel(state, BigInteger.valueOf(level), serverTime)

    /**
     * C1121 row advance: (claimed row index, reward pairs) or null when no row is claimable. The claimed row takes the
     * wording of the later tiers with its own threshold, flag 1, "Claimed"; the next tier turns into the progress form
     * with the same counter, flag 2 when reached else 1, "Claim Reward". Per-transaction rows count the claim and hide
     * the button until the next matching pack (candidate).
     */
    fun claimRow(activity: JObj): Pair<Int, JArr>? {
        val rows = activity.obj("rows").arr("entries").map { it.asObj }
        val index = rows.indexOfFirst { PyDocs.at(it, "wire_u8_flag") == JInt(2) }
        if (index < 0) return null
        val row = rows[index]
        val text = text(row)
        val pairs = JArr(PyDocs.at(row, "pairs").asObj.arr("entries").mapTo(ArrayList<JValue>()) { JArr(PyDocs.at(it.asObj, "wire_values").asArr.toMutableList()) })
        val transaction = match(TRANSACTION, text)
        if (transaction != null) {
            row["cstring_hex"] = JStr(hex("Each Transaction: ${transaction.group(1)}.${transaction.group(2)} dollars " +
                "(${int(transaction.group(3)) + BigInteger.ONE}/${transaction.group(4)})"))
            row["wire_u8_flag"] = JInt(0)
            row["conditional_cstring_hex"] = JNull
            return index to pairs
        }
        val progress = search(PROGRESS, text)
        val template = (rows.subList(index + 1, rows.size) + rows.subList(0, index)).firstOrNull { r ->
            (PyDocs.at(r, "wire_u8_flag") == JInt(0) || PyDocs.at(r, "conditional_cstring_hex") == JStr(CLAIMED)) &&
                search(NUMBER, text(r)) != null && search(PROGRESS, text(r)) == null
        }?.let { text(it) }
        if (progress != null && template != null) {
            row["cstring_hex"] = JStr(hex(NUMBER.matcher(template).replaceFirst(Matcher.quoteReplacement(progress.group(2)))))
        }
        row["wire_u8_flag"] = JInt(1)
        row["conditional_cstring_hex"] = JStr(CLAIMED)
        val following = if (index + 1 < rows.size) rows[index + 1] else null
        if (progress != null && following != null && PyDocs.at(following, "wire_u8_flag") == JInt(0)) {
            val number = search(NUMBER, text(following))
            if (number != null) {
                val current = int(progress.group(1))
                val threshold = int(number.group(0))
                following["cstring_hex"] = JStr(hex(text.substring(0, progress.start()) + "$current/$threshold" + text.substring(progress.end())))
                following["wire_u8_flag"] = JInt(if (current >= threshold) 2 else 1)
                following["conditional_cstring_hex"] = JStr(CLAIM)
            }
        }
        return index to pairs
    }
}

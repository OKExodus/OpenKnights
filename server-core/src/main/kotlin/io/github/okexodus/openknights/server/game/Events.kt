package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.server.ReleaseData
import java.math.BigInteger

/**
 * Local event definitions (`events.py`): the city Events tab ladders and the Event Hall exchanges from one data file.
 * The login refresh reconciles the S18 activity list with the definitions and keeps the Fate roulette window rolling
 * (release mode). Every activity of the file is a labeled local preservation policy.
 */
object Events {
    const val PROFILE = "offline_knights_events_v1"
    const val LOCAL_ID_FIRST = 9_000_000L
    const val LOCAL_ID_LAST = 9_099_999L
    const val EXCHANGE_ID_FIRST = 9_100_000L
    const val EXCHANGE_ID_LAST = 9_199_999L
    val MATERIAL_KINDS = listOf(1L, 2L, 3L, 9L)
    val RESULT_KINDS = listOf(1L, 3L, 9L)
    const val PERMANENT_SECONDS = 5L * 365 * 86400
    const val MAX_END = 0x7fffffffL
    const val CLAIM = "Claim Reward"
    const val CLAIMED = "Claimed"
    val SOURCES = setOf("diamond_spend", "diamond_recharge", "vip_level", "summon_supreme", "summon_any",
        "castle_collect_gold", "castle_collect_honor", "castle_collect_runes", "login_days", "battles_won")
    val INERT: Set<String> = emptySet()

    class EventDefinitionError(message: String) : IllegalArgumentException(message)

    /** The service's loaded definitions (`ACTIVE`, set at start-up). */
    @Volatile
    var ACTIVE: JObj = jobj("activities" to JArr(), "exchanges" to JArr())

    fun setActive(definitions: JObj?) {
        ACTIVE = if (definitions != null && definitions.isNotEmpty()) definitions else jobj("activities" to JArr(), "exchanges" to JArr())
    }

    private fun isInt(v: JValue?): Boolean = v is JInt

    private fun textOk(value: JValue?, label: String) {
        if (value !is JStr || value.value.contains('\u0000') || value.value.contains("guding.png")) {
            throw EventDefinitionError("$label must be text without NUL (and never 'guding.png')")
        }
    }

    private fun tripleOk(value: JValue?, kinds: List<Long>): Boolean {
        if (value !is JArr || value.size != 3 || !value.all { it is JInt }) return false
        val v = value.map { (it as JInt).value }
        return v[0] in kinds.map { BigInteger.valueOf(it) } && v[1].signum() > 0 && v[2].signum() > 0 && v[2] <= BigInteger.valueOf(0xffffffffL)
    }

    private fun length(v: JValue?): Int = when (v) {
        is JArr -> v.size
        is JObj -> v.size
        is JStr -> v.value.codePointCount(0, v.value.length)
        else -> throw PyDocs.TypeError("object has no len()")
    }

    fun validate(document: JObj): JObj {
        if (PyDocs.get(document, "profile") != JStr(PROFILE)) throw EventDefinitionError("Unsupported event definition profile")
        val seen = HashSet<BigInteger>()
        for (a in (document["activities"] ?: JArr()) as JArr) {
            val activity = a.asObj
            val ident = activity["id"]
            if (!isInt(ident) || (ident as JInt).value < BigInteger.valueOf(LOCAL_ID_FIRST) || ident.value > BigInteger.valueOf(LOCAL_ID_LAST) || ident.value in seen) {
                throw EventDefinitionError("Activity ids must be unique inside the local range 9,000,000 – 9,099,999")
            }
            seen.add(ident.value)
            for (key in listOf("name", "title_image", "time_text", "rules_text")) textOk(activity[key] ?: JStr(""), "$ident $key")
            val kind = activity["kind"] ?: JStr("ladder")
            if (kind != JStr("ladder") && kind != JStr("info")) throw EventDefinitionError("$ident: kind must be ladder or info")
            if (kind == JStr("info")) continue
            if ((activity["source"] as? JStr)?.value !in SOURCES) throw EventDefinitionError("$ident: unknown counter source")
            val wording = (activity["wording"] ?: JObj()) as JObj
            val progress = (wording["progress"] ?: JStr("")) as JStr
            val future = (wording["future"] ?: JStr("")) as JStr
            if ("{x}" !in progress.value || "{t}" !in progress.value || "{t}" !in future.value) {
                throw EventDefinitionError("$ident: wording needs progress '{x}/{t}' and future '{t}'")
            }
            val tiers = (activity["tiers"] ?: JArr()) as JArr
            if (tiers.size < 2 || tiers.size > 255) throw EventDefinitionError("$ident: 2 – 255 tiers")
            var last = BigInteger.ZERO
            for (t in tiers) {
                val tier = t.asObj
                val threshold = tier["threshold"]
                if (!isInt(threshold) || (threshold as JInt).value <= last) throw EventDefinitionError("$ident: thresholds must rise")
                last = threshold.value
                val rewards = (tier["rewards"] ?: JArr()) as JArr
                if (rewards.size < 1 || rewards.size > 255 || rewards.any { p -> length(p) != 2 || (p as JArr).minWith(PyDocs.ORDER).let { PyDocs.compare(it, JInt(0)) <= 0 } }) {
                    throw EventDefinitionError("$ident: each tier needs 1 – 255 [item, count] rewards")
                }
            }
        }
        val exchanges = (document["exchanges"] ?: JArr()) as JArr
        if (exchanges.size > 255) throw EventDefinitionError("At most 255 exchanges (S1760 type 9 count is a u8)")
        for (e in exchanges) {
            val exchange = e.asObj
            val ident = exchange["id"]
            if (!isInt(ident) || (ident as JInt).value < BigInteger.valueOf(EXCHANGE_ID_FIRST) || ident.value > BigInteger.valueOf(EXCHANGE_ID_LAST) || ident.value in seen) {
                throw EventDefinitionError("Exchange ids must be unique inside the local range 9,100,000 – 9,199,999")
            }
            seen.add(ident.value)
            for (key in listOf("name", "desc")) textOk(exchange[key] ?: JStr(""), "$ident $key")
            val scope = exchange["limit_scope"]
            if (scope != JStr("daily") && scope != JStr("weekly")) throw EventDefinitionError("$ident: limit_scope must be 'daily' or 'weekly'")
            if (scope == JStr("weekly")) {
                val reset = exchange["reset"]
                val ok = reset is JObj && isInt(reset["weekday"]) && (reset["weekday"] as JInt).value.toLong().let { it in 0..6 } &&
                    isInt(reset["hour"] ?: JInt(0)) && ((reset["hour"] ?: JInt(0)) as JInt).value.let { it >= BigInteger.ZERO && it <= BigInteger.valueOf(23) }
                if (!ok) throw EventDefinitionError("$ident: weekly exchanges need reset {weekday 0-6 (Monday 0), hour}")
            }
            if (exchange.containsKey("end")) {
                val end = exchange["end"]
                if (!isInt(end) || (end as JInt).value.signum() <= 0 || end.value > BigInteger.valueOf(MAX_END)) {
                    throw EventDefinitionError("$ident: end must be an epoch second")
                }
            }
            val formulas = (exchange["formulas"] ?: JArr()) as JArr
            if (formulas.size < 1 || formulas.size > 255) throw EventDefinitionError("$ident: 1 – 255 formulas")
            for (f in formulas) {
                val formula = f.asObj
                val materials = (formula["materials"] ?: JArr()) as JArr
                val result = formula["result"]
                val limit = formula["limit"]
                if (materials.size < 1 || materials.size > 255 || materials.any { !tripleOk(it, MATERIAL_KINDS) } || !tripleOk(result, RESULT_KINDS)) {
                    throw EventDefinitionError("$ident: materials must be [1 item | 2 hero | 3 equipment | 9 jewelry, id, count]; " +
                        "results [1 item | 3 equipment | 9 jewelry, id, count]")
                }
                if (!isInt(limit) || (limit as JInt).value.signum() <= 0 || limit.value > BigInteger.valueOf(0xffffffffL)) {
                    throw EventDefinitionError("$ident: each formula needs a positive limit")
                }
            }
        }
        return document
    }

    private fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).toHexString()

    /** `template.format(**fields)` for the wording templates: `{name}` fields and doubled braces. */
    fun format(template: String, fields: Map<String, Any>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> { out.append('{'); i += 2 }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> { out.append('}'); i += 2 }
                c == '{' -> {
                    val end = template.indexOf('}', i)
                    if (end < 0) throw PyValues.ValueError("Single '{' encountered in format string")
                    val name = template.substring(i + 1, end)
                    if (name.any { it == ':' || it == '!' || it == '.' || it == '[' }) throw NotPorted("format spec {$name} of an event wording")
                    if (name.isEmpty() || name.all { it.isDigit() }) throw IndexOutOfBoundsException("Replacement index ${name.ifEmpty { "0" }} out of range for positional args tuple")
                    val value = fields[name] ?: throw PyDocs.KeyError("'$name'")
                    out.append(value.toString())
                    i = end + 1
                }
                c == '}' -> throw PyValues.ValueError("Single '}' encountered in format string")
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The S18 / S1188 rows of a ladder with its counter and the number of claimed tiers. */
    fun rowsFor(activity: JObj, counter: BigInteger, claimed: Int): JObj {
        val wording = activity.obj("wording")
        val rows = JArr()
        activity.arr("tiers").forEachIndexed { index, t ->
            val tier = t.asObj
            val rewards = tier.arr("rewards")
            val pairs = jobj("count" to rewards.size, "entries" to rewards.map { jobj("wire_values" to JArr(it.asArr.toMutableList())) })
            val threshold = tier.int("threshold")
            val text: String
            val flag: Int
            val button: String?
            if (index < claimed) {
                text = format(wording.str("future"), mapOf("t" to threshold)); flag = 1; button = CLAIMED
            } else if (index == claimed) {
                text = format(wording.str("progress"), mapOf("x" to counter, "t" to threshold))
                flag = if (counter >= threshold && activity.strOrNull("source") !in INERT) 2 else 1
                button = CLAIM
            } else {
                text = format(wording.str("future"), mapOf("t" to threshold)); flag = 0; button = null
            }
            rows.add(jobj("cstring_hex" to hex(text), "pairs" to pairs, "wire_u8_flag" to flag,
                "conditional_cstring_hex" to (if (button != null) hex(button) else null)))
        }
        return jobj("count" to rows.size, "entries" to rows)
    }

    fun entryFor(activity: JObj, servedTime: Long, counter: BigInteger = BigInteger.ZERO, claimed: Int = 0): JObj {
        val end = minOf(MAX_END, servedTime + PERMANENT_SECONDS)
        val rows = if ((activity["kind"] ?: JStr("ladder")) == JStr("ladder")) rowsFor(activity, counter, claimed) else jobj("count" to 0, "entries" to JArr())
        return jobj("wire_u32_1" to activity["id"], "cstring_hex" to listOf("name", "title_image", "time_text", "rules_text").map { hex(activity.str(it)) },
            "wire_u32_after_strings" to end, "rows" to rows)
    }

    /** (counter, claimed tiers) read back from stored rows. */
    fun progressOf(activity: JObj, entry: JObj): Pair<BigInteger, Int> {
        val rows = entry.obj("rows").arr("entries")
        val claimed = rows.count { (it.asObj["conditional_cstring_hex"]) == JStr(hex(CLAIMED)) }
        val template = activity.obj("wording").str("progress")
        val pattern = StringBuilder("(?U)")
        var i = 0
        while (i < template.length) {
            when {
                template.startsWith("{x}", i) -> { pattern.append("(\\d+)"); i += 3 }
                template.startsWith("{t}", i) -> { pattern.append("\\d+"); i += 3 }
                else -> {
                    val cp = template.codePointAt(i)
                    pattern.append(java.util.regex.Pattern.quote(String(Character.toChars(cp))))
                    i += Character.charCount(cp)
                }
            }
        }
        val regex = Regex(pattern.toString())
        var counter = BigInteger.ZERO
        for (row in rows) {
            val text = String(row.asObj.str("cstring_hex").hexBytes(), Charsets.UTF_8)
            val match = regex.matchEntire(text)
            if (match != null) counter = PyValues.parseInt(match.groupValues[1])
        }
        return counter to claimed
    }

    /** Apply the definition file to a save's S18 activity list; returns whether anything changed. */
    fun reconcile(state: JObj, definitions: JObj, servedTime: Long, initialCounters: Map<String, JValue>? = null): Boolean {
        val section = state.obj("subsystems").obj("game_activities")
        val first = section.obj("first_list")
        var entries: List<JValue> = first.arr("entries")
        val activities = (definitions["activities"] ?: JArr()) as JArr
        val defined = LinkedHashMap<JValue, JObj>().also { m -> activities.forEach { m[it.asObj["id"]!!] = it.asObj } }
        val before = PyDocs.sortedDump(JArr(entries.toMutableList()))
        if (!PyDocs.truthy(definitions["retain_captured"])) entries = entries.filter { PyDocs.at(it.asObj, "wire_u32_1") in defined }
        val byId = LinkedHashMap<JValue, JObj>().also { m -> entries.forEach { m[PyDocs.at(it.asObj, "wire_u32_1")] = it.asObj } }
        val rebuilt = ArrayList<JValue>()
        for (a in activities) {
            val activity = a.asObj
            var entry = byId[activity["id"]]
            if (entry == null) {
                val counter = initialCounters?.get((activity["source"] as? JStr)?.value ?: "")?.let { PyDocs.int(it) } ?: BigInteger.ZERO
                entry = entryFor(activity, servedTime, counter)
            } else {
                if (PyDocs.compare(PyDocs.at(entry, "wire_u32_after_strings"), JInt(minOf(MAX_END, servedTime + PERMANENT_SECONDS / 2))) < 0) {
                    entry["wire_u32_after_strings"] = JInt(minOf(MAX_END, servedTime + PERMANENT_SECONDS))
                }
                entry["cstring_hex"] = JArr(listOf("name", "title_image", "time_text", "rules_text").mapTo(ArrayList()) { JStr(hex(activity.str(it))) })
            }
            rebuilt.add(entry)
        }
        val extras = entries.filter { PyDocs.at(it.asObj, "wire_u32_1") !in defined }
        first["entries"] = JArr((rebuilt + extras).toMutableList())
        first["count"] = JInt(first.arr("entries").size)
        val rolled = rollRouletteWindow(section, definitions, servedTime)
        return PyDocs.sortedDump(first["entries"]) != before || rolled
    }

    /**
     * Release mode: the Fate roulette window is permanent and rolling — its end moves forward only when less than half
     * of the span is left; a start after now moves back to now.
     */
    fun rollRouletteWindow(section: JObj, definitions: JObj, servedTime: Long): Boolean {
        val roulette = PyDocs.get(section, "roulette")
        if (definitions["roulette_window"] != JStr("rolling") || roulette == null) return false
        val values = (roulette as JObj).arr("wire_values")
        val before = JArr(values.toMutableList())
        if (PyDocs.compare(values[1], JInt(servedTime)) > 0 || values[1] == JInt(0)) values[1] = JInt(servedTime)
        if (PyDocs.compare(values[2], JInt(minOf(MAX_END, servedTime + PERMANENT_SECONDS / 2))) < 0) {
            values[2] = JInt(minOf(MAX_END, servedTime + PERMANENT_SECONDS))
        }
        return values != before
    }

    fun defined(activityId: JValue, definitions: JObj? = null): JObj? =
        ((definitions ?: ACTIVE)["activities"] as? JArr ?: JArr()).map { it.asObj }.firstOrNull { it["id"] == activityId }

    /** Add to every defined ladder of a counter source; S1188 frames. */
    fun advance(state: JObj, definitions: JObj, source: String, amount: Long, servedTime: Long): List<Frame> {
        if (source in INERT || amount <= 0) return emptyList()
        val frames = ArrayList<Frame>()
        val byId = LinkedHashMap<JValue, JObj>()
        for (a in (definitions["activities"] ?: JArr()) as JArr) if ((a.asObj["source"] as? JStr)?.value == source) byId[a.asObj["id"]!!] = a.asObj
        for (e in state.obj("subsystems").obj("game_activities").obj("first_list").arr("entries")) {
            val entry = e.asObj
            val activity = byId[entry["wire_u32_1"]] ?: continue
            if (PyDocs.compare(JInt(servedTime), PyDocs.at(entry, "wire_u32_after_strings")) >= 0) continue
            val (counter, claimed) = progressOf(activity, entry)
            entry["rows"] = rowsFor(activity, counter + BigInteger.valueOf(amount), claimed)
            frames.add(1188 to PlayerSections.encodeActivityUpdate(PyDocs.long(entry["wire_u32_1"]), entry.obj("rows")))
        }
        return frames
    }

    /** `ReleaseData.events()`: the definitions with texts resolved from the APK; the roulette window rolls (POLICY). */
    fun releaseEvents(data: ReleaseData): JObj {
        val document = data.document("events.json")
        for (e in (document["exchanges"] ?: JArr()) as JArr) {
            val exchange = e.asObj
            exchange["name"] = data.resolveText(exchange["name"]!!)
            exchange["desc"] = data.resolveText(exchange["desc"]!!)
        }
        document["roulette_window"] = JStr("rolling")
        validate(document)
        document["sha256"] = JStr(data.sha256("events.json"))
        document["path"] = JStr(data.label("events.json"))
        return document
    }
}

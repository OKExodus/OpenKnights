package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.exact.PyRandom
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.store.SqlConnection
import io.github.okexodus.openknights.server.store.StateStore
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The login parts of `summon.py`: the free-draw timers S354 (`u32 a, u32 b` remaining seconds of the lot-2 / lot-3
 * free single), the summon document seeded at the first use, and the service clock (`now_epoch`: the device clock
 * with its high-water mark in release mode).
 */
object Summon {
    const val S_FREE_CD = 354
    const val DOCUMENT_PROFILE = "summon_state_v1"
    val LOT_ACHIEVEMENT = linkedMapOf(1L to 27L, 2L to 28L, 3L to 29L)

    /** The service clock: the device clock in release mode, else the host epoch. */
    fun nowEpoch(): Long = DeviceClock.active?.now() ?: Now.epoch()

    fun freeCdPayload(remainingA: Long, remainingB: Long): ByteArray =
        WireWriter().number('I', maxOf(0L, remainingA)).number('I', maxOf(0L, remainingB)).bytes()

    /**
     * Seed the summon state the first time (labeled policy): fresh characters start both free timers from their
     * creation (`created_at_utc` + 86,400 / 259,200 s); first-draw rows count as used when the lot's cumulative draw
     * counter (achievement kinds 27 / 28 / 29) is positive.
     */
    fun initialDocument(current: StateStore.Current, now: Long): JObj {
        val counters = LinkedHashMap<JValue, JValue>()
        for (e in current.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = e.asObj.arr("wire_values")
            counters[wire[0]] = wire[2]
        }
        val profile = current.characterProfile
        val freeNext = if (profile != null) {
            val stamp = PyDocs.get(profile.obj("document"), "created_at_utc")
            val created = if (Py.truthy(stamp)) isoTimestamp(PyDocs.str(stamp)) else now
            jobj("2" to created + 86400, "3" to created + 259200)
        } else jobj("2" to 0, "3" to 0)
        val firstUsed = JObj()
        for ((lot, kind) in LOT_ACHIEVEMENT) {
            val count = counters[JInt(kind)] ?: JInt(0)
            firstUsed[lot.toString()] = JBool(PyDocs.compare(count, JInt(0)) > 0)
        }
        return jobj("profile" to DOCUMENT_PROFILE, "free_next_epoch" to freeNext, "first_used" to firstUsed,
            "seeded_at_epoch" to now, "seed_rule" to "derived: free now; fresh: creation + CD; first rows by counters")
    }

    fun readSummonState(db: SqlConnection): JValue? {
        if (!db.tableExists("summon_state")) return null
        val row = db.queryOne("SELECT document_json FROM summon_state WHERE id=1") ?: return null
        return Json.loads(row.string("document_json"))
    }

    fun writeSummonState(db: SqlConnection, document: JValue) = Shops.writeState(db, "summon_state", document)

    fun freeCdRemaining(document: JObj, now: Long): List<Long> {
        val next = PyDocs.at(document, "free_next_epoch") as JObj
        return listOf("2", "3").map { maxOf(0L, PyDocs.long(PyDocs.at(next, it)) - now) }
    }

    /**
     * `int(datetime.fromisoformat(stamp).timestamp())`: an aware stamp maps through its own offset, a naive one
     * through the host's local offset; the fractional seconds are truncated toward zero.
     */
    fun isoTimestamp(stamp: String): Long {
        val parsed = parseIso(stamp)
        val epoch = parsed.first
        val nanos = parsed.second
        return if (epoch < 0 && nanos > 0) epoch + 1 else epoch
    }

    private val NAIVE = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private fun parseIso(stamp: String): Pair<Long, Int> {
        val text = stamp.replace(' ', 'T')
        try {
            val aware = OffsetDateTime.parse(text)
            return aware.toEpochSecond() to aware.nano
        } catch (_: DateTimeParseException) {
        }
        try {
            val naive = LocalDateTime.parse(text, NAIVE)
            val guess = naive.toEpochSecond(ZoneOffset.UTC)
            val offset = Now.offset(guess)
            return naive.toEpochSecond(ZoneOffset.ofTotalSeconds(offset)) to naive.nano
        } catch (_: DateTimeParseException) {
        }
        try {
            val date = java.time.LocalDate.parse(stamp)
            val guess = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            return date.atStartOfDay().toEpochSecond(ZoneOffset.ofTotalSeconds(Now.offset(guess))) to 0
        } catch (_: DateTimeParseException) {
        }
        throw PyValues.ValueError("Invalid isoformat string: '$stamp'")
    }

    // --- summons (C321) and hero refine (C1251) --------------------------------------------------------------------

    const val SUMMON_OPCODE = 321
    const val REFINE_OPCODE = 1251
    const val LUCK_OPCODE = 1253
    const val S_LOT_RESULT = 352
    const val S_REFINE_RESULT = 1570
    const val S_LUCK = 1572
    const val S_BENCH_REMOVE = 40
    const val S_HERO_REMOVE = 34
    const val PAL_POINTS = 11L
    /** Currency f103 → property (30101 Summon / 30108 Special Voucher). */
    val VOUCHER_PROPERTY = mapOf(2L to 14L, 3L to 293L)
    /** GameStateLot::OnEnter always lists LotTypes 0..2; 3..5 need S358. */
    val OFFERED_LOTS = listOf(1L, 2L, 3L)
    /** 86,400 / 259,200 s = the observed S354 restarts. */
    val FREE_LOT_PROPERTY = mapOf(2L to 110L, 3L to 111L)
    /** niudanhero item 20001 = Gold. */
    const val GOLD_ITEM = 20001L
    /** The S352 handler ignores more than 11 results. */
    const val MAX_RESULT = 11

    /** `decode_summon_request(payload)`: `u8 lot, u8 mode[, u8 ronghe]` (the third byte only with lot-1 multis). */
    fun decodeSummonRequest(payload: ByteArray): JObj {
        if (payload.size != 2 && payload.size != 3) throw Acquisition.Rejected("C321 is u8 lot, u8 mode[, u8 ronghe]")
        val lot = payload[0].toLong() and 0xFF
        val mode = payload[1].toLong() and 0xFF
        val ronghe = if (payload.size == 3) payload[2].toLong() and 0xFF else null
        if (ronghe != null && (lot != 1L || mode == 0L)) throw Acquisition.Rejected("The ronghe byte is only sent with lot-1 multi summons")
        return jobj("lot" to lot, "mode" to mode, "ronghe" to ronghe)
    }

    /** `decode_refine_request(payload)`: `u8 n (>= 1), n x u32 hero uid`. */
    fun decodeRefineRequest(payload: ByteArray): JObj {
        if (payload.isEmpty() || payload.size != 1 + 4 * (payload[0].toInt() and 0xFF) || payload[0].toInt() == 0) {
            throw Acquisition.Rejected("C1251 is u8 n (>= 1), n x u32 hero uid")
        }
        val r = WireReader(payload.copyOfRange(1, payload.size))
        return jobj("uids" to (0 until (payload[0].toInt() and 0xFF)).map { r.u32() })
    }

    /** S352: `u8 count, count x u32 packed hero, Reward`. */
    fun lotResultPayload(templates: List<Long>, reward: JObj): ByteArray {
        val w = WireWriter().number('B', templates.size.toLong())
        templates.forEach { w.number('I', it) }
        return w.bytes() + BattleReport.encodeReward(reward)
    }

    /** `_pick_row(inputs, lot, mode, document, now)`: the free single, else a pending first-draw row, else the normal row. */
    private fun pickRow(inputs: AcquisitionInputs, lot: Long, mode: Long, document: JObj, now: Long): Pair<JObj, String> {
        var rows = inputs.lotRows().filter { it.long("lot") == lot }
        val count = mapOf(0L to 1L, 1L to 11L, 2L to 120L)[mode] ?: throw Acquisition.Rejected("Unknown summon mode")
        rows = rows.filter { it.long("count_105") == count && it.long("limited_108") == 0L }
        if (count == 1L) {
            val free = rows.filter { it.long("free_106") != 0L }
            val next = document.obj("free_next_epoch")
            if (free.isNotEmpty() && lot.toString() in next && PyDocs.compare(next.getValue(lot.toString()), JInt(now)) <= 0) return free[0] to "free"
        }
        val first = rows.filter { it.long("first_107") != 0L && it.long("free_106") == 0L }
        if (first.isNotEmpty() && !Py.truthy(document.obj("first_used")[lot.toString()] ?: JBool(true))) return first[0] to "first"
        val normal = rows.filter { it.long("first_107") == 0L && it.long("free_106") == 0L }
        if (normal.isEmpty()) throw Acquisition.Rejected("No configured summon row for this lot/mode")
        return normal[0] to "normal"
    }

    /** `_weighted(rng, entries, weight)`: one entry by positive weight, null when nothing weighs. */
    private fun <T> weighted(rng: PyRandom, entries: List<T>, weight: (T) -> Long): T? {
        val kept = entries.filter { weight(it) > 0 }
        val total = kept.sumOf { weight(it) }
        if (total == 0L) return null
        var roll = rng.randrange(total)
        for (entry in kept) {
            roll -= weight(entry)
            if (roll < 0) return entry
        }
        return kept.last()
    }

    /** `_draw_hero(rng, inputs, row, server_time, group)`: (group, packed hero). */
    private fun drawHero(rng: PyRandom, inputs: AcquisitionInputs, row: JObj, serverTime: Long, group: Long? = null): Pair<Long, Long> {
        var chosen = group
        if (chosen == null) {
            val slots = ArrayList<Pair<Long, Long>>()
            for ((index, s) in row.arr("slots").withIndex()) {
                var g = s.asArr[0].long
                val w = s.asArr[1].long
                if (index == 3 && row.long("rotation_991") != 0L && g != 0L) {
                    g = inputs.rotationGroup(row.long("rotation_991"), serverTime)?.takeIf { it != 0L } ?: g
                }
                if (g != 0L && w > 0 && inputs.niudanGroup(g).any { it.long("weight") > 0 && it.long("hero") != 0L }) slots.add(g to w)
            }
            val slot = weighted(rng, slots) { it.second } ?: throw Acquisition.Rejected("Summon row has no drawable group")
            chosen = slot.first
        }
        val hero = weighted(rng, inputs.niudanGroup(chosen).filter { it.long("hero") != 0L }) { it.long("weight") }
            ?: throw Acquisition.Rejected("Summon group has no drawable hero")
        return chosen to hero.long("hero")
    }

    /**
     * `plan_summon(request, owned, inputs, document, now, seed)`: one C321 on the character's summon state (the mutated
     * copy is the plan's `document_after`). Every random choice is the labeled local policy, seeded and recorded.
     */
    fun planSummon(request: JObj, owned: Owned, inputs: AcquisitionInputs, document: JObj, now: Long, seed: BigInteger,
                   serverTime: Long? = null): Plan {
        val lot = request.long("lot")
        val mode = request.long("mode")
        if (lot !in OFFERED_LOTS) throw Acquisition.Rejected("This summon lot is not offered (no limited-time lot list)", Acquisition.ERROR_WRONG_TYPE)
        if (Py.truthy(request["ronghe"])) throw Acquisition.Rejected("ronghe = 1 was never captured (excluded branch)", Acquisition.ERROR_WRONG_TYPE)
        val doc = document.deepCopy()
        val (row, kind) = pickRow(inputs, lot, mode, doc, now)
        val count = row.long("count_105")
        if (owned.state.arr("heroes").size + minOf(count, MAX_RESULT.toLong()) > 255) throw Acquisition.Rejected("Hero list would exceed its wire limit", 1016)
        val rng = PyRandom.seeded(seed)
        val time = serverTime ?: now
        val packets = ArrayList<Frame>()
        var costFrame: Frame? = null
        var palFrame: Frame? = null
        val cost = if (kind != "free") row.long("cost_104") else 0L
        val currency = row.long("currency_103")
        if (cost != 0L) {
            if (currency == 1L) {
                if (owned.roleBits(PAL_POINTS) < BigInteger.valueOf(cost)) throw Acquisition.Rejected("Not enough Pal Points", Acquisition.ERROR_RESOURCES)
                palFrame = owned.roleAdd(PAL_POINTS, -cost)
            } else if (currency in VOUCHER_PROPERTY) {
                val voucher = PyValues.parseLong(inputs.property(VOUCHER_PROPERTY.getValue(currency))!!)
                val frames = owned.consumeTemplate(voucher, cost)
                if (frames.size != 1) throw Acquisition.Rejected("Voucher must come from one stack", Acquisition.ERROR_NOT_ENOUGH)
                costFrame = frames[0]
            } else throw Acquisition.Rejected("Unknown summon currency")
        }
        val draws = ArrayList<JObj>()
        for (index in 0 until count) {
            val firstGroup = if (kind == "first" && index == 0L && row.long("first_group_200") != 0L) row.long("first_group_200") else null
            val (group, template) = drawHero(rng, inputs, row, time, firstGroup)
            draws.add(jobj("group" to group, "hero" to template))
        }
        if (kind == "first") doc.obj("first_used")[lot.toString()] = JBool(true)
        // Bonus (f901 chance / 10000 of one row of group f902 per hero; lucky item f301 x f302 per row).
        val reward = Acquisition.emptyReward()
        val bonusItems = LinkedHashMap<Long, Long>()
        var gold = 0L
        val chance = row.arr("bonus_901_903")[0].long
        val group902 = row.arr("bonus_901_903")[1].long
        for (draw in draws) {
            val bonus = if (chance != 0L && group902 != 0L && rng.randrange(10000) < chance) weighted(rng, inputs.niudanGroup(group902)) { it.long("weight") } else null
            if (bonus != null) {
                draw["bonus_row"] = bonus["id"]!!
                if (bonus.long("lucky_item_301") != 0L && bonus.long("lucky_count_302") != 0L) {
                    bonusItems[bonus.long("lucky_item_301")] = (bonusItems[bonus.long("lucky_item_301")] ?: 0L) + bonus.long("lucky_count_302")
                }
                if (bonus.long("item_105") == GOLD_ITEM) gold += bonus.long("count_106")
                else if (bonus.long("item_105") != 0L) bonusItems[bonus.long("item_105")] = (bonusItems[bonus.long("item_105")] ?: 0L) + bonus.long("count_106")
            }
        }
        // Mode 2 ("120 Times", "Auto Fuse 3 star and below"): heroes of 3 stars or less (or beyond the 11 S352 lists) refined.
        val fused = ArrayList<JObj>()
        val fusedItems = LinkedHashMap<Long, Long>()
        val drawsToGrant: List<JObj>
        if (mode == 2L) {
            val kept = ArrayList<JObj>()
            for (draw in draws) {
                val star = inputs.heroStar(draw.long("hero"))
                if ((star != null && star <= 3) || kept.size >= MAX_RESULT) {
                    val superClass = Math.floorMod(Math.floorMod(Math.floorDiv(draw.long("hero"), 100L), 10L), 3L)
                    val refine = (if (star != null) inputs.refineAmount(star, superClass) else null)
                        ?: throw Acquisition.Rejected("No refine row for a drawn hero")
                    fusedItems[refine.long("item")] = (fusedItems[refine.long("item")] ?: 0L) + refine.long("amount")
                    fused.add(draw)
                } else kept.add(draw)
            }
            drawsToGrant = kept
        } else drawsToGrant = draws
        // Frames: voucher cost first; per hero S32, S38, [Pal S128 after the first hero's S38], [book], S2850, S1184.
        if (costFrame != null) packets.add(costFrame)
        val templates = ArrayList<Long>()
        for ((index, draw) in drawsToGrant.withIndex()) {
            val (uid, groups) = owned.grantHero(draw.long("hero"))
            draw["uid"] = JInt(uid)
            templates.add(draw.long("hero"))
            packets.addAll(groups.getValue("add"))
            if (index == 0 && palFrame != null) {
                packets.add(palFrame)
                palFrame = null
            }
            packets.addAll(groups.getValue("book") + groups.getValue("god") + groups.getValue("activity"))
        }
        for ((template, amount) in fusedItems) {
            packets.add(owned.grantItem(template, amount))
            reward.arr("items").add(jarr(template, amount))
        }
        if (palFrame != null) packets.add(palFrame)
        for ((template, amount) in bonusItems) {
            packets.add(owned.grantItem(template, amount))
            reward.arr("items").add(jarr(template, amount))
        }
        if (gold != 0L) {
            packets.add(owned.roleAdd(Acquisition.GOLD, gold))
            reward["gold"] = JInt(gold)
        }
        val shown = ArrayList(templates)
        if (shown.size > 1) {   // live: S352 lists the heroes with the first and last exchanged
            val t = shown[0]; shown[0] = shown[shown.size - 1]; shown[shown.size - 1] = t
        }
        packets.add(S_LOT_RESULT to lotResultPayload(shown, reward))
        if (kind == "free") {
            doc.obj("free_next_epoch")[lot.toString()] = JInt(now + PyValues.parseLong(inputs.property(FREE_LOT_PROPERTY.getValue(lot))!!))
            val (a, b) = freeCdRemaining(doc, now)
            packets.add(S_FREE_CD to freeCdPayload(a, b))
        }
        for (entry in owned.state.obj("subsystems").obj("achievements").arr("entries")) {
            val wire = entry.asObj.arr("wire_values")
            if (wire[0].long == LOT_ACHIEVEMENT.getValue(lot)) {
                wire[2] = JInt(wire[2].big + BigInteger.valueOf(count))
                packets.add(Acquisition.S_ACHIEVEMENT to Acquisition.achievementPayload(wire))
            }
        }
        val fusedJson = JObj()
        for ((k, v) in fusedItems) fusedJson[k.toString()] = JInt(v)
        return Plan(jobj("lot" to lot, "mode" to mode, "row" to row["id"], "row_kind" to kind, "count" to count, "cost" to cost,
            "currency" to currency, "draws" to draws, "fused" to fused.size, "fused_items" to fusedJson, "reward" to reward,
            "document_after" to doc, "shown" to shown, "evidence_class" to "preservation_policy_summon_draw", "seed" to seed), packets)
    }

    /** `plan_refine(request, owned, inputs, excluded_uids, deployed_uids)`: C1251 — consume bench heroes, grant Σ herorh f105 of item f104. */
    fun planRefine(request: JObj, owned: Owned, inputs: AcquisitionInputs, excludedUids: Collection<Long> = emptyList(),
                   deployedUids: Collection<Long> = emptyList(), leaderUids: Collection<Long> = emptyList()): Plan {
        val uids = request.arr("uids").map { it.long }
        if (uids.toSet().size != uids.size) throw Acquisition.Rejected("Duplicate hero in the refine request")
        val heroes = LinkedHashMap<Long, Map<Long, JValue?>>()
        for (f in owned.state.arr("heroes")) { val v = Acquisition.heroValues(f.asArr); heroes[v[0L]!!.long] = v }
        val bench = owned.state.arr("offline_hero_uids").map { it.long }.toSet()
        val leaders = HashSet(leaderUids).also { s -> heroes.forEach { (uid, v) -> if (Py.truthy(v[14L])) s.add(uid) } }
        var total = 0L
        var item: Long? = null
        val rows = JArr()
        for (uid in uids) {
            if (uid !in heroes) throw Acquisition.Rejected("Hero is not owned", 1000)
            if (uid !in bench || uid in deployedUids || uid in excludedUids || uid in leaders) {
                throw Acquisition.Rejected("Only bench heroes outside any assignment can be refined", 1002)
            }
            val template = heroes.getValue(uid)[1L]!!.long
            val star = inputs.heroStar(template)
            val superClass = Math.floorMod(Math.floorMod(Math.floorDiv(template, 100L), 10L), 3L)
            val refine = (if (star != null && star != 0L) inputs.refineAmount(star, superClass) else null)
                ?: throw Acquisition.Rejected("No refine row for this hero", Acquisition.ERROR_WRONG_TYPE)
            if (item != null && refine.long("item") != item) throw Acquisition.Rejected("Mixed refine outputs are not supported")
            item = refine.long("item")
            total += refine.long("amount")
            rows.add(jobj("uid" to uid, "template" to template, "star" to star, "super" to superClass, "row" to refine["row"], "amount" to refine["amount"]))
        }
        for (uid in uids) owned.removeHero(uid)
        val grant = owned.grantItem(item!!, total)
        val reward = Acquisition.emptyReward()
        reward["items"] = jarr(jarr(item, total))
        val packets = listOf(grant, S_REFINE_RESULT to BattleReport.encodeReward(reward), S_BENCH_REMOVE to Acquisition.uidListPayload(uids),
            S_HERO_REMOVE to Acquisition.uidListPayload(uids))
        return Plan(jobj("uids" to request["uids"], "refined" to rows, "item" to item, "amount" to total, "reward" to reward,
            "evidence_class" to "config_deterministic_capture_confirmed"), packets)
    }
}

package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.store.SqlConnection

/**
 * Astral Power ("god skills", `god_skills.py`): the S2848 startup list and the owned document (table `god_skills`,
 * one checksum-bound JSON document). Heroes ascend by UID, skills ascend by id.
 */
object GodSkills {
    const val PROFILE = "god_skills_owned_v1"
    const val INIT_OPCODE = 2848
    const val UPDATE_OPCODE = 2850
    const val REQUEST_OPCODE = 2497

    private fun uint(value: Long, width: Int, label: String): Long {
        require(value >= 0 && (width == 64 || value < (1L shl width))) { "$label must fit uint$width" }
        return value
    }

    /** S2848 → [{"uid", "skills": [[skill, progress], ...]}] in wire order. */
    fun decodeGodSkillList(payload: ByteArray): List<JObj> {
        val r = WireReader(payload)
        val heroes = ArrayList<JObj>()
        val count = r.u32()
        for (h in 0L until count) {
            val uid = r.u32()
            val skills = JArr()
            val n = r.u32()
            for (s in 0L until n) skills.add(jarr(r.u32(), r.u32()))
            heroes.add(jobj("uid" to uid, "skills" to skills))
        }
        if (r.offset != payload.size) throw PyValues.ValueError("God-skill list has trailing bytes")
        return heroes
    }

    /** S2848: u32 n heroes; per hero u32 UID, u32 n, n × (u32 skill, u32 progress). */
    fun encodeGodSkillList(heroes: List<JObj>): ByteArray {
        val w = WireWriter().u32(uint(heroes.size.toLong(), 32, "Hero count"))
        for (hero in heroes) {
            val skills = hero.arr("skills")
            w.u32(uint(hero.long("uid"), 32, "Hero UID")).u32(uint(skills.size.toLong(), 32, "Skill count"))
            for (s in skills) {
                val pair = s.asArr
                w.u32(uint(pair[0].long, 32, "Skill id")).u32(uint(pair[1].long, 32, "Progress"))
            }
        }
        return w.bytes()
    }

    /** S2850: u32 hero UID, u32 n, n × (skill, progress). */
    fun encodeGodSkillUpdate(uid: Long, skills: List<JArr>): ByteArray {
        val w = WireWriter().u32(uint(uid, 32, "Hero UID")).u32(uint(skills.size.toLong(), 32, "Skill count"))
        skills.forEach { w.u32(uint(it[0].long, 32, "Skill id")).u32(uint(it[1].long, 32, "Progress")) }
        return w.bytes()
    }

    fun documentJson(document: JObj): String = Json.canonical(document)
    fun documentChecksum(document: JObj): String = sha256Hex(documentJson(document).toByteArray(Charsets.UTF_8))

    fun validateDocument(document: JObj) {
        require(document.strOrNull("profile") == PROFILE) { "God-skill document has an unsupported profile" }
        val seen = HashSet<Long>()
        var last = -1L
        for (h in (document["heroes"] as? JArr) ?: JArr()) {
            val hero = h.asObj
            val uid = (hero["uid"] as? JInt)?.value?.toLong()
            require(uid != null && uid !in seen && uid > last) { "God-skill document heroes must be unique and ascending by UID" }
            seen.add(uid)
            last = uid
            val skills = hero["skills"] as? JArr
            require(skills != null && skills.isNotEmpty()) { "God-skill hero entry needs a nonempty skill list" }
            val ids = skills.map { it.asArr[0].long }
            require(ids == ids.toSortedSet().toList()) { "God-skill ids must be unique and ascending" }
            for (s in skills) {
                uint(s.asArr[0].long, 32, "Skill id")
                uint(s.asArr[1].long, 32, "Progress")
            }
        }
    }

    fun writeGodSkills(db: SqlConnection, document: JObj, create: Boolean = false): String {
        validateDocument(document)
        val checksum = documentChecksum(document)
        if (create) {
            db.execute("""CREATE TABLE god_skills (
            id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL,
            document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)""")
            db.execute("INSERT INTO god_skills VALUES(1,1,?,?)", documentJson(document), checksum)
        } else {
            val updated = db.execute("UPDATE god_skills SET document_json=?,document_sha256=? WHERE id=1", documentJson(document), checksum)
            require(updated == 1) { "God-skill state row is missing" }
        }
        return checksum
    }

    /** Stored entries of the currently owned heroes, ascending by UID (S2848 order). */
    fun startupHeroes(document: JObj, ownedUids: Collection<Long>): List<JObj> {
        val owned = ownedUids.toSet()
        return document.arr("heroes").map { it.asObj }.filter { it.long("uid") in owned }
            .map { jobj("uid" to it.long("uid"), "skills" to JArr(it.arr("skills").mapTo(ArrayList()) { s -> JArr(s.asArr.toMutableList()) })) }
    }

    fun startupPayload(document: JObj, ownedUids: Collection<Long>): ByteArray = encodeGodSkillList(startupHeroes(document, ownedUids))

    // --- C2497 Astral Power press ------------------------------------------------------------------------------------

    const val ITEM_UPDATE_OPCODE = 68
    const val ITEM_REMOVE_OPCODE = 66
    val ALLOWED_TIMES = listOf(1L, 10L, 100L)
    const val ERROR_INVALID = 102
    const val ERROR_TOP_LEVEL = 1008
    const val ERROR_NO_SKILL = 1037
    const val ERROR_ITEMS = 2002
    const val EVIDENCE_CLASS = "capture_observed_astral_press_rule"

    open class GodSkillRejected(message: String, val code: Int = ERROR_INVALID) : IllegalArgumentException(message)

    /** C2497: u32 hero UID, u32 current skill id, u32 times. */
    fun decodeUpgradeRequest(payload: ByteArray): JObj {
        val r = WireReader(payload)
        val value = jobj("hero_uid" to r.u32(), "skill_id" to r.u32(), "times" to r.u32())
        if (r.offset != payload.size) throw PyValues.ValueError("Astral upgrade request has trailing bytes")
        return value
    }

    fun encodeUpgradeRequest(value: JObj): ByteArray =
        WireWriter().u32(uint(value.long("hero_uid"), 32, "Hero UID")).u32(uint(value.long("skill_id"), 32, "Skill"))
            .u32(uint(value.long("times"), 32, "Times")).bytes()

    /**
     * Validate one decoded C2497 and return the exact mutation (`plan_upgrade`): one press consumes row 110 items of
     * row 109 and adds +1 progress; at row 108 the skill becomes the next row (id + 1) at progress 0 and a multi-press
     * stops there; a request whose stones run out stops at the last affordable press (local choice, recorded).
     * `items` {uid: [uid, template, count]}; `rows` the zhushenzhili rows. [Plan.packets] holds the stone frame.
     */
    fun planUpgrade(request: JObj, document: JObj, ownedUids: Collection<Long>, items: Map<Long, JArr>, rows: JObj): Plan {
        val heroUid = request.long("hero_uid")
        val skillId = request.long("skill_id")
        val times = request.long("times")
        if (heroUid !in ownedUids.toSet()) throw GodSkillRejected("Hero is not owned", ERROR_NO_SKILL)
        if (times !in ALLOWED_TIMES) throw GodSkillRejected("Press count outside the client's x1/x10/x100 choices")
        val entry = document.arr("heroes").map { it.asObj }.firstOrNull { it["uid"] == JInt(heroUid) }
            ?: throw GodSkillRejected("Hero has no god-skill state", ERROR_NO_SKILL)
        val current = LinkedHashMap<Long, Long>()
        for (s in entry.arr("skills")) current[s.asArr[0].long] = s.asArr[1].long
        if (skillId !in current) throw GodSkillRejected("Hero does not have this astral skill", ERROR_NO_SKILL)
        val row = rows[skillId.toString()] as JObj?
        val following = rows[(skillId + 1).toString()] as JObj?
        if (row == null) throw GodSkillRejected("Astral skill has no configured row")
        if (following == null || following["series"] != row["series"] || following["level"] != JInt(row.int("level") + java.math.BigInteger.ONE)) {
            throw GodSkillRejected("Astral skill is at its top configured level", ERROR_TOP_LEVEL)
        }
        val need = row.long("need_108")
        val perPress = row.long("per_press_110")
        if (need <= 0 || perPress <= 0 || !io.github.okexodus.openknights.server.game.Py.truthy(row["item_109"])) {
            throw GodSkillRejected("Astral skill row has no press requirement")
        }
        var progress = current.getValue(skillId)
        if (progress >= need) throw GodSkillRejected("Stored progress already reaches the level requirement")
        val stacks = items.values.filter { it[1] == row["item_109"] }
        if (stacks.size != 1) throw GodSkillRejected("Astral Stone is missing or not a single stack", ERROR_ITEMS)
        val stackUid = stacks[0][0].long
        val owned = stacks[0][2].long
        if (owned < perPress) throw GodSkillRejected("Not enough Astral Stones for one press", ERROR_ITEMS)
        var presses = 0L
        var consumed = 0L
        var newSkill = skillId
        var stopped = "times"
        while (presses < times) {
            if (owned - consumed < perPress) {
                stopped = "stones_exhausted_policy"
                break
            }
            consumed += perPress
            presses += 1
            progress += 1
            if (progress >= need) {
                newSkill = skillId + 1
                progress = 0
                stopped = "level_up"
                break
            }
        }
        val remaining = owned - consumed
        val packet = HeroEvolution.stackPacket(stackUid, remaining)
        val newSkills = entry.arr("skills").map { val (s, p) = it.asArr.map { v -> v.long }; if (s == skillId) listOf(newSkill, progress) else listOf(s, p) }
            .sortedWith { a, b -> val c = a[0].compareTo(b[0]); if (c != 0) c else a[1].compareTo(b[1]) }
        val newSkillsJson = JArr(newSkills.mapTo(ArrayList()) { jarr(it[0], it[1]) })
        val heroes = JArr(document.arr("heroes").mapTo(ArrayList()) { h ->
            val hero = h.asObj
            if (hero["uid"] == JInt(heroUid)) JObj(LinkedHashMap(hero.map)).also { it["skills"] = newSkillsJson } else hero
        })
        val documentAfter = JObj(LinkedHashMap(document.map)).also { it["heroes"] = heroes }
        return Plan(jobj("hero_uid" to heroUid, "skill_before" to skillId, "progress_before" to current.getValue(skillId),
            "skill_after" to newSkill, "progress_after" to progress, "times" to times, "presses" to presses,
            "stopped" to stopped, "item" to row["item_109"], "per_press" to row["per_press_110"],
            "item_change" to jobj("uid" to stackUid, "template" to row["item_109"], "quantity" to consumed, "remaining" to remaining),
            "skills_after" to newSkillsJson, "document_after" to documentAfter,
            "row_before" to JObj(LinkedHashMap(row.map)), "row_after" to JObj(LinkedHashMap((rows[newSkill.toString()] as JObj).map)),
            "evidence_class" to (if (stopped != "stones_exhausted_policy") EVIDENCE_CLASS else "preservation_policy_local")), listOf(packet))
    }

    /** Observed live order: S68/66 (the Astral Stone stack) then S2850 (the hero's whole list). */
    fun upgradePackets(plan: Plan): List<Frame> =
        listOf(plan.packets[0], UPDATE_OPCODE to encodeGodSkillUpdate(plan.data.long("hero_uid"), plan.data.arr("skills_after").map { it.asArr }))
}

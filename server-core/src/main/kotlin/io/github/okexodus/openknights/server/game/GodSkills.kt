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
}

/** The unequipped-jewelry list of `equip_formation.py` (opcode 3072 and its stored document). */
object EquipFormation {
    const val S_JEWEL_LIST_REMOVE = 3074
    const val S_JEWEL_LIST_ADD = 3076
    const val S_SET_JEWEL = 3082
    const val S_JEWEL_LIST = 3072
    const val JEWEL_LIST_PROFILE = "jewelry_unequipped_list_v1"

    /** One 22-byte record `<IIIIBBI`: uid, config, level, exp, grade, flag, extra. */
    fun record(w: WireWriter, r: JArr) {
        w.u32(r[0].long).u32(r[1].long).u32(r[2].long).u32(r[3].long).u8(r[4].long.toInt()).u8(r[5].long.toInt()).u32(r[6].long)
    }

    fun jewelListPayload(records: List<JArr>): ByteArray {
        val w = WireWriter().u32(records.size.toLong())
        records.forEach { record(w, it) }
        return w.bytes()
    }

    fun jewelryListPayloadOf(document: JObj): ByteArray = jewelListPayload(document.arr("entries").map { it.asObj.arr("record") })

    /** `decode_jewel_list`: u32 n, n x 22-byte records. */
    fun decodeJewelList(payload: ByteArray): List<JArr> {
        require(payload.size >= 4) { "Jewelry list is truncated" }
        val r = io.github.okexodus.openknights.protocol.WireReader(payload)
        val n = r.u32()
        require(payload.size.toLong() == 4 + 22 * n) { "Jewelry list length does not match its count" }
        return (0 until n).map { JArr(mutableListOf(JInt(r.u32()), JInt(r.u32()), JInt(r.u32()), JInt(r.u32()), JInt(r.u8()), JInt(r.u8()), JInt(r.u32()))) }
    }

    /** The stored list seeded from exactly the payload the session served (`seed_jewelry_document`). */
    fun seedJewelryDocument(served: ByteArray, source: String): JObj {
        val records = decodeJewelList(served)
        if (records.map { it[0].long }.toSet().size != records.size) throw IllegalArgumentException("Served jewelry list has duplicate uids")
        return jobj("profile" to JEWEL_LIST_PROFILE, "seeded_from" to jobj("source" to source, "payload_sha256" to sha256Hex(served), "records" to records.size),
            "entries" to records.sortedBy { it[0].long }.map { jobj("record" to it, "tail" to null) })
    }

    /** `write_jewelry_list`: sorted compact JSON, its SHA-256 over the stored text. */
    fun writeJewelryList(db: io.github.okexodus.openknights.server.store.SqlConnection, document: JObj): String {
        val text = Json.dumps(document, sortKeys = true, itemSeparator = ",", keySeparator = ":")
        val checksum = sha256Hex(text.toByteArray(Charsets.UTF_8))
        db.execute("CREATE TABLE IF NOT EXISTS jewelry_list(id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)")
        db.execute("INSERT INTO jewelry_list VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET document_json=excluded.document_json, document_sha256=excluded.document_sha256", text, checksum)
        return checksum
    }
}

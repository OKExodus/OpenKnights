package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.PyText
import io.github.okexodus.openknights.exact.Utf8Lenient
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.WireReader
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant

/**
 * Mail (`mail.py`): system / personal / guild mail, rewards, the mail blacklist. Mailboxes live in the world document
 * `mail` per wire role id; a reward is claimed into the owner's save by one audited transaction whose per-character
 * ledger (`mail_state.claimed`) makes a claim idempotent even if the world write after it has to be retried. Mail ids
 * come from one world counter.
 */
object Mail {
    const val C_LIST = 257
    const val C_READ = 195
    const val C_CLAIM = 197
    const val C_DELETE = 199
    const val C_WRITE = 201
    const val C_BLACKLIST = 203
    const val C_BLOCK = 205
    const val C_UNBLOCK = 207
    const val C_ATTACK = 209
    const val C_GUILD_MAIL = 2169
    const val S_LIST = 256
    const val S_ADD = 258
    const val S_STATE = 260
    const val S_REMOVE = 262
    const val S_CONTENT = 264
    const val S_REWARD = 266
    const val S_SEND_RESULT = 268
    const val S_BLACKLIST = 270
    const val S_REMOVED = 272
    const val S_GUILD_SEND_RESULT = 2316
    const val SYSTEM_REWARD = 0L
    const val PERSONAL = 1L
    const val ATTACK = 2L
    const val ABSOLVE = 3L
    const val PRAISE = 4L
    const val NOTE = 5L
    const val GUILD = 6L
    const val UNREAD = 0L
    const val READ = 1L
    const val CLAIMED = 2L
    const val MAIL_PROFILE = "mail_state_v1"
    const val ERR_NOT_FOUND = 9000
    const val ERR_CLAIMED = 9002
    const val ERR_BAG_FULL = 9003
    const val ERR_CLAIM_FIRST = 9004
    /** The write panel's own limits ("Up to 20 characters" / "…140 characters"). */
    const val TITLE_MAX = 20L
    const val BODY_MAX = 140L
    /** "kept for a maximum of 30 days". */
    const val KEEP_SECONDS = 30 * 86_400L
    /** S256 carries the 50 oldest unclaimed type-0 mails and all others. */
    const val LIST_SYSTEM_LIMIT = 50
    /** The most one S256 list can carry (u8 count). */
    const val BOX_LIMIT = 255

    /** STC_MAIL_BRIEF: `u32 id, u8 type, u32 sender id, u32 time, u8 state, cstr sender, cstr title` (time on the viewer's clock). */
    fun brief(mail: JObj, offset: Long = 0): ByteArray =
        WireWriter().u32(mail.long("id")).u8(mail.long("type").toInt()).u32(mail.long("sender")).u32(maxOf(0, mail.long("at") + offset))
            .u8(mail.long("state").toInt()).raw(mail.str("sender_name_hex").hexBytes()).raw(byteArrayOf(0))
            .raw(mail.str("title_hex").hexBytes()).raw(byteArrayOf(0)).bytes()

    /** The mailbox of a participant, expired mail dropped (30 days). */
    fun box(document: JObj, role: Long, now: Long): List<JObj> =
        ((document.obj("boxes")[role.toString()] as? JArr) ?: JArr()).map { it as JObj }.filter { now - it.long("at") < KEEP_SECONDS }

    /** The 50 oldest unclaimed type-0 mails and every other mail, by id. */
    fun visible(mails: List<JObj>): List<JObj> {
        val system = mails.filter { it.long("type") == SYSTEM_REWARD }.sortedBy { it.long("id") }.take(LIST_SYSTEM_LIMIT)
        val keep = system.map { it.long("id") }.toSet()
        return mails.filter { it.long("type") != SYSTEM_REWARD || it.long("id") in keep }.sortedBy { it.long("id") }
    }

    fun listPayload(document: JObj, role: Long, now: Long, claimed: Collection<Long> = emptyList(), offset: Long = 0): ByteArray {
        val skip = claimed.toSet()
        val mails = visible(box(document, role, now)).filter { it.long("id") !in skip }.take(255)
        val w = WireWriter().u8(mails.size)
        for (m in mails) w.raw(brief(m, offset))
        return w.bytes()
    }

    fun hasReward(reward: JValue?): Boolean {
        if (!Py.truthy(reward)) return false
        return (reward as JObj).any { (k, v) -> k != "version" && Py.truthy(v) }
    }

    /** Reward scalars → role property (grant order of the live claim S128: Gold 6 before Exploit 7). */
    val ROLE_OF: Map<String, Long> = linkedMapOf("exp" to 4L, "gold" to 6L, "exploit" to 7L, "diamond" to 8L, "stamina" to 9L,
        "energy" to 10L, "friend_point" to 11L, "reputation" to 12L, "donation" to 30L)

    private val GRANTED_SCALARS = listOf("gold", "exploit", "diamond", "stamina", "energy", "friend_point", "reputation", "donation")

    /**
     * Pay a Reward v14 into the save (`grant`): item stacks (S68 / S64 each), heroes and equipment through the
     * acquisition grants, then one S128 with every scalar that changed.
     */
    fun grant(owned: Owned, reward: JObj, @Suppress("UNUSED_PARAMETER") inputs: AcquisitionInputs): List<Frame> {
        val frames = ArrayList<Frame>()
        for (pair in (reward["items"] as? JArr) ?: JArr()) {
            val template = pair.asArr[0]
            val count = pair.asArr[1]
            if (Py.truthy(count)) frames.add(owned.grantItem(PyDocs.long(template), PyDocs.long(count)))
        }
        for (entry in (reward["heroes"] as? JArr) ?: JArr()) {
            val template = if (entry is JArr) entry[0] else entry
            val groups = owned.grantHero(PyDocs.long(template)).second
            for (group in listOf("add", "book", "god", "activity")) frames.addAll(groups.getValue(group))
        }
        for (entry in (reward["equips"] as? JArr) ?: JArr()) {
            val template = if (entry is JArr) entry[0] else entry
            val groups = owned.grantEquipment(PyDocs.long(template)).second
            for (group in listOf("add", "book")) frames.addAll(groups.getValue(group))
        }
        val fields = ArrayList<Long>()
        for (name in GRANTED_SCALARS) {
            val value = reward[name]
            if (Py.truthy(value)) {
                owned.roleAdd(ROLE_OF.getValue(name), PyDocs.int(value))
                fields.add(ROLE_OF.getValue(name))
            }
        }
        if (fields.isNotEmpty()) {
            frames.add(Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) }))
        }
        return frames
    }

    /** Eviction order: mail without a claimable Reward first, then praise mail; other Reward mail is never evicted. */
    private fun evictable(mail: JObj): Int? {
        if (!hasReward(mail["reward"])) return 0
        return if (mail.long("type") == PRAISE) 1 else null
    }

    /**
     * Put one mail in a participant's box (world document edit); returns it. Mail past the keep time is dropped and a
     * box over [BOX_LIMIT] drops its oldest evictable mail.
     */
    fun newMail(document: JObj, recipient: Long, type: Long, sender: Long, senderName: ByteArray, title: ByteArray, body: ByteArray,
                reward: JValue? = null, now: Long): JObj {
        val mailId = document.long("next_id")
        document["next_id"] = JInt(mailId + 1)
        val mail = jobj("id" to mailId, "type" to type, "sender" to sender, "sender_name_hex" to senderName.toHexString(),
            "title_hex" to title.toHexString(), "body_hex" to body.toHexString(), "reward" to jvalue(reward), "at" to now, "state" to UNREAD)
        var mails = ((document.obj("boxes")[recipient.toString()] as? JArr) ?: JArr()).map { it as JObj }
            .filter { now - it.long("at") < KEEP_SECONDS } + mail
        while (mails.size > BOX_LIMIT) {
            val ranked = mails.dropLast(1).mapNotNull { m -> evictable(m)?.let { Triple(it, m.long("at"), m.long("id")) } }
            if (ranked.isEmpty()) break
            val oldest = ranked.minWith(compareBy<Triple<Int, Long, Long>> { it.first }.thenBy { it.second }.thenBy { it.third }).third
            mails = mails.filter { it.long("id") != oldest }
        }
        document.obj("boxes")[recipient.toString()] = JArr(mails.toMutableList<JValue>())
        return mail
    }

    private fun boxOf(document: JObj, role: Long): JArr = (document.obj("boxes")[role.toString()] as? JArr) ?: JArr()

    fun find(document: JObj, role: Long, mailId: Long): JObj? = boxOf(document, role).map { it as JObj }.firstOrNull { it["id"] == JInt(mailId) }

    /** S264 `u32 id, cstr body, Reward` (the Reward the claim will pay). */
    fun contentPayload(mail: JObj, hideReward: Boolean = false): ByteArray {
        val stored = mail["reward"]
        val reward = if (hideReward) Acquisition.emptyReward() else if (Py.truthy(stored)) stored as JObj else Acquisition.emptyReward()
        return WireWriter().u32(mail.long("id")).raw(mail.str("body_hex").hexBytes()).raw(byteArrayOf(0)).raw(BattleReport.encodeReward(reward)).bytes()
    }

    /** C195 `u32 id` → S260 `id, 1` then S264. Unknown id → 9000. */
    fun read(document: JObj, role: Long, mailId: Long, hideReward: Boolean = false): List<Frame> {
        val mail = find(document, role, mailId) ?: throw Acquisition.Rejected("That specific Mail can't be found", ERR_NOT_FOUND)
        if (mail["state"] == JInt(UNREAD)) mail["state"] = JInt(READ)
        return listOf(S_STATE to WireWriter().u32(mailId).number('B', mail["state"]!!).bytes(), S_CONTENT to contentPayload(mail, hideReward))
    }

    /**
     * The id list [key] ("claimed" / "praise_paid") of a character's mail ledger (`ledger_ids`): empty when the ledger or
     * the list is absent (unless [required]); a malformed ledger (not an object, a list that is not a list of ids, a
     * missing required list) is refused cleanly (102).
     */
    fun ledgerIds(ledger: JValue?, key: String, required: Boolean = false): JArr {
        val doc = if (Py.truthy(ledger)) ledger as? JObj ?: throw Acquisition.Rejected("Malformed mail ledger", Acquisition.ERROR_INVALID) else JObj()
        val ids = doc[key] ?: if (required) throw Acquisition.Rejected("Malformed mail ledger", Acquisition.ERROR_INVALID) else return JArr()
        if (ids !is JArr || ids.any { it !is JInt }) throw Acquisition.Rejected("Malformed mail ledger", Acquisition.ERROR_INVALID)
        return ids
    }

    /** The (claimed, praise_paid) lists of a mail ledger, both checked (`checked_ledger`). */
    fun checkedLedger(ledger: JValue?): Pair<List<Long>, List<Long>> =
        ledgerIds(ledger, "claimed").map { (it as JInt).toLong() } to ledgerIds(ledger, "praise_paid").map { (it as JInt).toLong() }

    /** `sorted(set(ids) | {id})[-2000:]`. */
    private fun withId(ids: JArr, mailId: JValue): JArr {
        val all = LinkedHashSet<JValue>(ids)
        all.add(mailId)
        val sorted = PyDocs.sorted(all)
        return JArr(sorted.subList(maxOf(0, sorted.size - 2000), sorted.size).toMutableList())
    }

    /** A "Praise from a Friend" mail (type 4) whose Pal Points were not paid yet. */
    fun praiseUnpaid(mail: JObj?, ledger: JValue?): Boolean {
        if (mail == null || mail["type"] != JInt(PRAISE) || !hasReward(mail["reward"])) return false
        return mail["id"] !in ledgerIds(ledger, "praise_paid").toSet()
    }

    /** A praise mail whose Pal Points were paid when it was opened: it counts as claimed (`praise_paid`). */
    fun praisePaid(mail: JObj?, ledger: JValue?): Boolean =
        mail != null && mail["type"] == JInt(PRAISE) && mail["id"] in ledgerIds(ledger, "praise_paid").toSet()

    /** `dict(ledger or {"profile": MAIL_PROFILE, "claimed": []})`. */
    private fun ledgerCopy(ledger: JValue?): JObj = if (Py.truthy(ledger)) PyDocs.shallow(ledger as JObj) else jobj("profile" to MAIL_PROFILE, "claimed" to JArr())

    /**
     * POLICY (the Personal tab has no Claim button): a praise mail's Pal Points are paid when it is opened (S128 only);
     * its content then shows no Reward and Praise back / Delete remove it plainly. Plan members: mail, reward,
     * mail_state_after.
     */
    fun planPraiseRead(mail: JObj, owned: Owned, inputs: AcquisitionInputs, ledger: JValue?): Plan {
        val paid = ledgerIds(ledger, "praise_paid")
        val after = ledgerCopy(ledger)
        val frames = grant(owned, mail["reward"] as JObj, inputs)
        after["praise_paid"] = withId(paid, mail["id"]!!)
        return Plan(jobj("mail" to mail["id"], "reward" to mail["reward"], "mail_state_after" to after), frames)
    }

    /**
     * C197 `u32 id` → [S68 item] [S128 props] → S266 Reward → S262 id. The ledger records the claim; a mail claimed
     * before, or a praise mail paid when it was opened, is refused (9002).
     */
    fun planClaim(mail: JObj, owned: Owned, inputs: AcquisitionInputs, ledger: JValue?): Plan {
        val claimed = ledgerIds(ledger, "claimed", required = Py.truthy(ledger))
        val after = ledgerCopy(ledger)
        if (mail["id"] in claimed || praisePaid(mail, after)) throw Acquisition.Rejected("The Mail Reward has been claimed", ERR_CLAIMED)
        val stored = mail["reward"]
        val reward = if (Py.truthy(stored)) stored as JObj else Acquisition.emptyReward()
        val frames = if (hasReward(reward)) grant(owned, reward, inputs) else emptyList()
        after["claimed"] = withId(claimed, mail["id"]!!)
        return Plan(jobj("mail" to mail["id"], "reward" to reward, "mail_state_after" to after),
            frames + listOf(S_REWARD to BattleReport.encodeReward(reward), S_REMOVE to WireWriter().u32(mail.long("id")).bytes()))
    }

    fun remove(document: JObj, role: Long, mailId: Long) {
        document.obj("boxes")[role.toString()] = JArr(boxOf(document, role).filterTo(ArrayList()) { (it as JObj)["id"] != JInt(mailId) })
    }

    /** C199 `u32 id` → S262 (9004 for an unclaimed reward mail; 9000 unknown). */
    fun delete(document: JObj, role: Long, mailId: Long, claimed: Collection<Long> = emptyList()): List<Frame> {
        val mail = find(document, role, mailId) ?: throw Acquisition.Rejected("That specific Mail can't be found", ERR_NOT_FOUND)
        if (hasReward(mail["reward"]) && mail["id"] !in claimed.mapTo(HashSet<JValue>()) { JInt(it) }) {
            throw Acquisition.Rejected("You have to claim the Reward first", ERR_CLAIM_FIRST)
        }
        remove(document, role, mailId)
        return listOf(S_REMOVE to WireWriter().u32(mailId).bytes())
    }

    private fun blacklistOf(document: JObj, role: Long): JArr = (document.obj("blacklist")[role.toString()] as? JArr) ?: JArr()

    /** S270 `u8 n, n × cstr`. */
    fun blacklistPayload(document: JObj, role: Long): ByteArray {
        val names = blacklistOf(document, role)
        val w = WireWriter().raw(PyDocs.bytes(listOf(names.size.toLong())))
        for (n in names) w.raw((n as JStr).value.hexBytes()).raw(byteArrayOf(0))
        return w.bytes()
    }

    /** The names (hex) a participant has blocked — the list every delivery checks (`blocked_names`). */
    fun blockedNames(document: JObj, role: Long): List<String> = blacklistOf(document, role).map { (it as JStr).value }

    /** A blacklist entry: the name bytes as hex. */
    fun nameHex(nameRaw: ByteArray): JStr = JStr(nameRaw.toHexString())

    fun blocked(document: JObj, role: Long, nameRaw: ByteArray): Boolean = nameHex(nameRaw) in blacklistOf(document, role)

    /**
     * C201 `cstr to, cstr title, cstr body` → S268 result: 0 delivered, 1 receiver not found, 2 title too long, 3 too
     * much content, 4 on their blacklist. Limits: the write panel's 20 / 140 characters (POLICY). Returns (code, mail).
     */
    fun write(document: JObj, sender: Participant?, recipient: Participant?, title: ByteArray, body: ByteArray, inputs: DailyInputs,
              now: Long): Pair<Int, JObj?> {
        if (recipient == null) return 1 to null
        if (PyText.length(Utf8Lenient.decodeReplace(title)) > maxOf(TITLE_MAX, inputs.prop(700, 10))) return 2 to null
        if (PyText.length(Utf8Lenient.decodeReplace(body)) > maxOf(BODY_MAX, inputs.prop(701, 50))) return 3 to null
        if (blocked(document, recipient.participantId, sender!!.nameRaw)) return 4 to null
        val mail = newMail(document, recipient.participantId, PERSONAL, sender.participantId, sender.nameRaw, title, body, now = now)
        return 0 to mail
    }

    fun decodeId(payload: ByteArray, opcode: Int): Long {
        if (payload.size != 4) throw Acquisition.Rejected("C$opcode is u32 mail id")
        return WireReader(payload).u32()
    }

    /** `payload.split(b"\0")`. */
    fun splitBytes(payload: ByteArray): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        var start = 0
        for (i in payload.indices) {
            if (payload[i] == 0.toByte()) {
                parts.add(payload.copyOfRange(start, i))
                start = i + 1
            }
        }
        parts.add(payload.copyOfRange(start, payload.size))
        return parts
    }

    /** [count] NUL-terminated strings, nothing after the last NUL. */
    fun decodeStrings(payload: ByteArray, count: Int, opcode: Int): List<ByteArray> {
        val parts = splitBytes(payload)
        if (parts.size != count + 1 || parts.last().isNotEmpty()) throw Acquisition.Rejected("C$opcode is $count cstring(s)")
        return parts.subList(0, count)
    }
}

/** Summon Report (`summon_reports.py`): the login S672 with the world's newest records. */
object SummonReports {
    const val S_REPORT = 672
    const val LOGIN_ENTRIES = 3

    /** S672 `u8 n, n x (u32 role, cstring name, u32 hero, u32 time)`; times on the requester's clock. */
    fun reportPayload(entries: List<JObj>, clockOffset: Long = 0): ByteArray {
        val w = WireWriter().u8(entries.size)
        for (e in entries) {
            val raw = e.str("name_hex").hexBytes()
            val name = raw.indexOf(0.toByte()).let { if (it < 0) raw else raw.copyOfRange(0, it) }
            w.u32(e.long("role")).raw(name).raw(byteArrayOf(0)).u32(e.long("hero")).u32(maxOf(0, e.long("at") + clockOffset) and 0xFFFFFFFFL)
        }
        return w.bytes()
    }

    /** The login S672 (the newest [LOGIN_ENTRIES], oldest first) or null when the world has none yet. */
    fun loginPayload(document: JObj?, clockOffset: Long = 0): ByteArray? {
        val all = ((document ?: JObj())["entries"] as? JArr)?.map { it as JObj } ?: emptyList()
        val entries = all.takeLast(LOGIN_ENTRIES)
        return if (entries.isNotEmpty()) reportPayload(entries, clockOffset) else null
    }

    const val S_MARQUEE = 768
    /** hero.csv col 104 (5- and 6-star draws reported, 4-star not). */
    const val MIN_STAR = 5L
    /** The LotSystem list size (native). */
    const val MAX_ENTRIES = 8
    const val MARQUEE_TEXT = 801L
    const val PROFILE = "summon_reports_world_v1"

    /** `qualifying(templates, inputs)`: the drawn heroes the report lists, in the S352 order. */
    fun qualifying(templates: List<Long>, inputs: AcquisitionInputs): List<Long> = templates.filter { (inputs.heroStar(it) ?: 0L) >= MIN_STAR }

    fun entry(role: Long, nameRaw: ByteArray, hero: Long, at: Long): JObj =
        jobj("role" to role, "name_hex" to nameRaw.toHexString(), "hero" to hero, "at" to at)

    /** World-document change: the new records at the end, the newest [MAX_ENTRIES] kept. */
    fun append(document: JObj, entries: List<JObj>): JArr {
        val all = (((document["entries"] as? JArr) ?: JArr()).toList() + entries).takeLast(MAX_ENTRIES)
        document["entries"] = JArr(all.toMutableList())
        return document.arr("entries")
    }

    /** S768: text 801 with the player's name, the hero's star and its name, NUL-terminated. */
    fun marqueePayload(nameRaw: ByteArray, hero: Long, inputs: DailyInputs): ByteArray {
        val text = inputs.text(MARQUEE_TEXT).ifEmpty { "##0## summoned a ##1##-Star Hero, ##2##." }
        val cut = nameRaw.indexOf(0.toByte()).let { if (it < 0) nameRaw else nameRaw.copyOfRange(0, it) }
        val name = io.github.okexodus.openknights.exact.Utf8Lenient.decodeReplace(cut)
        val heroName = inputs.heroName(hero)
        val line = text.replace("##0##", name).replace("##1##", (inputs.heroStar(hero) ?: 0L).toString())
            .replace("##2##", heroName.ifEmpty { hero.toString() })
        return line.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }

    /** Per record: S672 n = 1, then its S768 marquee (the live tail after a summon). */
    fun summonFrames(entries: List<JObj>, inputs: DailyInputs, clockOffset: Long = 0): List<Frame> =
        entries.flatMap { e -> listOf(S_REPORT to reportPayload(listOf(e), clockOffset), S_MARQUEE to marqueePayload(e.str("name_hex").hexBytes(), e.long("hero"), inputs)) }

    /** Store the summons of one world participant (character or bot); returns the new records. */
    fun record(world: io.github.okexodus.openknights.server.store.WorldDirectory?, role: Long, nameRaw: ByteArray, heroes: List<Long>, now: Long,
               actor: String = "local-service"): List<JObj> {
        val entries = heroes.map { entry(role, nameRaw, it, now) }
        if (entries.isNotEmpty() && world != null) {
            world.updateDocument("summon_reports", actor, "summon_report") { d -> append(d, entries) to jobj("role" to role, "heroes" to heroes) }
        }
        return entries
    }
}

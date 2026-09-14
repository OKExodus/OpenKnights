package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireWriter

/**
 * Mail (`mail.py`), the parts entering the game reads: the mailbox list (S256) and the brief of one mail, and putting
 * a mail in a box (the guild war result at login). Mailboxes live in the world document `mail` per wire role id.
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
    const val S_LIST = 256
    const val S_ADD = 258
    const val SYSTEM_REWARD = 0L
    const val PRAISE = 4L
    const val GUILD = 6L
    const val UNREAD = 0L
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
}

/** Chat (`chat.py`), the part entering the game reads: the history replayed after the mail list. */
object Chat {
    const val C_CHAT = 449
    const val S_CHAT = 480
    const val PRIVATE = 2L

    fun linePayload(channel: Long, outgoing: Boolean, senderId: Long, senderRaw: ByteArray, textRaw: ByteArray, gm: Boolean = false): ByteArray =
        WireWriter().u8(channel.toInt()).u8(if (outgoing) 1 else 0).u32(senderId).raw(senderRaw).raw(byteArrayOf(0))
            .raw(textRaw).raw(byteArrayOf(0)).u8(if (gm) 1 else 0).bytes()

    /** S480 of a stored line for a viewer (private lines: outgoing from the viewer's side shows the partner). */
    fun replay(line: JObj, viewer: Long): Frame {
        val gm = Py.truthy(line["gm"])
        if (line.long("channel") == PRIVATE && line.long("sender") == viewer) {
            val target = line.obj("target")
            return S_CHAT to linePayload(PRIVATE, true, target.long("id"), target.str("name_hex").hexBytes(), line.str("text_hex").hexBytes(), gm)
        }
        return S_CHAT to linePayload(line.long("channel"), false, line.long("sender"), line.str("name_hex").hexBytes(), line.str("text_hex").hexBytes(), gm)
    }

    /** Lines replayed after the mail list at login: the world history, the guild's history, the private lines to the viewer. */
    fun loginHistory(chatDoc: JObj, viewer: Long, guildId: Long): List<Frame> {
        val frames = ArrayList<Frame>()
        chatDoc.arr("world").forEach { frames.add(replay(it as JObj, viewer)) }
        if (guildId != 0L) ((chatDoc.obj("guild")[guildId.toString()] as? JArr) ?: JArr()).forEach { frames.add(replay(it as JObj, viewer)) }
        ((chatDoc.obj("private")[viewer.toString()] as? JArr) ?: JArr()).forEach { frames.add(replay(it as JObj, viewer)) }
        return frames
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

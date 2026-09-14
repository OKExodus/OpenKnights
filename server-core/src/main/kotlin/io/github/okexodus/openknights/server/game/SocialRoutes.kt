package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.math.BigInteger

/**
 * Request dispatch of the social layer (`social_routes.py`): guild, friends, chat, mail. World-level state lives in
 * the world database's documents and changes through [SocialContext.update] (optimistic, audited). Ported so far:
 * the parts entering the game runs (the login frames, the friends refresh data, presence) and the three
 * initialization queries it answers again later (C2145 my guild, C353 pending friends, C257 mail list).
 */
object SocialRoutes {
    private fun role(current: StateStore.Current, fieldId: Int, default: Long = 0): Long {
        for (f in current.state.arr("role_properties")) {
            val o = f as JObj
            if (o.long("id") == fieldId.toLong()) return o.obj("value").longOrNull("bits") ?: default
        }
        return default
    }

    fun roleOf(current: StateStore.Current): Long = role(current, 0)

    /** World access for one request: documents, participants (characters and bots), presence and pushes. */
    class SocialContext(
        val world: WorldDirectory?,
        val registry: AccountRegistry?,
        val inputs: DailyInputs?,
        val bots: List<Participant> = emptyList(),
        private val pushFn: ((Long, (Long) -> List<Frame>) -> Unit)? = null,
        val actor: String = "local-service",
        /** The requester's clock: S14 at login − service time at login (0 once the release runs on the device clock). */
        val clockOffset: Long = 0,
        private val powerOf: ((StateStore.Current) -> BigInteger?)? = null,
        /** The participant list, when already known (the reference's `_people`; vector replays pass a recorded one). */
        private var people: Map<Long, Participant>? = null,
    ) {

        fun document(name: String): JObj? {
            if (world == null) {
                val born = WorldDirectory.WORLD_DOCUMENTS.firstOrNull { it.first == name }?.second ?: throw NoSuchElementException(name)
                return Json.loads(Json.dumps(born)) as JObj
            }
            return world.document(name)?.second
        }

        fun <T> update(name: String, action: String, change: (JObj) -> Pair<T, JObj?>): T {
            val world = world ?: throw Acquisition.Rejected("The social layer needs the shared world (--world)")
            return world.updateDocument(name, actor, action, change = change)
        }

        /** {role: Participant} of every world participant (Power: the universal Power; bots keep their roster power). */
        fun people(current: StateStore.Current? = null): Map<Long, Participant> {
            people?.let { return it }
            val power = powerOf ?: world?.powerOf ?: throw NotPorted("battle_stats.participant_power without a bound world Power")
            val listed: List<Participant> = if (world == null || registry == null) {
                val own = if (current != null) listOf(WorldParticipants.characterParticipant(jobj("character_id" to null, "created_at_utc" to ""), current, power))
                else emptyList()
                if (world != null && bots.isNotEmpty()) throw NotPorted("prestige.with_progress (bot participants)")
                own
            } else WorldParticipants.worldParticipants(world, registry, power, bots)
            val out = LinkedHashMap<Long, Participant>()
            for (p in listed) out[p.participantId] = p
            people = out
            return out
        }

        fun presence(): JObj = document("presence")!!.obj("players")

        /** Frames for another participant's open session(s), built against each recipient's own clock offset. */
        fun push(role: Long, frames: (Long) -> List<Frame>) {
            pushFn?.invoke(role, frames)
        }
    }

    private fun guildDoc(ctx: SocialContext): JObj = ctx.document("guilds")!!

    fun myGuild(ctx: SocialContext, role: Long): Pair<Long, JObj?> = Guild.guildOf(guildDoc(ctx), role)

    fun myGuildFrame(ctx: SocialContext, role: Long, now: Long, current: StateStore.Current? = null): Frame =
        Guild.S_MY_GUILD to Guild.myGuildPayload(guildDoc(ctx), role, ctx.people(current), ctx.inputs!!, now)

    private fun guildRequired(ctx: SocialContext, role: Long): Pair<Long, JObj> {
        val (gid, guild) = myGuild(ctx, role)
        if (guild == null || !Py.truthy(guild)) throw Acquisition.Rejected("Not in a Guild yet", Guild.ERR_NOT_IN_GUILD)
        return gid to guild
    }

    const val GUILD_SHOP = 9L

    /**
     * C75 of a Guild Shop commodity (shop type 9): only a guild member whose guild reached the commodity's unlock level
     * (juntuan_dengji 111) may buy. The client already hides the request below the level, so the server errors are
     * POLICY: not a member 52002, level too low 52009.
     */
    fun guildShopGate(current: StateStore.Current, ctx: SocialContext, record: JObj?) {
        if (record == null || record["type"] != JInt(GUILD_SHOP)) return
        val (_, guild) = guildRequired(ctx, roleOf(current))
        val unlock = ctx.inputs!!.guildShopUnlock(PyDocs.long(PyDocs.at(record, "id")))
        if (unlock == null || Guild.levelOf(guild, ctx.inputs) < unlock) {
            throw Acquisition.Rejected("Unlock when your Guild reaches the level", Guild.ERR_NO_ACCESS)
        }
    }

    val GUILD_QUERIES = listOf(Guild.C_MY_GUILD, Guild.C_MEMBERS, Guild.C_GUILD_LIST, Guild.C_APPLICANTS, Guild.C_POSITIONS,
        Guild.C_TECH_LIST, Guild.C_ACTIVITY, Guild.C_BOSS, Guild.C_OTHER, Guild.C_TASKS)
    val GUILD_WORLD = listOf(Guild.C_APPLY, Guild.C_APPROVE, Guild.C_KICK, Guild.C_POSITION_APPLY, Guild.C_TRANSFER, Guild.C_QUIT,
        Guild.C_TECH_UP, Guild.C_NOTICE, Guild.C_WAR_SIGN, Guild.C_GUILD_MAIL)
    val GUILD_CHARACTER = listOf(Guild.C_CREATE, Guild.C_DONATE, Guild.C_WAGE, Guild.C_EMBLEM, Guild.C_RENAME, Guild.C_TASK_DONATE,
        Guild.C_TASK_REFRESH, Guild.C_TASK_ACCEPT, Guild.C_TASK_CLAIM)
    /** The Guild BOSS clear-CD / reset need a fought boss — nobody can fight it offline, so it is never on cooldown. */
    val GUILD_REFUSED: Map<Int, Int> = linkedMapOf(Guild.C_WAR_FIELD to Guild.ERR_WAR_CLOSED, Guild.C_BOSS_CD to Guild.ERR_BOSS_ALIVE,
        Guild.C_BOSS_RESET to Guild.ERR_BOSS_ALIVE)
    val FRIEND_OPCODES = listOf(Friends.C_PENDING, Friends.C_RECOMMEND, Friends.C_ADD, Friends.C_ADD_NAME, Friends.C_REPLY, Friends.C_REMOVE,
        Friends.C_PRAISE, Friends.C_INFO)
    val MAIL_OPCODES = listOf(Mail.C_LIST, Mail.C_READ, Mail.C_CLAIM, Mail.C_DELETE, Mail.C_WRITE, Mail.C_BLACKLIST, Mail.C_BLOCK, Mail.C_UNBLOCK)
    val OPCODES: List<Int> = GUILD_QUERIES + GUILD_WORLD + GUILD_CHARACTER + GUILD_REFUSED.keys + FRIEND_OPCODES + MAIL_OPCODES + Chat.C_CHAT

    /** `commit(action, planner)`: one audited acquisition transaction of the requesting character; returns its plan. */
    fun interface Commit {
        operator fun invoke(action: String, planner: (Owned, StateStore.Current) -> Plan): Plan
    }

    /** One social request (the game session's route): `dispatch`. */
    fun dispatch(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long,
                 servedTime: Long, ownerKey: String, online: List<Long> = emptyList()): List<Frame> {
        GUILD_REFUSED[opcode]?.let { throw Acquisition.Rejected("Not available offline", it) }
        if (opcode in GUILD_QUERIES || (opcode == Guild.C_TASK_REFRESH && payload.contentEquals(byteArrayOf(1)))) {
            if (opcode == Guild.C_TASK_REFRESH) return listOf(taskFrame(current, ctx, now, ownerKey))
            return query(opcode, payload, current, ctx, now, ownerKey)
        }
        if (opcode in GUILD_WORLD) return guildWorld(opcode, payload, current, ctx, now)
        if (opcode in GUILD_CHARACTER) return guildCharacter(opcode, payload, current, ctx, commit, now, servedTime, ownerKey)
        if (opcode in FRIEND_OPCODES) return friends(opcode, payload, current, ctx, commit, now)
        if (opcode in MAIL_OPCODES) return mail(opcode, payload, current, ctx, commit, now)
        if (opcode == Chat.C_CHAT) return chat(payload, current, ctx, now, online)
        throw Acquisition.Rejected("Not a social request")
    }

    // --- guild (agent A: query, taskFrame, guildWorld, guildCharacter and their helpers) ------------------------------

    /** Days the leader must be offline before the Leader seat can be applied for. */
    const val LEADER_AWAY_PROPERTY = 200004L

    /** The S2330 document of a character: its own levels, shown while in a world guild, capped by that guild. */
    private fun personalView(current: StateStore.Current, ctx: SocialContext, role: Long): JObj {
        val (_, guild) = myGuild(ctx, role)
        return Guild.personalTechView(PyDocs.obj(current, "guild_tech_state"), guild, ctx.inputs!!)
    }

    private fun personalFrame(current: StateStore.Current, ctx: SocialContext, role: Long): Frame =
        Guild.S_TECH_PERSONAL to Castle.guildTechPayload(personalView(current, ctx, role))

    /** `_page`: a `u32` page when the payload is 4 bytes, else page 1. */
    private fun page(payload: ByteArray): Long = if (payload.size == 4) io.github.okexodus.openknights.protocol.WireReader(payload).u32() else 1

    /** Frames for another participant built now (the reference's frame lists), whatever the recipient's clock. */
    private fun pushNow(ctx: SocialContext, role: Long, frames: List<Frame>) {
        ctx.push(role) { frames }
    }

    /** `(S_ROLE, role_update_payload([(f, tag, bits) for f in fields]))`. */
    private fun roleFrame(owned: Owned, fields: List<Long>): Frame =
        Acquisition.S_ROLE to Acquisition.roleUpdatePayload(fields.map { Triple(it, owned.role(it).long("tag"), owned.roleBits(it)) })

    private fun u32Pair(payload: ByteArray): Pair<Long, Long> =
        io.github.okexodus.openknights.protocol.WireReader(payload).let { it.u32() to it.u32() }

    /** The guild task list frame (`task_frame`): the board as it is today (renewed, not stored). */
    fun taskFrame(current: StateStore.Current, ctx: SocialContext, now: Long, ownerKey: String): Frame {
        val doc = Guild.taskDocument(PyDocs.get(current, "guild_task_state"), ctx.inputs!!, now, ownerKey)
        return Guild.S_TASKS to Guild.tasksPayload(doc, now)
    }

    /** The guild queries (`query`). */
    fun query(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long, ownerKey: String): List<Frame> {
        val role = roleOf(current)
        val doc = guildDoc(ctx)
        val people = ctx.people(current)
        when (opcode) {
            Guild.C_MY_GUILD -> return listOf(myGuildFrame(ctx, role, now, current))
            Guild.C_MEMBERS -> {
                if (payload.size != 8) throw Acquisition.Rejected("C2147 is u32 guild, u32 page")
                val (guildId, page) = u32Pair(payload)
                val guild = doc.obj("guilds")[guildId.toString()] as JObj? ?: throw Acquisition.Rejected("Cannot find the Guild", Guild.ERR_NO_GUILD)
                return listOf(Guild.S_MEMBERS to Guild.membersPayload(guildId, guild, page, people, ctx.inputs!!))
            }
            Guild.C_GUILD_LIST -> return listOf(Guild.S_GUILD_LIST to Guild.guildListPayload(doc, page(payload), role, people, ctx.inputs!!))
            Guild.C_APPLICANTS -> {
                val (_, guild) = guildRequired(ctx, role)
                return listOf(Guild.S_APPLICANTS to Guild.applicantsPayload(guild, page(payload), people, ctx.presence(), now, ctx.clockOffset))
            }
            Guild.C_POSITIONS -> {
                val (_, guild) = guildRequired(ctx, role)
                return listOf(Guild.S_POSITIONS to positionsPayload(guild, people))
            }
            Guild.C_TECH_LIST -> {
                val (_, guild) = guildRequired(ctx, role)
                return listOf(Guild.S_TECH_LIST to Guild.techListPayload(guild))
            }
            Guild.C_ACTIVITY -> {
                guildRequired(ctx, role)
                val own = warResults(ctx, role, now)
                val (_, guild) = guildRequired(ctx, role)
                return Guild.activityFrames(guild, now, ctx.inputs) + own
            }
            Guild.C_BOSS -> {
                val (_, guild) = guildRequired(ctx, role)
                return listOf(Guild.S_BOSS to Guild.bossPayload(guild, ctx.inputs!!))
            }
            Guild.C_OTHER -> {
                val guildId = Guild.decodeU32(payload, opcode)
                val guild = doc.obj("guilds")[guildId.toString()] as JObj? ?: throw Acquisition.Rejected("Cannot find the Guild", Guild.ERR_NO_GUILD)
                return listOf(Guild.S_OTHER to Guild.otherGuildPayload(guildId, guild, people, ctx.inputs!!))
            }
            Guild.C_TASKS -> return listOf(taskFrame(current, ctx, now, ownerKey))
        }
        throw Acquisition.Rejected("Not a social query")
    }

    /**
     * S2326 `u8 n, n × (u32 position, u8 m, m × (cstr name, u32 value))` — per position (Leader … Senior) its holders
     * and their accumulated contribution.
     */
    fun positionsPayload(guild: JObj, people: Map<Long, Participant>): ByteArray {
        val w = io.github.okexodus.openknights.protocol.WireWriter()
        var count = 0L
        for (position in Guild.POSITIONS.dropLast(1)) {
            val holders = guild.arr("members").map { it as JObj }.filter { it["position"] == JInt(position) }
            w.u32(position).raw(PyDocs.bytes(listOf(holders.size.toLong())))
            for (m in holders) {
                val person = people[m.long("role")]
                w.raw(person?.nameRaw ?: "?".toByteArray()).raw(byteArrayOf(0)).number('I', PyDocs.at(m, "contribution"))
            }
            count++
        }
        return PyDocs.bytes(listOf(count)) + w.bytes()
    }

    /** The world-only guild actions (`guild_world`). */
    fun guildWorld(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long): List<Frame> {
        val role = roleOf(current)
        val inputs = ctx.inputs!!
        val people = ctx.people(current)
        when (opcode) {
            Guild.C_APPLY -> {
                val guildId = Guild.decodeU32(payload, opcode)
                ctx.update<Unit>("guilds", "guild_apply") { d ->
                    Guild.apply(d, role, guildId, inputs, now)
                    Unit to jobj("guild" to guildId, "role" to role)
                }
                return listOf(Guild.S_APPLY to byteArrayOf(0))
            }
            Guild.C_APPROVE -> {
                if (payload.size != 5) throw Acquisition.Rejected("C2159 is u32 role, u8 action")
                val r = io.github.okexodus.openknights.protocol.WireReader(payload)
                val applicant = r.u32()
                val action = r.u8()
                ctx.update("guilds", "guild_decide") { d ->
                    Guild.decide(d, role, applicant, action == 1, inputs, now) to jobj("role" to role, "applicant" to applicant, "accept" to (action == 1))
                }
                if (action == 1) pushNow(ctx, applicant, listOf(myGuildFrame(ctx, applicant, now)))
                return listOf(Guild.S_APPROVE to io.github.okexodus.openknights.protocol.WireWriter().u32(applicant).u8(0).bytes())
            }
            Guild.C_KICK -> {
                val target = Guild.decodeU32(payload, opcode)
                ctx.update("guilds", "guild_kick") { d -> Guild.kick(d, role, target, inputs, now) to jobj("role" to role, "target" to target) }
                pushNow(ctx, target, listOf(myGuildFrame(ctx, target, now), Guild.S_TECH_PERSONAL to byteArrayOf(0)))
                return listOf(Guild.S_KICK to byteArrayOf(0))
            }
            Guild.C_QUIT -> {
                if (payload.isNotEmpty()) throw Acquisition.Rejected("C2173 has no payload")
                ctx.update("guilds", "guild_quit") { d -> Guild.quitGuild(d, role, now) to jobj("role" to role) }
                return listOf(Guild.S_QUIT to byteArrayOf(0), myGuildFrame(ctx, role, now, current), Guild.S_TECH_PERSONAL to byteArrayOf(0))
            }
            Guild.C_TRANSFER -> {
                val target = Guild.decodeU32(payload, opcode)
                ctx.update("guilds", "guild_transfer") { d -> Guild.transfer(d, role, target, inputs) to jobj("role" to role, "target" to target) }
                pushNow(ctx, target, listOf(myGuildFrame(ctx, target, now)))
                return listOf(myGuildFrame(ctx, role, now, current))
            }
            Guild.C_NOTICE -> {
                val notice = Guild.decodeCstrings(payload, 1, opcode)[0]
                ctx.update("guilds", "guild_notice") { d -> Guild.setNotice(d, role, notice, inputs) to jobj("role" to role) }
                return listOf(myGuildFrame(ctx, role, now, current))
            }
            Guild.C_POSITION_APPLY -> {
                val position = Guild.decodeU32(payload, opcode)
                val presence = ctx.presence()
                ctx.update("guilds", "guild_position") { d ->
                    applyPosition(d, role, position, inputs, presence, now) to jobj("role" to role, "position" to position)
                }
                return listOf(myGuildFrame(ctx, role, now, current),
                    Guild.S_POSITIONS to positionsPayload(Guild.guildOf(guildDoc(ctx), role).second ?: noneSubscript(), people))
            }
            Guild.C_TECH_UP -> {
                val tech = Guild.decodeU32(payload, opcode)
                ctx.update("guilds", "guild_tech") { d ->
                    val r = Guild.upgradeTech(d, role, tech, inputs)
                    r to jobj("role" to role, "tech" to tech, "cost" to r.second)
                }
                val (_, guild) = myGuild(ctx, role)
                for (member in (guild ?: noneSubscript()).arr("members")) {       // every member's personal caps follow
                    val other = (member as JObj).long("role")
                    if (other != role) pushNow(ctx, other, listOf(Guild.S_TECH_LIST to Guild.techListPayload(guild!!)))
                }
                return listOf(myGuildFrame(ctx, role, now, current), Guild.S_TECH_LIST to Guild.techListPayload(guild!!),
                    personalFrame(current, ctx, role))
            }
            Guild.C_WAR_SIGN -> {
                if (payload.isNotEmpty()) throw Acquisition.Rejected("C2185 has no payload")
                return warSign(current, ctx, role, now)
            }
            Guild.C_GUILD_MAIL -> {
                val (title, body) = Mail.decodeStrings(payload, 2, opcode)
                return guildMail(current, ctx, role, title, body, now)
            }
        }
        throw Acquisition.Rejected("Not a guild action")
    }

    /** The reference's TypeError of a missing guild record read as a dictionary. */
    private fun noneSubscript(): Nothing = throw PyDocs.TypeError("'NoneType' object is not subscriptable")

    /**
     * C2165 `u32 position` (the Position screen's Apply, enabled only when quanxian[position].111 is the applicant's own
     * position). Labeled policy: accumulated contribution ≥ quanxian 112 (52013) and a free seat — seats per position
     * from juntuan_dengji 104–109 at the guild level (52012); the Leader seat (quanxian 109) only when the leader has been
     * offline ≥ property 200004 (3) days (52034), the old leader then takes the applicant's former position.
     */
    fun applyPosition(document: JObj, role: Long, position: Long, inputs: DailyInputs, presence: JObj? = null, now: Long = 0): Long {
        val (gid, guild) = Guild.guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", Guild.ERR_NOT_IN_GUILD)
        val member = Guild.memberOf(guild, role)
        val row = inputs.guildPosition(position)
        if (row == null || member.long("position") <= position) throw Acquisition.Rejected("You already hold a higher position", Guild.ERR_POSITION_HIGHER)
        if (!Py.truthy(row["apply_from"]) || row["apply_from"] != member["position"]) throw Acquisition.Rejected("Cannot apply this position", Guild.ERR_POSITION_CANNOT)
        if (PyDocs.compare(PyDocs.at(member, "contribution"), PyDocs.at(row, "apply_contribution")) < 0) {
            throw Acquisition.Rejected("Not enough Accu. Contribution", Guild.ERR_CONTRIBUTION)
        }
        if (row.bool("leader_rule")) {
            val leader = Guild.leaderOf(guild)
            val seen = ((presence ?: JObj())[leader.long("role").toString()] ?: JObj()) as JObj
            val away = inputs.prop(LEADER_AWAY_PROPERTY, 3) * 86_400
            if (Py.truthy(seen["online"] ?: JBool(false)) || now - PyDocs.long(seen["last_seen"] ?: JInt(now)) < away) {
                throw Acquisition.Rejected("The leader cannot be changed", Guild.ERR_LEADER_STAYS)
            }
            val former = PyDocs.at(member, "position")
            leader["position"] = former
            member["position"] = JInt(position)
            return gid
        }
        val seats = inputs.guildLevel(Guild.levelOf(guild, inputs)).obj("seats")[position.toString()] ?: JInt(1)
        val held = guild.arr("members").count { (it as JObj)["position"] == JInt(position) }
        if (PyDocs.compare(JInt(held.toLong()), seats) >= 0) throw Acquisition.Rejected("Cannot apply this position", Guild.ERR_POSITION_CANNOT)
        member["position"] = JInt(position)
        return gid
    }

    /**
     * C2185 → S2332 ×4 + S258 system mail "Sign Up Guild War" (text 17102, type 6). Registration is open in war state 1
     * (52020 otherwise); one sign-up per guild per day (52021); only the leader signs up (52009).
     */
    private fun warSign(current: StateStore.Current, ctx: SocialContext, role: Long, now: Long): List<Frame> {
        val inputs = ctx.inputs!!
        if (Guild.warState(Guild.hour(now)) != 1) throw Acquisition.Rejected("The war application period ended", Guild.ERR_WAR_CLOSED)
        ctx.update("guilds", "guild_war_sign") { document ->
            val (gid, guild) = Guild.guildOf(document, role)
            if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", Guild.ERR_NOT_IN_GUILD)
            if (Guild.memberOf(guild, role).long("position") != Guild.LEADER) throw Acquisition.Rejected("No access", Guild.ERR_NO_ACCESS)
            if (guild.obj("war")["signed_day"] == io.github.okexodus.openknights.exact.JStr(Guild.day(now))) throw Acquisition.Rejected("Signed up already", Guild.ERR_WAR_SIGNED)
            guild.obj("war")["signed_day"] = io.github.okexodus.openknights.exact.JStr(Guild.day(now))
            gid to jobj("role" to role)
        }
        val mail = ctx.update("mail", "mail_guild_war") { d ->
            val text = inputs.text(17102).toByteArray(Charsets.UTF_8)
            val m = Mail.newMail(d, role, Mail.GUILD, 0, "System".toByteArray(), text, text, null, now)
            m to jobj("role" to role, "mail" to m["id"])
        }
        val (_, guild) = myGuild(ctx, role)
        return Guild.activityFrames(guild ?: noneSubscript(), now, ctx.inputs) + (Mail.S_ADD to Mail.brief(mail, ctx.clockOffset))
    }

    /**
     * C2169 `cstr title, cstr body` (officers with the mail permission) → S2316 `00`; a type-6 mail to every other member
     * (pushed as S258 to the online ones); without the permission S2316 `01`.
     */
    private fun guildMail(current: StateStore.Current, ctx: SocialContext, role: Long, title: ByteArray, body: ByteArray, now: Long): List<Frame> {
        val (gid, guild) = guildRequired(ctx, role)
        val member = Guild.memberOf(guild, role)
        if (!Guild.can(member.long("position"), "mail", ctx.inputs!!)) return listOf(Mail.S_GUILD_SEND_RESULT to byteArrayOf(1))
        val me = ctx.people(current)[role]
        val sent: List<Pair<Long, JObj>> = ctx.update("mail", "mail_guild") { document ->
            val mails = ArrayList<Pair<Long, JObj>>()
            for (m in guild.arr("members")) {
                val other = (m as JObj).long("role")
                if (other != role) mails.add(other to Mail.newMail(document, other, Mail.GUILD, role, me?.nameRaw ?: ByteArray(0), title, body, null, now))
            }
            mails to jobj("role" to role, "guild" to gid, "sent" to mails.size)
        }
        for ((recipient, mail) in sent) ctx.push(recipient) { offset -> listOf(Mail.S_ADD to Mail.brief(mail, offset)) }
        return listOf(Mail.S_GUILD_SEND_RESULT to byteArrayOf(0))
    }

    /** The guild actions that change the character (`guild_character`): `commit` runs the character transaction. */
    fun guildCharacter(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long,
                       servedTime: Long, ownerKey: String): List<Frame> {
        val role = roleOf(current)
        val inputs = ctx.inputs!!
        when (opcode) {
            Guild.C_CREATE -> {
                val (name, notice) = Guild.decodeCstrings(payload, 2, opcode)
                val doc = guildDoc(ctx)
                if (Guild.guildOf(doc, role).second != null) throw Acquisition.Rejected("Already in a guild", Guild.ERR_OTHER_GUILD)
                Guild.validateName(name, doc, inputs)
                val plan = commit("guild_create") { owned, cur ->
                    if (owned.roleBits(Guild.ROLE_LEVEL) <= BigInteger.valueOf(inputs.prop(200003, 50))) {
                        throw Acquisition.Rejected("Player level must be above 50", Guild.ERR_INVALID)
                    }
                    if (role(cur, Guild.VIP.toInt()) < inputs.prop(200002, 5)) throw Acquisition.Rejected("VIP level too low", Guild.ERR_INVALID)
                    val price = inputs.prop(200001, 1000)
                    if (owned.roleBits(Guild.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", Guild.ERR_DIAMONDS)
                    owned.roleAdd(Guild.DIAMOND, -price)
                    val frames = Shops.diamondAchievement(owned, price, servedTime) + roleFrame(owned, listOf(Guild.DIAMOND))
                    Plan(jobj("price" to price, "evidence_class" to "native_use_policy"), frames)
                }
                val gid: Long = ctx.update("guilds", "guild_create") { d ->
                    val g = Guild.create(d, role, name, notice, inputs, now)
                    g to jobj("role" to role, "guild" to g)
                }
                val (_, guild) = myGuild(ctx, role)
                return plan.packets.toList() + listOf(Guild.S_CREATE to io.github.okexodus.openknights.protocol.WireWriter().u8(0).u32(gid).bytes(),
                    myGuildFrame(ctx, role, now, current), Guild.S_TECH_LIST to Guild.techListPayload(guild ?: noneSubscript()),
                    personalFrame(current, ctx, role))
            }
            Guild.C_DONATE -> {
                if (payload.size != 8) throw Acquisition.Rejected("C2157 is u32 gold, u32 diamonds")
                val (gold, diamonds) = u32Pair(payload)
                val (_, guildNow) = guildRequired(ctx, role)
                var points = 0L
                var reward = JObj()
                val plan = commit("guild_donate") { owned, _ ->
                    val guild = guildNow.deepCopy()
                    val (p, r, spent) = Guild.donateGold(guild, role, gold, diamonds, owned, inputs, now)
                    points = p
                    reward = r
                    val fields = listOf(Guild.GOLD to gold, Guild.DIAMOND to spent).filter { it.second != 0L }.map { it.first } + Guild.CONTRIBUTION
                    val frames = (if (spent != 0L) Shops.diamondAchievement(owned, spent, servedTime) else emptyList()) + roleFrame(owned, fields)
                    Plan(jobj("gold" to gold, "diamonds" to spent, "points" to p, "evidence_class" to "native_use_policy"), frames)
                }
                ctx.update("guilds", "guild_donate") { document ->
                    val (gid, guild) = Guild.guildOf(document, role)
                    val member = Guild.memberOf(guild ?: noneSubscript(), role)
                    val today = Guild.day(now)
                    val before = if (member["gold_day"] == io.github.okexodus.openknights.exact.JStr(today)) PyDocs.int(PyDocs.at(member, "gold")) else BigInteger.ZERO
                    member["gold"] = JInt(before + BigInteger.valueOf(gold))
                    member["gold_day"] = io.github.okexodus.openknights.exact.JStr(today)
                    Guild.contribute(guild, role, points)
                    guild["diamonds"] = JInt(PyDocs.int(guild["diamonds"] ?: JInt(0)) + BigInteger.valueOf(diamonds))
                    gid to jobj("role" to role, "gold" to gold, "diamonds" to diamonds, "points" to points)
                }
                return plan.packets.toList() + listOf(Guild.S_DONATE to (byteArrayOf(0) + io.github.okexodus.openknights.protocol.BattleReport.encodeReward(reward)),
                    myGuildFrame(ctx, role, now, current))
            }
            Guild.C_WAGE -> {
                if (payload.isNotEmpty()) throw Acquisition.Rejected("C2201 has no payload")
                val (_, guildNow) = guildRequired(ctx, role)
                var reward = JObj()
                val plan = commit("guild_wage") { owned, _ ->
                    val (frames, r) = Guild.claimWage(guildNow.deepCopy(), role, owned, inputs, now)
                    reward = r
                    Plan(jobj("evidence_class" to "native_use_candidate"), frames)
                }
                ctx.update("guilds", "guild_wage") { document ->
                    val (gid, guild) = Guild.guildOf(document, role)
                    Guild.memberOf(guild ?: noneSubscript(), role)["wage_day"] = io.github.okexodus.openknights.exact.JStr(Guild.day(now))
                    gid to jobj("role" to role)
                }
                return plan.packets.toList() + listOf(Guild.S_WAGE to io.github.okexodus.openknights.protocol.BattleReport.encodeReward(reward),
                    myGuildFrame(ctx, role, now, current))
            }
            Guild.C_EMBLEM, Guild.C_RENAME -> return guildPaid(opcode, payload, current, ctx, commit, now, servedTime, role)
            Guild.C_TASK_DONATE, Guild.C_TASK_REFRESH, Guild.C_TASK_ACCEPT, Guild.C_TASK_CLAIM ->
                return guildTask(opcode, payload, current, ctx, commit, now, servedTime, ownerKey, role)
        }
        throw Acquisition.Rejected("Not a guild action")
    }

    /**
     * C2163 emblem upgrade (junhui[current].103 Diamonds, +112 Metals = role 30) and C2205 rename (the rename price): the
     * character pays in one transaction, then the guild record changes; every other member gets the new S2306.
     */
    private fun guildPaid(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long,
                          servedTime: Long, role: Long): List<Frame> {
        val inputs = ctx.inputs!!
        val doc = guildDoc(ctx)
        val price: Long
        val metals: Long
        val action: String
        var name = ByteArray(0)
        if (opcode == Guild.C_EMBLEM) {
            if (payload.isNotEmpty()) throw Acquisition.Rejected("C2163 has no payload")
            val (_, badge) = Guild.emblemStep(doc, role, inputs)
            price = badge.long("cost")
            metals = badge.long("metals")
            action = "guild_emblem"
        } else {
            name = Guild.decodeCstrings(payload, 1, opcode)[0]
            Guild.checkRename(doc, role, name, inputs)
            price = Guild.renamePrice(inputs)
            metals = 0
            action = "guild_rename"
        }
        val plan = commit(action) { owned, _ ->
            if (owned.roleBits(Guild.DIAMOND) < BigInteger.valueOf(price)) throw Acquisition.Rejected("Not enough Diamonds", Guild.ERR_DIAMONDS)
            owned.roleAdd(Guild.DIAMOND, -price)
            val fields = mutableListOf(Guild.DIAMOND)
            if (metals != 0L) {
                owned.roleAdd(Guild.CONTRIBUTION, metals)
                fields.add(Guild.CONTRIBUTION)
            }
            val frames = Shops.diamondAchievement(owned, price, servedTime) + roleFrame(owned, fields)
            Plan(jobj("price" to price, "metals" to metals, "evidence_class" to "native_use_policy"), frames)
        }
        if (opcode == Guild.C_EMBLEM) {
            ctx.update("guilds", "guild_emblem") { d -> Guild.upgradeEmblem(d, role, inputs) to jobj("role" to role, "price" to price) }
        } else {
            ctx.update("guilds", "guild_rename") { d -> Guild.rename(d, role, name, inputs) to jobj("role" to role, "price" to price) }
        }
        val (_, guild) = myGuild(ctx, role)
        for (member in (guild ?: noneSubscript()).arr("members")) {
            val other = (member as JObj).long("role")
            if (other != role) pushNow(ctx, other, listOf(myGuildFrame(ctx, other, now)))
        }
        return plan.packets.toList() + listOf(myGuildFrame(ctx, role, now, current))
    }

    /** The guild task board actions (C2435 donate, C2437 `02` star refresh, C2439 accept, C2443 claim). */
    private fun guildTask(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long,
                          servedTime: Long, ownerKey: String, role: Long): List<Frame> {
        val inputs = ctx.inputs!!
        val (_, guildNow) = guildRequired(ctx, role)
        if (opcode == Guild.C_TASK_REFRESH) {
            if (payload.size != 1 || payload[0].toInt() !in listOf(1, 2)) throw Acquisition.Rejected("C2437 is u8 1 query / 2 refresh")
            if (payload[0].toInt() == 1) return listOf(taskFrame(current, ctx, now, ownerKey))
        }
        val action = if (opcode == Guild.C_TASK_CLAIM) "guild_task_claim" else "guild_task"
        val plan = commit(action) { owned, cur ->
            val doc = Guild.taskDocument(PyDocs.get(cur, "guild_task_state"), inputs, now, ownerKey)
            when (opcode) {
                Guild.C_TASK_DONATE -> {
                    val frames = Guild.donateToTask(doc, Guild.decodeTaskDonate(payload), owned, inputs)
                    Plan(jobj("guild_task_state_after" to doc, "evidence_class" to "capture_observed"), frames)
                }
                Guild.C_TASK_REFRESH -> {
                    val frames = Guild.refreshStar(doc, owned, inputs, servedTime, Guild.starRng(ownerKey, now))
                    Plan(jobj("guild_task_state_after" to doc, "star" to doc["star"], "evidence_class" to "native_use_policy"),
                        frames + (Guild.S_TASKS to Guild.tasksPayload(doc, now)))
                }
                Guild.C_TASK_ACCEPT -> {
                    Guild.acceptTask(doc, Guild.decodeU32(payload, opcode), inputs)
                    Plan(jobj("guild_task_state_after" to doc, "evidence_class" to "native_use_candidate"), listOf(Guild.S_TASKS to Guild.tasksPayload(doc, now)))
                }
                else -> {
                    val taskId = Guild.decodeU32(payload, opcode)
                    val (frames, got) = Guild.claimTask(doc, taskId, owned, inputs, guildNow.deepCopy(), role)
                    val data = jobj("guild_task_state_after" to doc)
                    for ((k, v) in got) data[k] = v
                    data["evidence_class"] = io.github.okexodus.openknights.exact.JStr("capture_observed_calculation")
                    Plan(data, frames + (Guild.S_TASKS to Guild.tasksPayload(doc, now)))
                }
            }
        }
        if (opcode == Guild.C_TASK_CLAIM && Py.truthy(plan["contribution"])) {
            val points = plan["contribution"]!!
            ctx.update("guilds", "guild_task_contribution") { document ->
                val (gid, guild) = Guild.guildOf(document, role)
                if (guild != null) Guild.contribute(guild, role, PyDocs.long(points))
                gid to jobj("role" to role, "points" to points)
            }
        }
        return plan.packets.toList()
    }

    // --- friends, mail, chat (agent B: friends, mail, chat and their helpers) -------------------------------------------

    /** `SocialContext.by_name`: {casefolded name: participant} (a later equal name wins). */
    private fun byName(ctx: SocialContext, current: StateStore.Current?): Map<String, Participant> {
        val out = LinkedHashMap<String, Participant>()
        for (p in ctx.people(current).values) out[Chat.nameKey(p.nameRaw)] = p
        return out
    }

    private fun u32(value: Long): ByteArray = io.github.okexodus.openknights.protocol.WireWriter().u32(value).bytes()

    /** Raised inside the praise planner when nothing is praised: the transaction is abandoned (not a refusal). */
    private class NoPraise : RuntimeException()

    /** `_friend_point_reward`: an empty Reward with Pal Points. */
    private fun friendPointReward(points: Long): JObj {
        val reward = Acquisition.emptyReward()
        reward["friend_point"] = JInt(points)
        return reward
    }

    /** The friend requests (`friends`): C353 pending, C385 player card, C355 recommendations, C359 / C361 add, C363 reply, C365 remove, C387 praise. */
    fun friends(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long): List<Frame> {
        val role = roleOf(current)
        val inputs = ctx.inputs
        val people = ctx.people(current)
        val presence = ctx.presence()
        val vip = role(current, Friends.ROLE_VIP)
        if (opcode == Friends.C_PENDING) {
            return listOf(Friends.S_PENDING to Friends.pendingPayload(ctx.document("social")!!, role, people, presence, now, ctx.clockOffset))
        }
        if (opcode == Friends.C_INFO) {
            val target = people[Friends.decodeId(payload, opcode)] ?: throw Acquisition.Rejected("Can't find the player", Friends.ERR_NO_PLAYER)
            var state: JObj? = null
            if (target.participantId == role) state = current.state
            else if (!target.characterId.isNullOrEmpty() && ctx.registry != null) state = ctx.registry.resolveStateStore(target.characterId).read().state
            return listOf(Friends.S_INFO to Friends.playerInfoPayload(target, state, presence, now, ctx.clockOffset))
        }
        if (opcode == Friends.C_RECOMMEND) {
            val request = Friends.decodeRecommend(payload)
            return listOf(Friends.S_RECOMMEND to Friends.recommendPayload(ctx.document("social")!!, role, people, presence, now,
                request.long("count"), request.long("kind"), role(current, 3, 1), ctx.clockOffset, ctx.inputs))
        }
        if (opcode == Friends.C_ADD || opcode == Friends.C_ADD_NAME) {
            val target = if (opcode == Friends.C_ADD) people[Friends.decodeId(payload, opcode)]
            else byName(ctx, current)[Chat.nameKey(Friends.decodeName(payload, opcode))]
            if (target == null) return listOf(Friends.S_ADD_RESULT to byteArrayOf(1))             // "Can't find the player"
            ctx.update<Unit>("social", "friend_add") { d ->
                Friends.add(d, role, target.participantId, Friends.maxFriends(vip, inputs!!), now)
                Unit to jobj("role" to role, "target" to target.participantId)
            }
            return listOf(Friends.S_ADD_RESULT to byteArrayOf(0), Friends.S_FRIEND_ADD to Friends.record(target, presence, now, offset = ctx.clockOffset))
        }
        if (opcode == Friends.C_REPLY) {
            val request = Friends.decodeReply(payload)
            val requesterId = request.long("id")
            val requester = people[requesterId]
            val theirVip = requester?.vip ?: 0L
            val result: Int = ctx.update("social", "friend_reply") { d ->
                val r = Friends.reply(d, role, requesterId, request.bool("accept"), Friends.maxFriends(vip, inputs!!), Friends.maxFriends(theirVip, inputs))
                r to jobj("role" to role, "requester" to requesterId, "result" to r)
            }
            val frames = arrayListOf<Frame>(Friends.S_REPLY_RESULT to PyDocs.bytes(listOf(result.toLong())))
            if (result == 0 && requester != null) {
                frames.add(Friends.S_FRIEND_ADD to Friends.record(requester, presence, now, offset = ctx.clockOffset))
                val me = people[role]
                if (me != null) ctx.push(requester.participantId) { offset -> listOf(Friends.S_FRIEND_ADD to Friends.record(me, presence, now, offset = offset)) }
            }
            return frames
        }
        if (opcode == Friends.C_REMOVE) {
            val target = Friends.decodeId(payload, opcode)
            ctx.update<Unit>("social", "friend_remove") { d ->
                Friends.remove(d, role, target)
                Unit to jobj("role" to role, "target" to target)
            }
            ctx.push(target) { listOf(Friends.S_FRIEND_REMOVE to u32(role)) }
            return listOf(Friends.S_FRIEND_REMOVE to u32(target))
        }
        if (opcode == Friends.C_PRAISE) {
            val request = Friends.decodePraise(payload)
            val target = request.long("target")
            val social = ctx.document("social")!!
            var result: Friends.Praise? = null
            val plan = try {
                commit("friend_praise") { owned, _ ->
                    val praise = Friends.planPraise(social, role, target, request.long("kind"), owned, inputs!!, now)
                    result = praise
                    if (!praise.praised) throw NoPraise()
                    Plan(jobj("target" to target, "evidence_class" to "capture_observed_policy_mail"), praise.packets)
                }
            } catch (e: NoPraise) {
                return result!!.packets
            }
            ctx.update<Unit>("social", "friend_praise") { d ->
                d.obj("praise")["$role:$target"] = JInt(now)
                Unit to jobj("role" to role, "target" to target)
            }
            val me = people[role]
            val mail: JObj = ctx.update("mail", "mail_praise") { d ->
                val m = Mail.newMail(d, target, Mail.PRAISE, role, me?.nameRaw ?: ByteArray(0), inputs!!.text(1115).toByteArray(Charsets.UTF_8),
                    inputs.text(1113).toByteArray(Charsets.UTF_8), friendPointReward(Friends.PRAISE_NOTE_POINTS), now)
                m to jobj("to" to target, "mail" to m["id"])
            }
            ctx.push(target) { offset -> listOf(Mail.S_ADD to Mail.brief(mail, offset)) }
            return plan.packets
        }
        throw Acquisition.Rejected("Not a friend action")
    }

    /** The mail requests (`mail`): C257 list, C195 read, C197 claim, C199 delete, C201 write, C203 / C205 / C207 blacklist. */
    fun mail(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long): List<Frame> {
        val role = roleOf(current)
        val claimed = claimedMail(current)
        if (opcode == Mail.C_LIST) {
            return listOf(Mail.S_LIST to Mail.listPayload(ctx.document("mail")!!, role, now, claimed, ctx.clockOffset))
        }
        val ledger = PyDocs.get(current, "mail_state")
        if (opcode == Mail.C_READ) {
            val mailId = Mail.decodeId(payload, opcode)
            val found = Mail.find(ctx.document("mail")!!, role, mailId)
            var paid: List<Frame> = emptyList()
            if (Mail.praiseUnpaid(found, ledger)) {
                paid = commit("mail_claim") { owned, cur ->
                    Mail.planPraiseRead(found!!, owned, ctx.inputs!!, PyDocs.get(cur, "mail_state")).also { it["evidence_class"] = "native_use_policy" }
                }.packets
            }
            val hide = found != null && found["type"] == JInt(Mail.PRAISE)
            val frames: List<Frame> = ctx.update("mail", "mail_read") { d -> Mail.read(d, role, mailId, hide) to jobj("role" to role, "mail" to mailId) }
            return frames + paid
        }
        if (opcode == Mail.C_CLAIM) {
            val mailId = Mail.decodeId(payload, opcode)
            val found = Mail.find(ctx.document("mail")!!, role, mailId)
            if (found == null || mailId in claimed) {
                if (found != null) {                     // claimed before, the world removal still pending
                    ctx.update<Unit>("mail", "mail_remove") { d -> Mail.remove(d, role, mailId); Unit to jobj("role" to role, "mail" to mailId) }
                }
                return listOf(Mail.S_REMOVED to u32(mailId))          // live: a repeated C197 → S272
            }
            val plan = commit("mail_claim") { owned, cur ->
                Mail.planClaim(found, owned, ctx.inputs!!, PyDocs.get(cur, "mail_state")).also { it["evidence_class"] = "capture_observed" }
            }
            ctx.update<Unit>("mail", "mail_claimed") { d -> Mail.remove(d, role, mailId); Unit to jobj("role" to role, "mail" to mailId) }
            return plan.packets
        }
        if (opcode == Mail.C_DELETE) {
            val mailId = Mail.decodeId(payload, opcode)
            val found = Mail.find(ctx.document("mail")!!, role, mailId)
            var paid: List<Frame> = emptyList()
            if (Mail.praiseUnpaid(found, ledger)) {
                // removed before it was opened (Praise back / Delete both send C199): its Pal Points are paid now
                paid = commit("mail_claim") { owned, cur ->
                    Mail.planPraiseRead(found!!, owned, ctx.inputs!!, PyDocs.get(cur, "mail_state")).also { it["evidence_class"] = "native_use_policy" }
                }.packets
            }
            val settled = if (found != null && found["type"] == JInt(Mail.PRAISE)) claimed + mailId else claimed
            val frames: List<Frame> = ctx.update("mail", "mail_delete") { d -> Mail.delete(d, role, mailId, settled) to jobj("role" to role, "mail" to mailId) }
            return paid + frames
        }
        if (opcode == Mail.C_WRITE) {
            val (to, title, body) = Mail.decodeStrings(payload, 3, opcode)
            val people = ctx.people(current)
            val sender = people[role]
            var recipient = byName(ctx, current)[Chat.nameKey(to)]
            if (recipient != null && recipient.participantId == role) recipient = null
            var code = 0
            var written: JObj? = null
            ctx.update<Unit>("mail", "mail_write") { d ->
                val (c, m) = Mail.write(d, sender, recipient, title, body, ctx.inputs!!, now)
                code = c
                written = m
                Unit to (if (m != null) jobj("role" to role, "to" to recipient!!.participantId) else null)
            }
            val delivered = written
            if (delivered != null) ctx.push(recipient!!.participantId) { offset -> listOf(Mail.S_ADD to Mail.brief(delivered, offset)) }
            return listOf(Mail.S_SEND_RESULT to PyDocs.bytes(listOf(code.toLong())))
        }
        if (opcode == Mail.C_BLACKLIST) return listOf(Mail.S_BLACKLIST to Mail.blacklistPayload(ctx.document("mail")!!, role))
        if (opcode == Mail.C_BLOCK || opcode == Mail.C_UNBLOCK) {
            val name = Friends.decodeName(payload, opcode)
            ctx.update<Unit>("mail", "mail_blacklist") { d ->
                val lists = d.obj("blacklist")
                val names = (lists[role.toString()] as? JArr) ?: JArr().also { lists[role.toString()] = it }
                val hex = Mail.nameHex(name)
                if (opcode == Mail.C_BLOCK && hex !in names && names.size < 50) names.add(hex)
                else if (opcode == Mail.C_UNBLOCK && hex in names) names.remove(hex)
                Unit to jobj("role" to role, "block" to (opcode == Mail.C_BLOCK))
            }
            return if (opcode == Mail.C_BLOCK) emptyList() else listOf(Mail.S_BLACKLIST to Mail.blacklistPayload(ctx.document("mail")!!, role))
        }
        throw Acquisition.Rejected("Not a mail action")
    }

    /** C449 a chat line (`chat`) → the sender's frames; other recipients get pushes. World lines reach every online player. */
    fun chat(payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long, onlineRoles: List<Long>): List<Frame> {
        val request = Chat.decodeChat(payload)
        val role = roleOf(current)
        val people = ctx.people(current)
        val sender = people[role]
        val (gid, guild) = myGuild(ctx, role)
        val members = if (guild != null && Py.truthy(guild)) guild.arr("members").map { (it as JObj).long("role") } else emptyList()
        var deliveries: List<Chat.Delivery> = emptyList()
        ctx.update<Unit>("chat", "chat_line") { document ->
            val (sent, line) = Chat.send(document, request, sender, gid, members, byName(ctx, current), ctx.inputs!!, now)
            deliveries = sent
            Unit to (if (line != null) jobj("role" to role, "channel" to request.channel) else null)
        }
        val blacklists = ctx.document("mail")!!
        val own = ArrayList<Frame>()
        for (delivery in deliveries) {
            val frame = delivery.frame
            val recipient = delivery.recipient
            if (delivery.world) {
                own.add(frame)
                for (other in onlineRoles) {
                    if (other != role && !Mail.blocked(blacklists, other, sender!!.nameRaw)) ctx.push(other) { listOf(frame) }
                }
            } else if (recipient == null || recipient == role) own.add(frame)
            else if (!Mail.blocked(blacklists, recipient, sender!!.nameRaw)) ctx.push(recipient) { listOf(frame) }
        }
        return own
    }

    // --- shared helpers -------------------------------------------------------------------------------------------------

    private fun claimedMail(current: StateStore.Current): List<Long> {
        val ledger = current.document("mail_state")
        val claimed = if (Py.truthy(ledger)) (ledger as JObj)["claimed"] else null
        return (claimed as? JArr)?.map { (it as JInt).toLong() } ?: emptyList()
    }

    /**
     * The guild war's matching result, delivered lazily (at login or on the Events tab) once matching began: offline no
     * other guild takes part, so every member gets the System mail "No Match Found." dated 19:00. Returns the S258 frames
     * for [role]; online members get theirs pushed.
     */
    fun warResults(ctx: SocialContext, role: Long, now: Long): List<Frame> {
        val (_, guild) = myGuild(ctx, role)
        if (guild == null || !Guild.warResultsDue(guild, now)) return emptyList()
        val text = ctx.inputs!!.text(Guild.TEXT_NO_MATCH).toByteArray(Charsets.UTF_8)
        val members: List<Long> = ctx.update("guilds", "guild_war_result") { document ->
            val (gid, g) = Guild.guildOf(document, role)
            if (g == null || !Guild.warResultsDue(g, now)) return@update emptyList<Long>() to null
            g.obj("war")["notified_day"] = io.github.okexodus.openknights.exact.JStr(Guild.day(now))
            g.arr("members").map { (it as JObj).long("role") } to jobj("guild" to gid, "result" to "no_match")
        }
        if (members.isEmpty()) return emptyList()
        val at = Guild.matchTime(now)
        val sent: List<Pair<Long, JObj>> = ctx.update("mail", "mail_guild_war_result") { document ->
            val mails = members.map { r -> r to Mail.newMail(document, r, Mail.GUILD, 0, "System".toByteArray(), text, text, null, at) }
            mails to jobj("sent" to mails.size, "mail" to "guild_war_no_match")
        }
        val own = ArrayList<Frame>()
        for ((recipient, mail) in sent) {
            if (recipient == role) own.add(Mail.S_ADD to Mail.brief(mail, ctx.clockOffset))
            else ctx.push(recipient) { offset -> listOf(Mail.S_ADD to Mail.brief(mail, offset)) }
        }
        return own
    }

    /** Replies to the social initialization queries (C2145 my guild, C257 mail list + chat history, C353 pending). */
    fun loginFrames(current: StateStore.Current, ctx: SocialContext, now: Long): List<Frame> {
        val role = roleOf(current)
        val claimed = claimedMail(current)
        try {
            warResults(ctx, role, now)                  // a due war result lands in the mail list below
        } catch (e: Acquisition.Rejected) {
        }
        val (gid, _) = myGuild(ctx, role)
        val frames = ArrayList<Frame>()
        frames.add(Mail.S_LIST to Mail.listPayload(ctx.document("mail")!!, role, now, claimed, ctx.clockOffset))
        frames.addAll(Chat.loginHistory(ctx.document("chat")!!, role, gid))
        // the world's three newest Summon Report records (after S256 / S226, before S2306)
        val report = SummonReports.loginPayload(ctx.document("summon_reports"), ctx.clockOffset)
        if (report != null) frames.add(SummonReports.S_REPORT to report)
        frames.add(myGuildFrame(ctx, role, now, current))
        frames.add(Friends.S_PENDING to Friends.pendingPayload(ctx.document("social")!!, role, ctx.people(current), ctx.presence(), now, ctx.clockOffset))
        return frames
    }

    /**
     * What the login refresh writes into the S18: the friends section rebuilt from the world's friendships and MaxFriend
     * (role 25) = 30 + viplv col 112. Computed from the world at login and recorded in the refresh revision.
     */
    fun friendsRefreshData(current: StateStore.Current, ctx: SocialContext, now: Long): JObj {
        val role = role(current, 0)
        return jobj("section" to Friends.friendsSection(ctx.document("social")!!, role, ctx.people(current), ctx.presence(), ctx.inputs!!, now, ctx.clockOffset),
            "max_friend" to Friends.maxFriends(role(current, Friends.ROLE_VIP), ctx.inputs))
    }

    /** Apply [friendsRefreshData] to the S18 state; true when anything changed. */
    fun refreshFriends(state: JObj, data: JObj): Boolean {
        var changed = false
        val subsystems = state.obj("subsystems")
        val friends = subsystems["friends"]
        if (friends != null && friends != io.github.okexodus.openknights.exact.JNull &&
            Json.dumps(friends, sortKeys = true) != Json.dumps(data.obj("section"), sortKeys = true)) {
            subsystems["friends"] = data.obj("section").deepCopy()
            changed = true
        }
        for (prop in state.arr("role_properties")) {
            val p = prop as JObj
            if (p.long("id") == Friends.ROLE_MAX_FRIEND.toLong() && p.obj("value")["bits"] != data["max_friend"]) {
                p.obj("value")["bits"] = data["max_friend"]!!
                changed = true
            }
        }
        return changed
    }

    fun setPresence(ctx: SocialContext, role: Long, online: Boolean, now: Long) {
        ctx.update<Unit>("presence", "presence") { document ->
            val players = document.obj("players")
            val entry = (players[role.toString()] as? JObj) ?: JObj().also { players[role.toString()] = it }
            if (entry["online"] == JBool(online) && (online || Py.truthy(entry["last_seen"]))) return@update Unit to null
            entry["online"] = JBool(online)
            entry["last_seen"] = JInt(now)
            Unit to jobj("role" to role, "online" to online)
        }
    }

    /** Players who have this participant on their friend list (they receive S398 / S400). */
    fun friendWatchers(ctx: SocialContext, role: Long): List<Long> {
        val social = ctx.document("social")!!
        return social.obj("friends").entries.filter { (_, list) -> (list as JArr).any { (it as JInt).toLong() == role } }
            .map { (r, _) -> PyValues.parseInt(r).toLong() }
    }
}

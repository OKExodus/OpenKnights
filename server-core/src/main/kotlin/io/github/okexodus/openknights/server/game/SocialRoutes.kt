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
    ) {
        private var people: Map<Long, Participant>? = null

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

    /** The guild task list frame (`task_frame`). */
    fun taskFrame(current: StateStore.Current, ctx: SocialContext, now: Long, ownerKey: String): Frame =
        throw NotPorted("social_routes.task_frame")

    /** The guild queries (`query`). */
    fun query(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long, ownerKey: String): List<Frame> {
        val role = roleOf(current)
        if (opcode == Guild.C_MY_GUILD) {
            guildDoc(ctx)
            ctx.people(current)
            return listOf(myGuildFrame(ctx, role, now, current))
        }
        throw NotPorted("social_routes.query (opcode $opcode)")
    }

    /** The world-only guild actions (`guild_world`). */
    fun guildWorld(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long): List<Frame> =
        throw NotPorted("social_routes.guild_world (opcode $opcode)")

    /** The guild actions that change the character (`guild_character`). */
    fun guildCharacter(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, commit: Commit, now: Long,
                       servedTime: Long, ownerKey: String): List<Frame> =
        throw NotPorted("social_routes.guild_character (opcode $opcode)")

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

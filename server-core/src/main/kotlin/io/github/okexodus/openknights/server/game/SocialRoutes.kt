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

    /** One social request (only the initialization queries are ported yet). */
    fun dispatch(opcode: Int, payload: ByteArray, current: StateStore.Current, ctx: SocialContext, now: Long): List<Frame> {
        val role = roleOf(current)
        when (opcode) {
            Guild.C_MY_GUILD -> {
                guildDoc(ctx)
                ctx.people(current)
                return listOf(myGuildFrame(ctx, role, now, current))
            }
            Friends.C_PENDING -> {
                val people = ctx.people(current)
                val presence = ctx.presence()
                return listOf(Friends.S_PENDING to Friends.pendingPayload(ctx.document("social")!!, role, people, presence, now, ctx.clockOffset))
            }
            Mail.C_LIST -> {
                val claimed = claimedMail(current)
                return listOf(Mail.S_LIST to Mail.listPayload(ctx.document("mail")!!, role, now, claimed, ctx.clockOffset))
            }
        }
        throw NotPorted("social request (opcode $opcode)")
    }

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

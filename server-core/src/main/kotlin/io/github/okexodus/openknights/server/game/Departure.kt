package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.store.WorldDirectory

/**
 * A participant left the world (`world_participants.participant_departed` and the `depart` rules of the guild,
 * friends and summon-report modules): a deleted character leaves no in-game trace — its guild seat (leader succession
 * by position, then contribution, then seniority; the last member dissolves the guild), applications, rejoin entry,
 * friendships, requests and praise cooldowns, its mailbox and sent mails, chat lines, presence, Arena rank, stage first
 * kills, helper cooldowns and Summon Report records. Each document is one audited world write; nothing to remove
 * writes nothing.
 */
object Departure {
    private fun JObj.objOrEmpty(key: String): JObj = this[key] as? JObj ?: JObj()

    /** `guild.depart`. */
    fun guild(document: JObj, role: Long): JObj? {
        val detail = JObj()
        val (gid, guild) = Guild.guildOf(document, role)
        if (guild != null) {
            val member = Guild.memberOf(guild, role)
            val members = guild.arr("members")
            members.remove(member)
            detail["guild"] = JInt(gid)
            detail["position"] = member["position"]!!
            if (members.isEmpty()) {
                document.obj("guilds").remove(gid.toString())
                detail["dissolved"] = JBool(true)
            } else if (member.long("position") == Guild.LEADER && members.none { (it as JObj).long("position") == Guild.LEADER }) {
                val heir = members.map { it as JObj }.minWith(compareBy<JObj> { it.long("position") }.thenBy { -it.long("contribution") }
                    .thenBy { it.long("joined_at") }.thenBy { it.long("role") })
                detail["heir"] = heir["role"]!!
                detail["heir_position"] = heir["position"]!!
                heir["position"] = JInt(Guild.LEADER)
            }
        }
        var applied = 0
        for ((_, other) in document.obj("guilds")) {
            val o = other as JObj
            val applications = o.arr("applications")
            val kept = applications.filter { (it as JObj).long("role") != role }
            applied += applications.size - kept.size
            o["applications"] = JArr(kept.toMutableList())
        }
        if (applied != 0) detail["applications"] = JInt(applied)
        val rejoin = document["rejoin"] as? JObj
        if (rejoin != null && rejoin.remove(role.toString()) != null) detail["rejoin"] = JBool(true)
        return if (detail.isEmpty()) null else detail
    }

    /** `friends.depart`: its list, its place on every other list, requests both ways and the praise cooldowns of its pairs. */
    fun friends(social: JObj, role: Long): JObj? {
        val key = role.toString()
        val friends = social.obj("friends")
        val requests = social.obj("requests")
        val ownFriends = (friends.remove(key) as? JArr)?.size ?: 0
        val ownRequests = (requests.remove(key) as? JArr)?.size ?: 0
        var listedBy = 0
        var requestsSent = 0
        var praise = 0
        for (owner in friends.keys.toList()) {
            val targets = friends[owner] as JArr
            if (targets.any { (it as JInt).toLong() == role }) {
                friends[owner] = JArr(targets.filter { (it as JInt).toLong() != role }.toMutableList())
                listedBy++
            }
        }
        for (owner in requests.keys.toList()) {
            val list = requests[owner] as JArr
            val kept = list.filter { (it as JObj).long("from") != role }
            if (kept.size != list.size) {
                requests[owner] = JArr(kept.toMutableList())
                requestsSent += list.size - kept.size
            }
        }
        val praiseDoc = social.obj("praise")
        for (pair in praiseDoc.keys.filter { key in it.split(":") }) {
            praiseDoc.remove(pair)
            praise++
        }
        val detail = jobj("friends" to ownFriends, "listed_by" to listedBy, "requests" to ownRequests, "requests_sent" to requestsSent, "praise" to praise)
        return if (listOf(ownFriends, listedBy, ownRequests, requestsSent, praise).any { it != 0 }) detail else null
    }

    /** `summon_reports.forget`: the departed participant's records leave the report. */
    fun summonReports(document: JObj, role: Long): JObj? {
        val entries = (document["entries"] as? JArr) ?: JArr()
        val kept = entries.filter { (it as JObj).long("role") != role }
        document["entries"] = JArr(kept.toMutableList())
        return if (kept.size != entries.size) jobj("summon_reports_removed" to true) else null
    }

    fun helper(social: JObj, role: Long): JObj? {
        val helper = social["helper"].let { if (Py.truthy(it)) it as JObj else null }
        val keys = helper?.keys?.filter { role.toString() in it.split(":") } ?: emptyList()
        for (key in keys) social.obj("helper").remove(key)
        return if (keys.isNotEmpty()) jobj("helper_cooldowns" to keys.size) else null
    }

    fun mail(document: JObj, role: Long): JObj? {
        val boxes = document.obj("boxes")
        val removed = (boxes.remove(role.toString()) as? JArr)?.size ?: 0
        var sent = 0
        for (owner in boxes.keys.toList()) {
            val mails = boxes[owner] as JArr
            val kept = mails.filter { ((it as JObj)["sender"] as? JInt)?.toLong() != role }
            sent += mails.size - kept.size
            boxes[owner] = JArr(kept.toMutableList())
        }
        val blocked = (document["blacklist"] as? JObj)?.remove(role.toString())
        val wasBlocked = Py.truthy(blocked)
        return if (removed != 0 || sent != 0 || wasBlocked) jobj("mailbox" to removed, "sent" to sent, "blacklist" to wasBlocked) else null
    }

    fun chat(document: JObj, role: Long): JObj? {
        fun mine(line: JValue): Boolean {
            val l = line as JObj
            val target = l["target"].let { if (Py.truthy(it)) it as JObj else JObj() }
            return (l["sender"] as? JInt)?.toLong() == role || (target["id"] as? JInt)?.toLong() == role
        }
        var removed = 0
        val world = document.arr("world")
        val keptWorld = world.filter { !mine(it) }
        removed += world.size - keptWorld.size
        document["world"] = JArr(keptWorld.toMutableList())
        for (key in listOf("guild", "private")) {
            val box = document.obj(key)
            if (key == "private") removed += (box.remove(role.toString()) as? JArr)?.size ?: 0
            for (owner in box.keys.toList()) {
                val lines = box[owner] as JArr
                val kept = lines.filter { !mine(it) }
                removed += lines.size - kept.size
                box[owner] = JArr(kept.toMutableList())
            }
        }
        return if (removed != 0) jobj("chat_lines" to removed) else null
    }

    fun roulette(document: JObj, role: Long): JObj? {
        val key = role.toString()
        var removed = (document["total"] as? JObj)?.remove(key) != null
        for ((_, scores) in (document["days"] as? JObj) ?: JObj()) removed = ((scores as JObj).remove(key) != null) || removed
        return if (removed) jobj("roulette_rank" to true) else null
    }

    fun presence(document: JObj, role: Long): JObj? =
        if (document.obj("players").remove(role.toString()) != null) jobj("presence" to true) else null

    fun arena(document: JObj, role: Long): JObj? {
        val ranks = (document["ranks"] as? JArr) ?: JArr()
        val index = ranks.indexOfFirst { (it as JInt).toLong() == role }
        if (index < 0) return null
        document["ranks"] = JArr(ranks.filter { (it as JInt).toLong() != role }.toMutableList())
        return jobj("arena_rank" to index + 1)
    }

    fun firstKill(document: JObj, role: Long): JObj? {
        val kills = (document["first_kills"] as? JObj) ?: JObj()
        val stages = kills.entries.filter { (_, e) -> ((e as JObj)["role"] as? JInt)?.toLong() == role }.map { it.key }
        for (stage in stages) kills.remove(stage)
        return if (stages.isNotEmpty()) jobj("first_kills" to stages.size) else null
    }

    /** {document: detail} of what changed. */
    fun participantDeparted(world: WorldDirectory, role: Long, actor: String, reason: String): JObj {
        val changes = JObj()
        val rules: List<Pair<String, (JObj, Long) -> JObj?>> = listOf("guilds" to ::guild, "social" to ::friends,
            "summon_reports" to ::summonReports, "social" to ::helper, "mail" to ::mail, "chat" to ::chat,
            "presence" to ::presence, "arena_ladder" to ::arena, "campaign" to ::firstKill, "roulette_rank" to ::roulette)
        for ((name, depart) in rules) {
            if (world.document(name) == null) continue
            val detail = world.updateDocument(name, actor, "participant_departed") { document ->
                val d = depart(document, role)
                d to d?.let { JObj(LinkedHashMap(it.map)).also { full -> full["role"] = JInt(role); full["reason"] = io.github.okexodus.openknights.exact.JStr(reason) } }
            }
            if (detail != null) {
                val into = (changes[name] as? JObj) ?: JObj().also { changes[name] = it }
                detail.forEach { (k, v) -> into[k] = v }
            }
        }
        return changes
    }
}

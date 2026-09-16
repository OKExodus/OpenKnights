package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.server.store.StateStore

/** Guild mutations used by the private in-chat administrator command route.
 *
 * These functions only edit the supplied copy of the world guild document. The
 * caller owns the transaction and is responsible for committing the world and
 * character documents together. [Owned] and [current] are accepted so callers
 * cannot accidentally apply an admin guild operation to a different character
 * revision; guild state itself remains world-owned.
 */
object AdminGuild {
    /** Create a level-one guild without charging the normal creation cost. */
    fun createGuild(
        document: JObj,
        owned: Owned,
        current: StateStore.Current,
        inputs: DailyInputs,
        nameRaw: ByteArray,
        now: Long,
    ): Long {
        val role = SocialRoutes.roleOf(current)
        val name = Guild.checkCreate(document, role, nameRaw, ByteArray(0), inputs, now)
        val gid = document.long("next_id")
        document["next_id"] = JInt(gid + 1)
        val notice = inputs.text(0x1285).toByteArray(Charsets.UTF_8)
        document.obj("guilds")[gid.toString()] = Guild.newGuild(gid, name, notice, role, now)
        // Creating a guild also lapses this founder's pending applications,
        // matching the normal C2153 behavior.
        for (value in document.obj("guilds").values) {
            val guild = value as JObj
            guild["applications"] = io.github.okexodus.openknights.exact.JArr(
                guild.arr("applications").filter { (it as JObj).long("role") != role }.toMutableList()
            )
        }
        return gid
    }

    /** Leave a guild under the native succession rule: a leader with members must transfer first. */
    fun leaveGuild(
        document: JObj,
        owned: Owned,
        current: StateStore.Current,
        inputs: DailyInputs,
        now: Long,
    ): Long {
        return Guild.quitGuild(document, SocialRoutes.roleOf(current), now)
    }

    /** Disband the actor's guild after the Session layer has obtained confirmation. */
    fun disbandGuild(
        document: JObj,
        owned: Owned,
        current: StateStore.Current,
        inputs: DailyInputs,
        now: Long,
        confirmed: Boolean,
    ): Long {
        if (!confirmed) throw Acquisition.Rejected("Confirmation required", Guild.ERR_INVALID)
        val role = SocialRoutes.roleOf(current)
        val (gid, guild) = Guild.guildOf(document, role)
        if (guild == null) throw Acquisition.Rejected("Not in a Guild yet", Guild.ERR_NOT_IN_GUILD)
        if (Guild.memberOf(guild, role).long("position") != Guild.LEADER) {
            throw Acquisition.Rejected("Only the Guild leader can disband it", Guild.ERR_NO_ACCESS)
        }
        val rejoin = (document["rejoin"] as? JObj) ?: JObj().also { document["rejoin"] = it }
        for (member in guild.arr("members")) {
            rejoin[(member as JObj).long("role").toString()] = JInt(now + Guild.REJOIN_CD)
        }
        document.obj("guilds").remove(gid.toString())
        // Applications to the removed guild are part of the removed record;
        // no public chat, mail, or bot notification is produced here.
        return gid
    }
}

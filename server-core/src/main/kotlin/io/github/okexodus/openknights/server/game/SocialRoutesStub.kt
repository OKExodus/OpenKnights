package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj

/**
 * Stand-in for the social routes port (merged separately; this file is removed then): only `refresh_friends`, which
 * the daily login refresh calls.
 */
object SocialRoutes {
    private const val ROLE_MAX_FRIEND = 25L

    /** Apply the login friends data (`{"section", "max_friend"}`) to the S18; true when anything changed. */
    fun refreshFriends(state: JObj, data: JObj): Boolean {
        var changed = false
        val subsystems = state.obj("subsystems")
        val section = PyDocs.at(data, "section")
        if (PyDocs.get(subsystems, "friends") != null && PyDocs.sortedDump(subsystems["friends"]) != PyDocs.sortedDump(section)) {
            subsystems["friends"] = section.deepCopy()
            changed = true
        }
        val maxFriend = PyDocs.at(data, "max_friend")
        for (p in state.arr("role_properties")) {
            val prop = p.asObj
            if (prop["id"] == JInt(ROLE_MAX_FRIEND) && (PyDocs.get(prop.obj("value"), "bits") ?: JNull) != maxFriend) {
                prop.obj("value")["bits"] = maxFriend
                changed = true
            }
        }
        return changed
    }
}

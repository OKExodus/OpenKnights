package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.*
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.store.StateStore
import io.github.okexodus.openknights.server.store.WorldDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Local comparison against private reference packets, including real captain Power calculations. */
class LeaderboardVectorsTest {
    @Test
    fun `all categories match reference packets on recorded states`() {
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")
        assumeTrue(dev != null && originals != null, "private inputs not configured")
        val file = Path.of(dev!!, "leaderboards", "vectors.json")
        assumeTrue(Files.isRegularFile(file), "private leaderboard vectors not generated")
        val vectors = Json.loads(Files.readString(file)).asObj
        val tables = GameTables(ApkTables(Path.of(originals!!, "com.enjoygame.hero2d.apk")))
        val inputs = DailyInputs(tables)
        val evolution = EvolutionInputs(tables)
        val states = linkedMapOf<Long, StateStore.Current>()
        val people = vectors.arr("saves").map { value ->
            val entry = value.asObj
            val save = entry.obj("save")
            val current = StateStore.Current(save.long("revision"), "", "", save.obj("state"), ByteArray(0), 0, null,
                save.arr("inventory_items"), emptyList(), save.arr("acquired_items"), emptyList(), null, null, null,
                save["character_profile"] as? JObj, null, save.obj("documents"))
            WorldParticipants.characterParticipant(entry.obj("member"), current,
                BattleStats.participantPower(null, evolution, inputs)).also { states[it.participantId] = current }
        }
        val weekly = vectors.obj("weekly").map { (id, metrics) -> id.toLong() to metrics.asObj.mapValues { it.value.long } }.toMap()
        for (value in vectors.arr("cases")) {
            val case = value.asObj
            val type = case.long("type")
            val rows = when {
                type == 12L || type == 13L -> Leaderboards.guildRows(type, vectors.obj("guilds"), people, inputs)
                type in Leaderboards.EXTENDED_TYPES -> Leaderboards.playerRows(type, people, states, weekly) {
                    Leaderboards.captainPower(it, inputs, null, evolution)
                }
                else -> people.map { WorldDirectory.participantRankRow(it) }
            }
            val reply = WorldDirectory.buildRankReply(type, case.long("page"), rows, case.long("requester"))
            assertEquals(case.str("packet"), WorldDirectory.encodeRankReply(reply).toHexString(), "type $type page ${case.long("page")}")
        }
    }
}

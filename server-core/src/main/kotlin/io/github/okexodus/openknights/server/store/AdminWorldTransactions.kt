package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.game.AdminGuild
import io.github.okexodus.openknights.server.game.DailyInputs
import io.github.okexodus.openknights.server.game.Owned

/** The guild edit and the caller's provenance share one SQLite rollback-journal transaction. */
fun StateStore.adminGuild(world: WorldDirectory, command: String, args: List<String>, inputs: DailyInputs, now: Long,
                          expectedGuild: Long? = null): Long = connect().use { db ->
    db.execute("ATTACH DATABASE ? AS admin_world", world.path.toString())
    // Multi-file atomic commits require disk-backed rollback journals, used by both shipped drivers.
    for (schema in listOf("main", "admin_world")) {
        val mode = db.queryOne("PRAGMA $schema.journal_mode")!![0].toString().lowercase()
        require(mode in setOf("delete", "truncate", "persist")) { "Guild commands require rollback journals." }
    }
    db.immediate {
        val current = read(db)
        val row = db.queryOne("SELECT revision,document_json FROM admin_world.world_documents WHERE name='guilds'")
            ?: error("Guild document is missing")
        val doc = Json.loads(row.string("document_json")).asObj
        val owned = Owned(current, inputs)
        if (expectedGuild != null) require(io.github.okexodus.openknights.server.game.Guild.guildOf(doc,
            io.github.okexodus.openknights.server.game.SocialRoutes.roleOf(current)).first == expectedGuild) {
            "Your guild changed. Enter /disbandguild again."
        }
        val gid = when (command) {
            "createguild" -> AdminGuild.createGuild(doc, owned, current, inputs, args.single().toByteArray(Charsets.UTF_8), now)
            "leaveguild" -> AdminGuild.leaveGuild(doc, owned, current, inputs, now)
            "disbandguild" -> AdminGuild.disbandGuild(doc, owned, current, inputs, now, confirmed = true)
            else -> error("Unknown guild command")
        }
        val revision = row.long("revision") + 1
        db.execute("UPDATE admin_world.world_documents SET revision=?,document_json=?,updated_at_utc=? WHERE name='guilds'",
            revision, Json.canonical(doc), PyTime.nowIsoMillis())
        // Administrative details remain in the character's private history. Shared world history has only the change.
        db.execute("INSERT INTO admin_world.world_history(timestamp_utc,actor,action,entry_id,character_id,detail_json) VALUES(?,?,?,NULL,NULL,?)",
            PyTime.nowIsoMillis(), "local-service", "guild_update", Json.canonical(jobj("name" to "guilds", "revision" to revision)))
        commitState(db, current, "admin_command", jobj("command" to command, "guild" to gid))
        gid
    }
}

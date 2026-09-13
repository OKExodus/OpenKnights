package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.protocol.PlayerState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creating saves (`StateStore.initialize` of the reference): a complete, losslessly decoded full player record becomes
 * revision 1 with its history row and the (empty) checksum-bound inventory, published without overwriting anything.
 * Fresh characters (with their profile and god-skill documents) are created by the character-creation system.
 */
object SaveWriter {
    fun initialize(path: Path, payload: ByteArray, driver: SqlDriver): StateStore {
        val state = PlayerState.parse(payload)
        val mode = (state["login_mode"] as JInt).value.toLong()
        require(mode != 1L && mode != 2L) { "A status-only login reply cannot seed a full player save" }
        require(state["complete"] == JBool(true) && PlayerState.encode(state).contentEquals(payload)) {
            "Refusing to seed state from an incomplete or non-lossless decode"
        }
        val target = path.toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        val temporary = Publish.temporaryBeside(target, "init", ".sqlite3")
        try {
            Files.delete(temporary)
            driver.open(temporary, SqlDriver.Mode.CREATE).use { db ->
                db.execute(Schemas.SAVE.getValue("player_state"))
                db.execute(Schemas.SAVE.getValue("state_history"))
                val timestamp = PyTime.nowIsoMillis()
                val checksum = sha256Hex(payload)
                db.immediate {
                    ensureInventorySchema(db)
                    db.execute("INSERT INTO player_state VALUES(1,1,1,?,?,?,?)", checksum, checksum, timestamp, StateStore.stateJson(state))
                    db.execute("INSERT INTO state_history VALUES(1,?,?,?,?)", timestamp, "initialize", "{}", checksum)
                }
            }
            Publish.publishNew(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return StateStore(target, driver)
    }

    /** The additive inventory tables with the checksum of an empty inventory (inside the caller's transaction). */
    fun ensureInventorySchema(db: SqlConnection) {
        for (table in listOf("inventory_meta", "inventory_batches", "inventory_records", "inventory_retired_uids")) {
            db.execute(Schemas.SAVE.getValue(table))
        }
        if (db.queryOne("SELECT 1 FROM inventory_meta WHERE id=1") == null) {
            val checksum = sha256Hex(StateStore.stateJson(jobj("batches" to JArr(), "retired" to JArr())).toByteArray())
            db.execute("INSERT INTO inventory_meta VALUES(1,1,?)", checksum)
        }
    }
}

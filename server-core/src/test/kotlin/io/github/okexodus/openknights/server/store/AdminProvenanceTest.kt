package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.exact.jobj
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdminProvenanceTest {
    private val driver = JdbcSqlDriver()

    @TempDir lateinit var temp: Path

    private fun save(name: String = "save.sqlite3"): Pair<Path, StateStore> {
        val path = temp.resolve(name)
        SaveWriter.initialize(path, PlayerState.encode(StoreTest.minimalState()), driver)
        return path to StateStore(path, driver)
    }

    @Test
    fun `legacy save has no marker and remains unflagged`() {
        val (_, store) = save()
        assertFalse(store.read().adminCommandsUsed)
        store.connect(readOnly = true).use { db -> assertFalse(db.tableExists(AdminProvenance.TABLE)) }
    }

    @Test
    fun `command flag survives marker deletion and later commits repeat evidence`() {
        val (_, store) = save()
        store.connect().use { db ->
            db.immediate {
                val current = store.read(db)
                store.commitState(db, current, "admin_command", jobj("command" to "help"))
            }
        }
        assertTrue(store.read().adminCommandsUsed)
        store.connect().use { db -> db.execute("DELETE FROM ${AdminProvenance.TABLE}") }
        assertTrue(store.read().adminCommandsUsed)
        store.connect().use { db ->
            db.immediate {
                val current = store.read(db)
                store.commitState(db, current, "normal_follow_up", jobj("ok" to true))
            }
        }
        store.connect().use { db ->
            assertTrue(db.query("SELECT 1 FROM state_history WHERE revision=3 AND detail_json LIKE '%admin_provenance%'").isNotEmpty())
        }
    }

    @Test
    fun `edited marker checksum fails closed`() {
        val (_, store) = save()
        store.connect().use { db ->
            db.immediate {
                val current = store.read(db)
                store.commitState(db, current, "admin_command", jobj("command" to "debug"))
            }
            db.execute("UPDATE ${AdminProvenance.TABLE} SET used=0")
        }
        assertTrue(store.read().adminCommandsUsed)
    }

    @Test
    fun `admin history alone flags a save`() {
        val (_, store) = save()
        store.connect().use { db ->
            val checksum = db.queryOne("SELECT payload_sha256 FROM player_state WHERE id=1")!!.string("payload_sha256")
            db.execute("INSERT INTO state_history VALUES(2,?,?,?,?)", "2026-01-01T00:00:00.000Z", "admin_command", "{\"command\":\"debug\"}", checksum)
        }
        assertTrue(store.read().adminCommandsUsed)
    }

    @Test
    fun `copy made before command remains unflagged`() {
        val (path, store) = save()
        val backup = temp.resolve("before-command.sqlite3")
        Files.copy(path, backup)
        store.connect().use { db ->
            db.immediate {
                val current = store.read(db)
                store.commitState(db, current, "admin_command", jobj("command" to "debug"))
            }
        }
        assertTrue(store.read().adminCommandsUsed)
        assertFalse(StateStore(backup, driver).read().adminCommandsUsed)
    }

    @Test
    fun `logical backup retains marker even when command history is removed`() {
        val (path, store) = save()
        store.markAdminCommand("help")
        store.connect().use { db -> db.execute("DELETE FROM state_history") }
        assertTrue(store.read().adminCommandsUsed)
        val copy = temp.resolve("logical-copy.sqlite3")
        LogicalCopy.copyDatabase(path, copy, driver)
        assertTrue(StateStore(copy, driver).read().adminCommandsUsed)
    }

    @Test
    fun `rolled back command does not leave a flag`() {
        val (_, store) = save()
        try {
            store.connect().use { db -> db.immediate {
                val current = store.read(db)
                store.commitState(db, current, "admin_command", jobj("command" to "rejected"))
                error("reject transaction")
            } }
        } catch (_: IllegalStateException) { }
        assertFalse(store.read().adminCommandsUsed)
    }
}

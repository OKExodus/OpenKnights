package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.server.DeviceClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class StoreTest {
    private val driver = JdbcSqlDriver()

    companion object {
        private fun list() = jobj("count" to 0, "entries" to JArr())

        /** A made-up full player record with every section present and empty. */
        fun minimalState(): JObj {
            val sections = JObj()
            for (name in listOf("gems", "technologies", "hero_collection", "equip_collection", "jewelry_collection", "achievements",
                "buildings", "buffs", "friends")) sections[name] = list()
            sections["alchemy"] = jobj("wire_values" to List(9) { 0 })
            sections["vip"] = jobj("wire_values" to List(5) { 0 })
            sections["stages"] = jobj("stages" to list(), "tail" to list())
            sections["servants"] = jobj("wire_u8_prefix" to listOf(0, 0), "servants" to list(), "optional_servant" to null)
            sections["xinggong"] = jobj("wire_u32_prefix" to List(4) { 0 }, "entries" to list())
            sections["game_activities"] = jobj("wire_u8_prefix" to 0, "first_list" to list(), "second_list" to list(),
                "wire_u8_after_lists" to 0, "update_u32" to 0, "update_conditional_u8" to null, "roulette" to null, "roulette_items" to null)
            return jobj("reset_player" to 0, "login_mode" to 3, "role_properties" to JArr(), "equipment" to JArr(), "heroes" to JArr(),
                "offline_hero_uids" to JArr(), "bag_equipment_uids" to JArr(), "formation" to JArr(), "captain_slot" to 0,
                "item_capacity_values" to listOf(0, 0, 0), "items" to JArr(), "subsystems" to sections, "servant_messages" to JArr(),
                "title_reward_flag" to 0, "final_flags" to listOf(0, 0, 0, 0), "unparsed_tail_hex" to "")
        }
    }

    @TempDir
    lateinit var tmp: Path

    /** An unborn root: a generation with only the registry, the owner account and sign-in sessions. */
    private fun unbornRoot(clock: () -> Long = { System.currentTimeMillis() / 1000 }): Triple<DataRoot, AccountRegistry, LocalAuth> {
        val root = DataRoot(tmp.resolve("root"), driver).open()
        val generation = root.unbornStaging()
        val registry = AccountRegistry.initialize(generation.resolve(DataRoot.REGISTRY), driver)
        LocalAuth.initialize(registry, actor = "release-service")
        registry.createAccount("owner", "a-long-random-password-nobody-uses", actor = "release-service")
        val auth = LocalAuth(AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true), clock)
        auth.deviceOwner = "owner"
        return Triple(root, registry, auth)
    }

    @Test
    fun `a new data root is unborn until a world is committed`() {
        val (root, _, auth) = unbornRoot()
        assertFalse(root.born)
        val manifest = root.readManifest()!!
        assertEquals("openknights_data_root", manifest.str("format"))
        assertTrue(manifest.isNull("active"))
        val issued = auth.deviceLogin()
        assertEquals(43, issued.token.length)
        assertEquals(issued.session.accountId, auth.authenticate(issued.token).accountId)
        // A second open reuses the unborn generation instead of making another.
        val again = DataRoot(root.root, driver).open()
        assertEquals(root.unbornStaging(), again.unbornStaging())
        assertTrue(again.recovered.isEmpty())
    }

    @Test
    fun `sessions slide and a device-owner session survives its expiry`() {
        var now = 1_800_000_000L
        val (_, registry, auth) = unbornRoot { now }
        val issued = auth.deviceLogin(ttlSeconds = 100)
        now += 60                                // less than half left: renewed to a fresh 100 s
        auth.authenticate(issued.token)
        val renewed = registry.connect(readOnly = true).use { it.queryOne("SELECT expires_epoch FROM local_sessions")!!.long("expires_epoch") }
        assertEquals(now + 100, renewed)
        now += 500                               // expired: the device owner's session is renewed anyway
        auth.authenticate(issued.token)
        val history = registry.connect(readOnly = true).use { db -> db.query("SELECT action,detail_json FROM registry_history ORDER BY sequence").map { it.string("action") } }
        assertEquals(listOf("local_sessions_initialized", "account_created", "session_issued", "session_renewed", "session_renewed"), history)
        auth.deviceOwner = null                  // without a device owner an expired session is refused
        now += 500
        assertThrows(AuthenticationRejected::class.java) { auth.authenticate(issued.token) }
        assertThrows(AuthenticationRejected::class.java) { auth.authenticate("x".repeat(43)) }
    }

    @Test
    fun `a world is born in one manifest commit with its documents and bots database`() {
        val (root, _, _) = unbornRoot()
        val generation = root.unbornStaging()
        val seed = WorldDirectory.newSeed()
        val world = WorldDirectory.initialize(generation.resolve(DataRoot.WORLD), driver, seed)
        BotsDatabase.initialize(generation.resolve(DataRoot.BOTS), driver, seed)
        root.commitActive(generation.fileName.toString(), "world born with the first character")
        assertTrue(root.born)
        assertEquals(seed, world.worldSeed())
        assertEquals(11, world.connect(readOnly = true).use { it.query("SELECT name FROM world_documents").size })
        val door = world.document("royal_door")!!
        assertEquals(1L, door.first)
        assertEquals(1L, door.second.long("level"))
        val schemas = root.readManifest()!!.obj("schemas")
        assertEquals(2L, schemas.long("registry"))
        assertEquals(4L, schemas.long("world"))
        assertEquals(1L, schemas.long("bots"))
        val first = Fingerprint.compute(root, driver)
        assertEquals(Fingerprint.sha256(first), Fingerprint.sha256(Fingerprint.compute(DataRoot(root.root, driver).open(), driver)))
        assertEquals(setOf("bots.sqlite3", "registry.sqlite3", "world.sqlite3"), first.obj("databases").keys)
    }

    @Test
    fun `publishing never overwrites and clears a crashed publisher's claim`() {
        val target = tmp.resolve("file.sqlite3")
        val first = Publish.temporaryBeside(target, "init", ".sqlite3")
        Files.writeString(first, "one")
        Publish.publishNew(first, target)
        val second = Publish.temporaryBeside(target, "init", ".sqlite3")
        Files.writeString(second, "two")
        assertThrows(FileAlreadyExistsException::class.java) { Publish.publishNew(second, target) }
        assertEquals("one", Files.readString(target))
        val other = tmp.resolve("other.sqlite3")
        Files.createFile(tmp.resolve("other.sqlite3.claim"))
        assertThrows(FileAlreadyExistsException::class.java) { Publish.publishNew(second, other) }
        assertFalse(Files.exists(other))
        val moved = Publish.clearStaleClaims(tmp, tmp.resolve("quarantine"))
        assertTrue("other.sqlite3.claim" in moved)
        assertFalse(Files.exists(second))            // the unpublished temporary file is quarantined too
        val third = Publish.temporaryBeside(other, "init", ".sqlite3")
        Files.writeString(third, "two")
        Publish.publishNew(third, other)
        assertEquals("two", Files.readString(other))
    }

    @Test
    fun `the device clock never goes back and its day turns at local midnight`() {
        var real = 1_800_000_000L                  // 2027-01-15T08:00:00Z
        val offset = -6 * 3600
        val path = tmp.resolve("clock.json")
        val clock = DeviceClock(path, { real }, { offset })
        val start = clock.now()
        assertEquals("2027-01-15", clock.localDay(start))
        real -= 86400 * 3                          // the device clock is set back: time stands still
        assertEquals(start, clock.now())
        assertEquals("2027-01-15", clock.localDay(clock.now()))
        val midnight = clock.nextDayStart(start)
        assertEquals(0L, (midnight + offset) % 86400)  // the next local midnight
        real = midnight + 60                       // past local midnight
        assertEquals("2027-01-16", clock.localDay(clock.now()))
        val reopened = DeviceClock(path, { real }, { offset })
        assertEquals("2027-01-16", reopened.hwmDay)
        val s14 = clock.s14()
        assertEquals(8, s14.size)
        assertEquals(offset, java.nio.ByteBuffer.wrap(s14).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4))
    }

    @Test
    fun `a save reads back with every integrity check and refuses a changed record`() {
        val payload = PlayerState.encode(minimalState())
        val path = tmp.resolve("save.sqlite3")
        SaveWriter.initialize(path, payload, driver)
        val store = StateStore(path, driver)
        val current = store.read()
        assertEquals(1L, current.revision)
        assertEquals(sha256Hex(payload), current.payloadSha256)
        assertEquals(1, current.inventorySchemaVersion)
        // Changing one value of the stored record breaks the payload checksum.
        store.connect().use { db ->
            val text = db.queryOne("SELECT state_json FROM player_state")!!.string("state_json")
            db.execute("UPDATE player_state SET state_json=?", text.replaceFirst("\"reset_player\":", "\"reset_player\":1").let {
                if (it == text) text.replaceFirst("\"captain_slot\":", "\"captain_slot\":1") else it
            })
        }
        assertThrows(IllegalArgumentException::class.java) { store.read() }
    }

    @Test
    fun `a logical copy has the same schema and fingerprint`() {
        val (root, registry, auth) = unbornRoot()
        auth.deviceLogin()
        val copy = tmp.resolve("copy.sqlite3")
        LogicalCopy.copyDatabase(registry.path, copy, driver)
        assertTrue(LogicalCopy.schemaDifferences(registry.path, copy, driver).isEmpty())
        assertEquals(Fingerprint.database(registry.path, driver), Fingerprint.database(copy, driver))
        val generation = root.unbornStaging()
        val world = WorldDirectory.initialize(generation.resolve(DataRoot.WORLD), driver)
        val worldCopy = tmp.resolve("world-copy.sqlite3")
        LogicalCopy.copyDatabase(world.path, worldCopy, driver)
        assertTrue(LogicalCopy.schemaDifferences(world.path, worldCopy, driver).isEmpty())
        assertEquals(Fingerprint.database(world.path, driver), Fingerprint.database(worldCopy, driver))
        assertNotEquals(Fingerprint.database(world.path, driver), Fingerprint.database(registry.path, driver))
    }
}

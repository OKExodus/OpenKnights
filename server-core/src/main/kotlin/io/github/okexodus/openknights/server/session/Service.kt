package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.ReleaseData
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.LocalAuth
import io.github.okexodus.openknights.server.store.SqlDriver
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

/** The service log: one JSON object per line (`timestamp_utc`, `event`, fields), to standard output and a file. */
class ServiceLog(file: Path? = null, private val echo: Boolean = true) : AutoCloseable {
    private val out: Writer? = file?.let {
        Files.createDirectories(it.toAbsolutePath().parent)
        Files.newBufferedWriter(it, Charsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
    val events = ArrayList<JObj>()
    var keep = false

    @Synchronized
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val entry = jobj("timestamp_utc" to PyTime.nowIsoMillis(), "event" to event)
        fields.forEach { (k, v) -> entry[k] = jvalue(v) }
        val line = Json.dumps(entry)
        if (echo) println(line)
        out?.apply { write(line); write("\n"); flush() }
        if (keep) events.add(entry)
    }

    override fun close() { out?.close() }
}

/**
 * The running service's shared state (the reference's `snapshot` object in release mode): the data root, the device
 * clock, the release data, the game tables, the registry and sign-in, the world (null before the first character),
 * the in-game character list, the open game sessions and the hooks later systems attach to.
 */
class Service(
    val driver: SqlDriver,
    val log: ServiceLog,
    val clock: DeviceClock,
    val tables: GameTables,
    val releaseData: ReleaseData?,
    val auth: LocalAuth,
    var world: WorldDirectory?,
    val select: CharacterSelect,
    val dataRoot: DataRoot? = null,
    val generation: Path? = null,
    /** Dev layouts only: the captured wire id of characters derived from a capture (fresh characters carry their own). */
    val capturedWireAccountId: Long? = null,
) {
    /** Open game sessions (server-initiated pushes go through here: chat lines, mail, presence; bots later). */
    val liveGameSessions = LinkedHashMap<Session, (List<Pair<Int, ByteArray>>) -> Unit>()

    /**
     * Called at service start and at every heartbeat (plan §5b): the place where time-based catch-up runs — on the
     * device the server only runs while the app runs. Nothing registers yet.
     */
    val settleHooks = ArrayList<(reason: String, now: Long) -> Unit>()

    /** Whether the in-game list offers "Create a character" (always in release mode). */
    var creationEnabled = true

    /** Character creation (world birth on the first); null until the character-creation system is ported. */
    var characterFactory: ((accountId: String, name: String, gender: Int, starter: Long) -> String)? = null

    fun settle(reason: String) {
        val now = clock.now()
        settleHooks.forEach { it(reason, now) }
    }

    /** Frames to every open, initialised game session of a participant (the reference's `push_to_role`). */
    @Synchronized
    fun pushToRole(role: Long, frames: List<Pair<Int, ByteArray>>, origin: Session? = null): Int {
        var delivered = 0
        for ((session, writer) in liveGameSessions.entries.toList()) {
            if (session === origin || session.closed || !session.queries.complete || session.role != role) continue
            writer(frames)
            delivered++
        }
        if (delivered > 0) log.log("response_batch", "service" to "game", "opcodes" to frames.map { it.first }, "pushed" to "social",
            "to_role" to role, "bytes" to frames.sumOf { it.second.size + 4 })
        return delivered
    }

    companion object {
        const val OWNER = "owner"

        /**
         * Release mode (`release_mode.configure_release`): only the player's APK, the release data and one data root.
         * The world is born with the first character; until then the owner's sign-in sessions wait in an unborn
         * generation. The data root's lock is held until [close].
         */
        fun release(driver: SqlDriver, dataRoot: Path, apk: Path, releaseData: Path, log: ServiceLog, loaded: GameTables? = null): Service {
            val data = ReleaseData(releaseData)
            val tables = loaded ?: GameTables(ApkTables(apk))
            log.log("release_apk_bound", "label" to "Pocket Knights 4.4.9", "tables" to tables.names().size,
                "note" to "game tables from the player's APK only; no download overlay (D1)")
            val root = DataRoot(dataRoot, driver).open()
            root.lock.acquire()
            if (root.recovered.isNotEmpty()) log.log("data_root_recovered", "moved" to root.recovered)
            val clock = DeviceClock(root.clockPath)
            DeviceClock.active = clock
            val copies = io.github.okexodus.openknights.server.store.SaveManagement.startupCopies(root, driver, clock, log)
            val generation: Path
            val registry: AccountRegistry
            val world: WorldDirectory?
            if (root.born) {
                generation = root.active!!
                registry = AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
                world = WorldDirectory(generation.resolve(DataRoot.WORLD), driver, strictPaths = true)
            } else {
                generation = root.unbornStaging()
                registry = ownerRegistry(generation, driver)
                world = null
            }
            val auth = LocalAuth(registry).also { it.deviceOwner = OWNER }
            val texts = data.startupDefaults().obj("character_select")
            val select = CharacterSelect(data.offers(), CharacterSelect.labeledRowIds(tables), texts.str("announcement"), texts.str("create_row"))
            log.log("release_data_bound", "files" to data.manifest.obj("files").size, "gate" to data.gate().str("routes"),
                "born" to root.born, "generation" to generation.fileName.toString(), "safety_copies" to copies)
            return Service(driver, log, clock, tables, data, auth, world, select, root, generation)
        }

        /** The unborn generation's registry with the implicit device owner (never a world). */
        fun ownerRegistry(folder: Path, driver: SqlDriver): AccountRegistry {
            val path = folder.resolve(DataRoot.REGISTRY)
            if (!Files.exists(path)) {
                val registry = AccountRegistry.initialize(path, driver)
                LocalAuth.initialize(registry, actor = "release-service")
                // The account schema requires a password: random, never shown, never used (device sign-in).
                registry.createAccount(OWNER, io.github.okexodus.openknights.server.Entropy.current.tokenUrlsafe(32), actor = "release-service")
            }
            return AccountRegistry(path, driver, strictPaths = true)
        }
    }

    fun close() {
        clock.checkpoint()
        if (DeviceClock.active === clock) DeviceClock.active = null
        dataRoot?.lock?.release()
    }
}

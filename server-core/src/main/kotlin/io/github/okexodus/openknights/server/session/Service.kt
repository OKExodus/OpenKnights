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

    /** Character creation (world birth on the first): returns the created character (`character_id`, …). */
    var characterFactory: ((accountId: String, name: String, gender: Int, starter: Long, actor: String) -> JObj)? = null

    /** The catalog inputs of every game system (`snapshot.acquisition_inputs`: the daily inputs over the APK tables). */
    val inputs: io.github.okexodus.openknights.server.game.DailyInputs by lazy { io.github.okexodus.openknights.server.game.DailyInputs(tables) }

    /** The release data's day-zero seed frames of the fresh systems (`snapshot.fresh_systems`). */
    val freshSystems: Map<Int, List<ByteArray>>? by lazy { releaseData?.let { io.github.okexodus.openknights.server.game.SystemSeeds.freshSystems(it) } }

    /** The acquisition catalog (`snapshot.acquisition_catalog`: release-data/shop-catalog.json). */
    val acquisitionCatalog: JObj? by lazy { releaseData?.let { io.github.okexodus.openknights.server.game.Shops.releaseCatalog(it) } }

    /** The hero / gear Fortify catalog inputs (`snapshot.fortify_inputs`). */
    val fortifyInputs: io.github.okexodus.openknights.server.game.FortifyInputs by lazy { io.github.okexodus.openknights.server.game.FortifyInputs(tables) }

    /** The EXP-item Fortify catalog inputs (`snapshot.item_fortify_inputs`). */
    val itemFortifyInputs: io.github.okexodus.openknights.server.game.ItemFortifyInputs by lazy { io.github.okexodus.openknights.server.game.ItemFortifyInputs(tables) }

    /** The history label `{path, sha256}` of the loaded acquisition RNG policy (`ReleaseData.policy("acquisition-rng")`). */
    val acquisitionPolicyLabel: JObj? by lazy {
        releaseData?.let { jobj("path" to it.label("policies/acquisition-rng.json"), "sha256" to it.sha256("policies/acquisition-rng.json")) }
    }

    /** The labeled local RNG policy document (`snapshot.acquisition_policy`, bound to every character in release). */
    var acquisitionPolicy: JObj? = null

    /** `acquisition.policy_allows(policy, key)`: an optional draw the policy names with its one allowed value. */
    fun policyAllows(key: String): Boolean {
        val allowed = mapOf("lucky_refresh" to "uniform_over_observed_pools", "fuse_roll" to "displayed_rate_plus_luck_v1")
        val document = acquisitionPolicy ?: return false
        return document.strOrNull(key) == allowed.getValue(key)
    }

    /** The evolution rows (`snapshot.evolution_inputs` / `leader_inputs`). */
    val evolutionInputs: io.github.okexodus.openknights.server.game.EvolutionInputs by lazy { io.github.okexodus.openknights.server.game.EvolutionInputs(tables) }

    /** The hero-card catalog inputs (`snapshot.hero_card_inputs`: Power Up, Astral Power, Ascension). */
    val heroCardInputs: io.github.okexodus.openknights.server.game.HeroCardInputs by lazy { io.github.okexodus.openknights.server.game.HeroCardInputs(tables) }

    /** The verified client lineup rules (`snapshot.secondary_rules`): loaded by the first C3779 of the process, then kept. */
    var secondaryRules: io.github.okexodus.openknights.server.game.SecondaryTeam.NativeLineupRules? = null

    /** The universal Power of a character save (`snapshot.power_of` = `battle_stats.participant_power(snapshot)`). */
    var powerOf: ((io.github.okexodus.openknights.server.store.StateStore.Current) -> java.math.BigInteger?)? = null

    /**
     * The labeled local policies of the hero systems in their loaded shape (`ReleaseData.policy`; `configure_release`):
     * evolution test tiers, item-Fortify bonus draws, Power Up draws, ordinary-Ascension materials. A session applies
     * them through its bound-policy check.
     */
    var evolutionTestPolicy: JObj? = null
    var fortifyBonusPolicy: JObj? = null
    var powerUpPolicy: JObj? = null
    var ascensionPolicy: JObj? = null

    fun settle(reason: String) {
        val now = clock.now()
        settleHooks.forEach { it(reason, now) }
    }

    /**
     * Frames to every open, initialised game session of a participant (the reference's `push_to_role`); a builder
     * makes the frames against each recipient's own clock offset (times shown by the client).
     */
    @Synchronized
    fun pushToRole(role: Long, origin: Session? = null, frames: (clockOffset: Long) -> List<Pair<Int, ByteArray>>): Int {
        var delivered = 0
        var sent: List<Pair<Int, ByteArray>> = emptyList()
        for ((session, writer) in liveGameSessions.entries.toList()) {
            if (session === origin || session.closed || !session.queriesSent) continue
            if (session.socialRole != role) continue
            sent = frames(session.clockOffset)
            writer(sent)
            delivered++
        }
        if (delivered > 0) log.log("response_batch", "service" to "game", "opcodes" to sent.map { it.first }, "pushed" to "social",
            "to_role" to role, "bytes" to sent.sumOf { it.second.size + 4 })
        return delivered
    }

    fun pushToRole(role: Long, frames: List<Pair<Int, ByteArray>>, origin: Session? = null): Int = pushToRole(role, origin) { frames }

    /** Wire role ids of the characters with an open, initialized game session (`online_roles`), sorted. */
    fun onlineRoles(): List<Long> = liveGameSessions.keys.mapNotNull { s -> s.socialRole?.takeIf { s.queriesSent && !s.closed } }.toSortedSet().toList()

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
            data.tables = tables
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
            // Refuse an inconsistent root before accepting any client: every registered character is a fresh
            // character of this world with its own wire id.
            for (owned in registry.listCharacters()) {
                val current = registry.resolveStateStore(owned.characterId, owned.accountId).read()
                val profile = current.characterProfile ?: throw IllegalArgumentException("Release mode serves only characters created in release mode")
                val member = world?.member(owned.characterId)
                if (member == null || member.long("wire_account_id") != profile.obj("document").long("wire_account_id")) {
                    throw IllegalArgumentException("A character of the data root is not an active member of its world")
                }
                io.github.okexodus.openknights.server.game.FreshProfile.freshStartup(current, owned.characterId, clock.s14())
            }
            val service = Service(driver, log, clock, tables, data, auth, world, select, root, generation)
            service.acquisitionPolicy = checkAcquisitionPolicy(data.document("policies/acquisition-rng.json"))
            service.evolutionTestPolicy = io.github.okexodus.openknights.server.game.HeroEvolution.checkTestPolicy(data.policy("evolution")) as JObj?
            service.fortifyBonusPolicy = io.github.okexodus.openknights.server.game.ItemFortify.checkBonusPolicy(data.policy("fortify-bonus"))
            service.powerUpPolicy = io.github.okexodus.openknights.server.game.HeroPowerUp.checkPolicy(data.policy("power-up")) as JObj?
            service.ascensionPolicy = io.github.okexodus.openknights.server.game.HeroAscension.checkMaterialPolicy(data.policy("ascension")) as JObj?
            io.github.okexodus.openknights.server.game.Events.setActive(io.github.okexodus.openknights.server.game.Events.releaseEvents(data))
            service.acquisitionCatalog
            service.freshSystems
            service.powerOf = io.github.okexodus.openknights.server.game.BattleStats.participantPower(service.freshSystems, service.evolutionInputs, service.inputs)
            world?.powerOf = service.powerOf
            val factory = ReleaseFactory(service, root, generation, data.freshTemplate())
            service.characterFactory = { accountId, name, gender, starter, actor -> factory.create(accountId, name, gender, starter, actor) }
            return service
        }

        /** `check_acquisition_policy`: the labeled local RNG policy document (never presented as recovered odds). */
        fun checkAcquisitionPolicy(document: JObj): JObj {
            if (document.strOrNull("profile") != "acquisition_rng_policy_v1" || document.strOrNull("class") != "preservation_policy_rng") {
                throw IllegalArgumentException("Acquisition policy must be profile acquisition_rng_policy_v1, class preservation_policy_rng")
            }
            // release policies are bound to every character (scope all_characters or listed with the loaded set)
            if (document.strOrNull("scope") !in setOf("all_characters", "listed_characters")) {
                throw IllegalArgumentException("Acquisition policy scope must be all_characters or listed_characters")
            }
            if (listOf(document["box_draw"], document["summon_draw"], document["roulette_draw"]).map { (it as? io.github.okexodus.openknights.exact.JStr)?.value } !=
                listOf("weighted_row_per_slot", "configured_weights_v1", "uniform_over_evidenced_slots")) {
                throw IllegalArgumentException("Acquisition policy draws must be weighted_row_per_slot / configured_weights_v1 / uniform_over_evidenced_slots")
            }
            for ((key, value) in listOf("lucky_refresh" to "uniform_over_observed_pools", "fuse_roll" to "displayed_rate_plus_luck_v1")) {
                if (key in document && document.strOrNull(key) != value) throw IllegalArgumentException("Acquisition policy $key must be $value")
            }
            return document
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

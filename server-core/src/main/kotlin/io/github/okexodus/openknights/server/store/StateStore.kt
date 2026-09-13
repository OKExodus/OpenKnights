package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.protocol.Inventory
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.jvalue
import io.github.okexodus.openknights.server.game.Acquisition
import io.github.okexodus.openknights.server.game.AcquisitionInputs
import io.github.okexodus.openknights.server.game.EquipFormation
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.GodSkills
import io.github.okexodus.openknights.server.game.Owned
import io.github.okexodus.openknights.server.game.Plan
import io.github.okexodus.openknights.server.game.SecondaryTeam
import io.github.okexodus.openknights.server.game.VipQuest
import java.nio.file.Files
import java.nio.file.Path

/**
 * One character's save (`state_store.py`): the single-row player record (the decoded opcode-18 tree as insertion-order
 * JSON, verified by the SHA-256 of its re-encoded payload), the revision history, the checksum-bound inventory,
 * acquired stacks and system documents, and about forty plain per-character documents.
 *
 * [read] performs every storage-level integrity check of the reference's read. The game-level checks that sit on top
 * of some documents (alternate-team references regenerated from owned heroes, god-skill document rules, the history
 * hero sets) belong to their systems and come with them in the port of the game systems.
 */
class StateStore(path: Path, private val driver: SqlDriver) {
    val path: Path = path.toAbsolutePath().normalize()

    /** The stored root-relative path (set when the registry resolves the save) and the directory it is relative to. */
    var key: String? = null
    var root: Path? = null

    companion object {
        /** Plain per-character document tables (`ACQUISITION_DOCUMENTS` values + summon_state), no checksum. */
        val DOCUMENT_TABLES = listOf("shop_state", "lucky_state", "buyback_state", "fuse_luck_state", "recharge_ledger",
            "vip_state", "month_cards", "vip_quest", "palace_state", "magic_pie_state", "sign_in_state", "salary_state",
            "daily_mission_state", "royal_door_state", "quest_state", "bounty_board", "arena_state", "event_state",
            "exchange_state", "castle_state", "guild_tech_state", "training_state", "forge_state", "explore_state",
            "guild_task_state", "mail_state", "campaign_state", "totem_state", "tmp_vip", "sgxj_state", "rebirth_shop",
            "album_state", "buff_state", "gift_codes", "alt_team", "roulette_claims", "roulette_day", "goal_state")
        const val SUMMON_STATE = "summon_state"
        const val INJECT_ACTION = "test_fixture_inject_hero"

        /** Every acquisition-transaction action (`ACQUISITION_ACTIONS`, reference order). */
        val ACQUISITION_ACTIONS = listOf("acquire_item_use", "acquire_choose_box", "acquire_merge", "acquire_summon",
            "acquire_shop_buy", "acquire_lucky_exchange", "acquire_roulette", "acquire_decompose",
            "acquire_recharge", "acquire_sell", "acquire_buyback", "acquire_refine", "acquire_fuse",
            "claim_activity_row", "claim_vip_daily", "claim_month_card", "vip_daily_reset",
            "claim_vip_quest", "rebirth_evolve", "rebirth_fortify", "vip_buy",
            "event_palace_visit", "event_palace_claim", "event_magic_pie", "daily_check_in",
            "daily_time_gift", "daily_salary", "daily_mission_gift", "royal_door_daily",
            "royal_door_level_up", "royal_door_donate", "quest_claim",
            "castle_collect", "castle_building", "castle_tech", "castle_guild_tech", "castle_transmute",
            "castle_alchemy_refresh", "castle_buy_slot", "castle_work", "castle_release", "castle_guard", "bounty_task", "arena_reward", "daily_login_refresh",
            "daily_counters", "event_combine", "event_exchange",
            "training_create_room", "training_seat", "training_password", "training_claim",
            "training_add_time", "forge_smith", "forge_craft", "forge_no_cd", "explore_task",
            "explore_claim", "card_sacrifice", "card_reforge",
            "guild_create", "guild_donate", "guild_wage", "guild_emblem", "guild_rename", "guild_task",
            "guild_task_claim", "friend_praise", "mail_claim",
            "set_signature", "warehouse_capacity", "totem_lineup", "tmp_vip_claim", "event_great_offer",
            "rebirth_shop_roll", "rebirth_shop_refresh", "rebirth_shop_buy",
            "leader_class_change", "album_activate", "rename_character", "gift_code", "roulette_rank_claim",
            "alt_team_unlock", "alt_team_set", "leader_digit_repair",
            "campaign_battle", "campaign_auto", "campaign_reentry", "campaign_star_box", "campaign_refresh",
            "goal_refresh", "goal_claim")

        private class HeroScan(val revision: Long, val anchorTime: String, val anchorPayload: String, val injected: Set<Long>, val acquired: Set<Long>)
        private val heroScanCache = java.util.concurrent.ConcurrentHashMap<String, HeroScan>()
        const val FRESH_PROFILE = "fresh_character_v1"
        const val JEWEL_LIST_PROFILE = "jewelry_unequipped_list_v1"

        /** `state_json`: compact, insertion order, ensure_ascii, no NaN. */
        fun stateJson(value: JValue): String = Json.compact(value)
    }

    init {
        if (!Files.isRegularFile(this.path)) throw IllegalArgumentException("State database does not exist; initialize it explicitly")
    }

    fun connect(readOnly: Boolean = false): SqlConnection = driver.open(path, if (readOnly) SqlDriver.Mode.READ_ONLY else SqlDriver.Mode.READ_WRITE)

    /** What a read returns: the fields the session and the systems use. */
    class Current(
        val revision: Long,
        val sourceSha256: String,
        val payloadSha256: String,
        val state: JObj,
        val payload: ByteArray,
        val inventorySchemaVersion: Int,
        val inventorySha256: String?,
        val inventoryItems: JArr,
        val inventoryPayloads: List<ByteArray>,
        val acquiredItems: JArr,
        val acquiredPayloads: List<ByteArray>,
        val acquiredSha256: String?,
        val secondaryTeam: JObj?,
        val godSkills: JObj?,
        val characterProfile: JObj?,
        val jewelryList: JObj?,
        val documents: Map<String, JValue?>,
        /** Heroes of labeled test-fixture injections and of acquisitions, from the history (sorted). */
        val fixtureInjectedHeroUids: List<Long> = emptyList(),
        val acquiredHeroUids: List<Long> = emptyList(),
    ) {
        /** The unequipped-jewelry entries a planner reads (`current["jewel_entries_view"]`), set by the transaction. */
        var jewelEntriesView: JArr? = null

        /** `current.get(<document table>)`: a per-character document (null when its table does not exist yet). */
        fun document(table: String): JValue? = documents[table]
    }

    fun read(): Current = connect(readOnly = true).use { db -> db.snapshot { read(it) } }

    fun read(db: SqlConnection): Current {
        val row = db.queryOne("SELECT format_version,revision,source_sha256,payload_sha256,state_json FROM player_state WHERE id=1")
        if (row == null || row.long("format_version") != 1L) throw IllegalArgumentException("Unsupported or missing player state format")
        val state = Json.loads(row.string("state_json")).asObj
        val mode = (state["login_mode"] as? JInt)?.value?.toLong()
        if (mode == 1L || mode == 2L) throw IllegalArgumentException("A status-only login reply is not a full player save")
        val payload = PlayerState.encode(state)
        if (state["complete"] != JBool(true) || sha256Hex(payload) != row.string("payload_sha256")) {
            throw IllegalArgumentException("Stored player state integrity check failed")
        }
        val inventory = inventory(db)
        val initUids = state.arr("items").map { it.asObj.arr("wire_values")[0] }.toSet()
        if (inventory.items.any { it.asObj.arr("wire_values")[0] in initUids }) throw IllegalArgumentException("Item UID appears in both initialization and extra inventory")
        val secondary = checksummedDocument(db, "secondary_team", withSchema = true) { Json.canonical(it) }
        if (secondary != null && secondary.obj("document").str("source_player_sha256") != row.string("source_sha256")) {
            throw IllegalArgumentException("Secondary-team provenance belongs to a different initial player save")
        }
        val unlockRevision = (secondary?.obj("document")?.get("unlock") as? JObj)?.get("state_revision") as? JInt
        if (unlockRevision != null && unlockRevision.value.toLong() > row.long("revision")) {
            throw IllegalArgumentException("Secondary unlock provenance is newer than the owned save")
        }
        val god = checksummedDocument(db, "god_skills", withSchema = true) { Json.canonical(it) }
        val profile = checksummedDocument(db, "character_profile", withSchema = true) { Json.canonical(it) }
        if (profile != null && profile.obj("document").strOrNull("profile") != FRESH_PROFILE) throw IllegalArgumentException("Unsupported character profile")
        val jewels = jewelryList(db)
        val acquired = acquired(db)
        val taken = initUids + inventory.items.map { it.asObj.arr("wire_values")[0] }
        if (acquired.items.any { it.asObj.arr("wire_values")[0] in taken }) throw IllegalArgumentException("Acquired item UID collides with an initialization or imported item")
        val documents = LinkedHashMap<String, JValue?>()
        documents[SUMMON_STATE] = plainDocument(db, SUMMON_STATE)
        for (table in DOCUMENT_TABLES) documents[table] = plainDocument(db, table)
        val (injected, acquiredHeroes) = historyHeroSets(db)
        return Current(row.long("revision"), row.string("source_sha256"), row.string("payload_sha256"), state, payload,
            inventory.schemaVersion, inventory.sha256, inventory.items, inventory.payloads, acquired.items, acquired.payloads,
            acquired.sha256, secondary, god, profile, jewels, documents, injected.sorted(), acquiredHeroes.sorted())
    }

    /**
     * Heroes recorded by the history (`_history_hero_sets`): labeled test-fixture injections (action
     * `test_fixture_inject_hero`, evidence class `preservation_policy_test`) and heroes added by acquisition
     * transactions (`heroes_added`). The history is append-only; the scan is cached per file and continues from the
     * newest row it saw (a replaced file, whose anchor row differs, is scanned again).
     */
    private fun historyHeroSets(db: SqlConnection): Pair<Set<Long>, Set<Long>> {
        val key = path.toString()
        var cached = heroScanCache[key]
        if (cached != null && cached.revision > 0) {
            val anchor = db.queryOne("SELECT timestamp_utc,payload_sha256 FROM state_history WHERE revision=?", cached.revision)
            if (anchor == null || anchor.string("timestamp_utc") != cached.anchorTime || anchor.string("payload_sha256") != cached.anchorPayload) cached = null
        }
        val injected = HashSet(cached?.injected ?: emptySet())
        val acquired = HashSet(cached?.acquired ?: emptySet())
        val last = db.queryOne("SELECT revision,timestamp_utc,payload_sha256 FROM state_history ORDER BY revision DESC LIMIT 1")
        val start = cached?.revision ?: 0L
        if (last != null && last.long("revision") > start) {
            val actions = listOf(INJECT_ACTION) + ACQUISITION_ACTIONS
            val placeholders = actions.joinToString(",") { "?" }
            for (row in db.query("SELECT action,detail_json FROM state_history WHERE revision>? AND revision<=? AND action IN ($placeholders)",
                    start, last.long("revision"), *actions.toTypedArray())) {
                val detail = Json.loads(row.string("detail_json")).asObj
                if (row.string("action") == INJECT_ACTION) {
                    if (detail.strOrNull("evidence_class") == "preservation_policy_test") {
                        (detail["injected"] as? JArr)?.forEach { injected.add((it.asObj["uid"] as JInt).value.toLong()) }
                    }
                } else {
                    (detail["heroes_added"] as? JArr)?.forEach { acquired.add((it.asObj["uid"] as JInt).value.toLong()) }
                }
            }
        }
        heroScanCache[key] = if (last != null) HeroScan(last.long("revision"), last.string("timestamp_utc"), last.string("payload_sha256"), injected, acquired)
            else HeroScan(0, "", "", injected, acquired)
        return injected to acquired
    }

    private class InventoryRead(val schemaVersion: Int, val sha256: String?, val items: JArr, val payloads: List<ByteArray>)

    private fun inventory(db: SqlConnection, verify: Boolean = true): InventoryRead {
        val names = db.query("SELECT name FROM sqlite_master WHERE type='table'").map { it.string("name") }.toSet()
        val expected = setOf("inventory_meta", "inventory_batches", "inventory_records", "inventory_retired_uids")
        if (names.intersect(expected).isEmpty()) return InventoryRead(0, null, JArr(), emptyList())
        if (!names.containsAll(expected)) throw IllegalArgumentException("Incomplete inventory schema")
        val meta = db.queryOne("SELECT schema_version,inventory_sha256 FROM inventory_meta WHERE id=1")
        if (meta == null || meta.long("schema_version") != 1L) throw IllegalArgumentException("Unsupported or missing inventory format")
        val canonical = JArr()
        val items = JArr()
        val payloads = ArrayList<ByteArray>()
        var recordTotal = 0
        for (batch in db.query("""SELECT batch_id,source_order,source_path,source_sha256,source_payload,
                                   imported_revision,imported_at_utc FROM inventory_batches ORDER BY source_order""")) {
            val source = batch.bytes("source_payload")
            if (sha256Hex(source) != batch.string("source_sha256")) throw IllegalArgumentException("Inventory source integrity check failed")
            val original = Inventory.decode(source)
            val records = db.query("SELECT item_uid,item_order,item_json FROM inventory_records WHERE batch_id=? ORDER BY item_order", batch.long("batch_id"))
            if (records.size != original.size) throw IllegalArgumentException("Inventory source record count changed")
            val batchItems = JArr()
            val saved = JArr()
            records.forEachIndexed { index, record ->
                val uid = record.long("item_uid")
                val originalItem = original[index].asObj
                if (record.long("item_order") != index.toLong() || (originalItem.arr("wire_values")[0] as JInt).value.toLong() != uid) {
                    throw IllegalArgumentException("Inventory source order or UID changed")
                }
                val item = record.stringOrNull("item_json")?.let { Json.loads(it).asObj }
                if (item != null) {
                    Inventory.encode(jarr(item))
                    // Quantity may change; identity and timing are preserved.
                    if (identity(item) != identity(originalItem)) throw IllegalArgumentException("Inventory identity or timing changed")
                    batchItems.add(item)
                }
                saved.add(jarr(uid, index, item ?: JNull))
            }
            recordTotal += records.size
            items.addAll(batchItems)
            payloads.add(Inventory.encode(batchItems))
            canonical.add(jobj("batch_id" to batch.long("batch_id"), "source_order" to batch.long("source_order"),
                "source_path" to batch.string("source_path"), "source_sha256" to batch.string("source_sha256"),
                "imported_revision" to batch.long("imported_revision"), "imported_at_utc" to batch.string("imported_at_utc"),
                "records" to saved))
        }
        val count = db.queryOne("SELECT COUNT(*) AS n FROM inventory_records")!!.long("n")
        if (count != recordTotal.toLong()) throw IllegalArgumentException("Inventory record refers to a missing batch")
        val retired = JArr(db.query("SELECT item_uid,retired_revision FROM inventory_retired_uids ORDER BY item_uid")
            .mapTo(ArrayList<JValue>()) { jarr(it.long("item_uid"), it.long("retired_revision")) })
        val checksum = sha256Hex(stateJson(jobj("batches" to canonical, "retired" to retired)).toByteArray())
        if (verify && checksum != meta.string("inventory_sha256")) throw IllegalArgumentException("Stored inventory integrity check failed")
        return InventoryRead(1, checksum, items, payloads)
    }

    private fun identity(item: JObj): JObj = JObj(LinkedHashMap(item.map)).also {
        it["wire_values"] = JArr(item.arr("wire_values").take(2).toMutableList())
    }

    private class AcquiredRead(val items: JArr, val payloads: List<ByteArray>, val sha256: String?)

    private fun acquired(db: SqlConnection): AcquiredRead {
        if (!db.tableExists("acquired_items")) return AcquiredRead(JArr(), emptyList(), null)
        val rows = db.query("SELECT item_uid,template,count,created_revision FROM acquired_items ORDER BY item_uid")
        val meta = db.queryOne("SELECT schema_version,rows_sha256 FROM acquired_meta WHERE id=1")
        val checksum = sha256Hex(stateJson(JArr(rows.mapTo(ArrayList<JValue>()) {
            jarr(it.long("item_uid"), it.long("template"), it.long("count"), it.long("created_revision"))
        })).toByteArray())
        if (meta == null || meta.long("schema_version") != 1L || meta.string("rows_sha256") != checksum) {
            throw IllegalArgumentException("Acquired item integrity check failed")
        }
        val items = JArr(rows.mapTo(ArrayList<JValue>()) { jobj("wire_values" to jarr(it.long("item_uid"), it.long("template"), it.long("count")), "timed_flag" to 0) })
        val payloads = items.chunked(255).map { Inventory.encode(JArr(it.toMutableList())) }
        return AcquiredRead(items, payloads, checksum)
    }

    /** A `{schema_version, document_json, document_sha256}` row checked against the reference's checksum form. */
    private fun checksummedDocument(db: SqlConnection, table: String, withSchema: Boolean, form: (JValue) -> String): JObj? {
        if (!db.tableExists(table)) return null
        val row = db.queryOne("SELECT schema_version,document_json,document_sha256 FROM $table WHERE id=1")
        if (row == null || (withSchema && row.long("schema_version") != 1L)) throw IllegalArgumentException("Unsupported or missing $table state format")
        val document = Json.loads(row.string("document_json"))
        if (sha256Hex(form(document).toByteArray()) != row.string("document_sha256")) throw IllegalArgumentException("Stored $table integrity check failed")
        return jobj("document" to document, "document_sha256" to row.string("document_sha256"))
    }

    private fun jewelryList(db: SqlConnection): JObj? {
        if (!db.tableExists("jewelry_list")) return null
        val row = db.queryOne("SELECT document_json, document_sha256 FROM jewelry_list WHERE id=1") ?: return null
        if (sha256Hex(row.string("document_json").toByteArray(Charsets.UTF_8)) != row.string("document_sha256")) {
            throw IllegalArgumentException("Stored jewelry list checksum mismatch")
        }
        val document = Json.loads(row.string("document_json")).asObj
        if (document.strOrNull("profile") != JEWEL_LIST_PROFILE) throw IllegalArgumentException("Unsupported jewelry list document")
        return jobj("document" to document, "document_sha256" to row.string("document_sha256"))
    }

    private fun plainDocument(db: SqlConnection, table: String): JValue? {
        if (!db.tableExists(table)) return null
        return db.queryOne("SELECT document_json FROM $table WHERE id=1")?.let { Json.loads(it.string("document_json")) }
    }

    // --- transactions ----------------------------------------------------------------------------------------------

    /** Per-character document plan keys ("<table>_after") and their tables (`ACQUISITION_DOCUMENTS`, reference order). */
    private val acquisitionDocumentKeys: List<Pair<String, String>> = DOCUMENT_TABLES.map { "${it}_after" to it }

    /**
     * One acquisition-family request as one atomic revision (`acquisition_transaction`): `planner(owned, current)`
     * validates on the owned view and returns the plan; every rejection happens before any write. The store applies the
     * recorded item changes, new stacks, documents, the god-skill document, and commits one revision + history row.
     * Returns (`{revision, timestamp_utc, **detail}`, plan).
     */
    fun acquisitionTransaction(action: String, characterId: String, policy: FreshProfile.DeploymentPolicy?,
                               inputs: AcquisitionInputs?, actor: String, reason: String,
                               expectedRevision: Long? = null, detailExtra: JObj? = null, servedJewelList: ByteArray? = null,
                               servedJewelSource: String? = null,
                               planner: (Owned, Current) -> Plan): Pair<JObj, Plan> {
        if (action !in ACQUISITION_ACTIONS) throw Acquisition.Rejected("Unknown acquisition action")
        SecondaryTeam.validateOwner(characterId)
        require(actor.isNotBlank() && reason.isNotBlank()) { "Actor and reason must be nonempty text" }
        if (expectedRevision != null) require(expectedRevision >= 1) { "Expected revision must be a positive integer" }
        if (policy == null) throw Acquisition.Rejected("Acquisition lacks explicit owner/source evidence for this save")
        if (inputs == null) throw Acquisition.Rejected("Acquisition requires a catalog input loader")
        connect().use { db ->
            return db.immediate {
                val current = read(db)
                if (expectedRevision != null && current.revision != expectedRevision) throw Acquisition.Rejected("State revision changed; reread before acquiring")
                try { policy.validateCurrent(current, characterId) } catch (e: SecondaryTeam.Rejected) {
                    throw Acquisition.Rejected(e.message ?: "")
                }
                val retired = if (db.tableExists("inventory_retired_uids")) db.query("SELECT item_uid FROM inventory_retired_uids").map { it.long("item_uid") } else emptyList()
                val owned = Owned(current, inputs, retired)
                var jewelDoc = current.jewelryList?.obj("document")
                var jewelSeeded = false
                if (jewelDoc == null && servedJewelList != null) {
                    jewelDoc = EquipFormation.seedJewelryDocument(servedJewelList, servedJewelSource ?: "served")
                    jewelSeeded = true
                }
                current.jewelEntriesView = jewelDoc?.arr("entries")
                val plan = planner(owned, current)
                val activities = current.state.obj("subsystems")["game_activities"]
                if (action != "claim_vip_quest" && activities != null && activities != JNull) {
                    val (questDoc, questFrames) = VipQuest.afterTransaction(owned, current, plan.data, action, inputs)
                    if (questDoc != null) plan.data["vip_quest_after"] = questDoc
                    plan.packets = plan.packets + questFrames
                }
                val revision = current.revision + 1
                val acquiredChanges = LinkedHashMap<Long, Long>()
                for ((uid, count) in owned.itemChanges) {
                    val (item, location) = ownedItem(current, uid)
                    if (location == "acquired") {
                        acquiredChanges[uid] = count
                        if (count == 0L) {
                            SaveWriter.ensureInventorySchema(db)
                            db.execute("INSERT INTO inventory_retired_uids VALUES(?,?)", uid, revision)
                        }
                    } else setOwnedCount(db, current, item, location, count)
                }
                var acquiredSha: String? = null
                if (acquiredChanges.isNotEmpty() || owned.newItems.isNotEmpty()) {
                    for ((uid, entry) in owned.newItems) {
                        if (entry.second == 0L) {
                            SaveWriter.ensureInventorySchema(db)
                            db.execute("INSERT INTO inventory_retired_uids VALUES(?,?)", uid, revision)
                        }
                    }
                    acquiredSha = writeAcquired(db, acquiredChanges, owned.newItems, revision)
                }
                plan.data["summon_state_after"]?.let { if (it != JNull) writeDocument(db, SUMMON_STATE, it) }
                for ((key, table) in acquisitionDocumentKeys) plan.data[key]?.let { if (it != JNull) writeDocument(db, table, it) }
                var jewelChecksum: String? = null
                val jewelAfter = plan.data["jewel_entries_after"]
                if (jewelAfter != null && jewelAfter != JNull) {
                    val doc = JObj(LinkedHashMap(jewelDoc!!.map)).also { it["entries"] = jewelAfter }
                    jewelChecksum = EquipFormation.writeJewelryList(db, doc)
                }
                var godSha: String? = null
                val god = owned.godDocument
                if (god != null) {
                    val ids = god.arr("heroes").map { (it.asObj["uid"] as JInt).value.toLong() }.toSet()
                    if (owned.heroesAdded.any { (it.asObj["uid"] as JInt).value.toLong() !in ids }) throw Acquisition.Rejected("God-skill document lost a new hero")
                    godSha = GodSkills.writeGodSkills(db, god)
                }
                val detail = jobj("actor" to actor, "reason" to reason, "character_id" to characterId)
                for ((k, v) in plan.data) if (k != "jewel_entries_after") detail[k] = v
                detailExtra?.forEach { (k, v) -> detail[k] = v }
                detail["heroes_added"] = owned.heroesAdded
                detail["heroes_removed"] = JArr(owned.heroesRemoved.mapTo(ArrayList()) { JInt(it) })
                detail["equipment_added"] = owned.equipmentAdded
                detail["items_created"] = JArr(owned.newItems.entries.mapTo(ArrayList()) { (u, e) -> jobj("uid" to u, "template" to e.first, "count" to e.second) })
                detail["item_changes"] = JArr(owned.itemChanges.entries.mapTo(ArrayList()) { (u, c) -> jobj("uid" to u, "count" to c) })
                detail["role_changes"] = JObj().also { o -> owned.roleChanges.forEach { (k, v) -> o[k.toString()] = jobj("before" to v.first, "after" to v.second) } }
                detail["operations"] = owned.log
                detail["acquired_items_sha256"] = jvalue(acquiredSha)
                detail["god_skills_sha256"] = jvalue(godSha)
                detail["jewelry_list_seeded"] = JBool(if (jewelChecksum != null) jewelSeeded else false)
                detail["jewelry_list_sha256"] = jvalue(jewelChecksum)
                detail["reply_opcodes"] = JArr(plan.packets.mapTo(ArrayList()) { JInt(it.first) })
                detail["deployment_policy_path"] = JStr(policy.sourcePath)
                detail["deployment_policy_sha256"] = JStr(policy.sha256)
                detail["contract"] = JStr("docs/ACQUISITION_CONTRACT.md")
                commitState(db, current, action, detail) to plan
            }
        }
    }

    /**
     * A restored character's identity in its new world (`restore_reidentify`): role 0 (wire id) and the profile's wire
     * id when the old one is taken there, role 2 (name) after a rename. One audited revision.
     */
    fun restoreReidentify(expectedRevision: Long, actor: String, reason: String, wireAccountId: Long? = null, name: String? = null): JObj {
        require(expectedRevision >= 1) { "Expected revision must be a positive integer" }
        require(actor.isNotBlank() && reason.isNotBlank()) { "Actor and reason must be nonempty text" }
        connect().use { db ->
            return db.immediate {
                val current = read(db)
                if (current.revision != expectedRevision) throw IllegalArgumentException("State revision changed; reread before editing")
                val profile = current.characterProfile ?: throw IllegalArgumentException("Only a created character can be re-identified")
                val roles = LinkedHashMap<Long, JObj>()
                for (f in current.state.arr("role_properties")) roles[(f as JObj).long("id")] = f.obj("value")
                val detail = jobj("actor" to actor, "reason" to reason)
                if (wireAccountId != null) {
                    detail["wire_account_id"] = io.github.okexodus.openknights.exact.jarr(roles.getValue(0)["bits"], wireAccountId)
                    roles.getValue(0)["bits"] = JInt(wireAccountId)
                    val document = JObj(LinkedHashMap(profile.obj("document").map)).also { it["wire_account_id"] = JInt(wireAccountId) }
                    db.execute("UPDATE character_profile SET document_json=?,document_sha256=? WHERE id=1",
                        FreshProfile.canonicalJson(document), FreshProfile.checksum(document))
                }
                if (name != null) {
                    detail["name"] = io.github.okexodus.openknights.exact.jarr(roles.getValue(2)["text"], name)
                    roles.getValue(2)["raw_hex"] = io.github.okexodus.openknights.exact.JStr(name.toByteArray(Charsets.UTF_8).toHexString())
                    roles.getValue(2)["text"] = io.github.okexodus.openknights.exact.JStr(name)
                }
                commitState(db, current, "restore_reidentify", detail)
            }
        }
    }

    /** Commit the edited state as the next revision with its history row (`_commit_state`): `{revision, timestamp_utc, **detail}`. */
    fun commitState(db: SqlConnection, current: Current, action: String, detail: JObj): JObj {
        val payload = PlayerState.encode(current.state)
        val parsed = PlayerState.parse(payload)
        require(parsed["complete"] == JBool(true) && PlayerState.encode(parsed).contentEquals(payload)) { "Edited state no longer round-trips" }
        val revision = current.revision + 1
        val timestamp = PyTime.nowIsoMillis()
        val checksum = sha256Hex(payload)
        var full = detail
        if (current.inventorySchemaVersion != 0 || db.tableExists("inventory_meta")) {
            val inventoryChecksum = inventory(db, verify = false).sha256!!
            db.execute("UPDATE inventory_meta SET inventory_sha256=? WHERE id=1", inventoryChecksum)
            full = JObj(LinkedHashMap(detail.map)).also { it["inventory_sha256"] = JStr(inventoryChecksum) }
        }
        db.execute("UPDATE player_state SET revision=?,payload_sha256=?,updated_at_utc=?,state_json=? WHERE id=1", revision, checksum, timestamp, stateJson(parsed))
        db.execute("INSERT INTO state_history VALUES(?,?,?,?,?)", revision, timestamp, action, stateJson(full), checksum)
        val result = jobj("revision" to revision, "timestamp_utc" to timestamp)
        full.forEach { (k, v) -> result[k] = v }
        return result
    }

    /** The one owned item of a UID and its store (`_owned_item`): initial / extra / acquired. */
    fun ownedItem(current: Current, itemUid: Long): Pair<JObj, String> {
        val matches = ArrayList<Pair<JObj, String>>()
        fun uidOf(item: JValue) = (item.asObj.arr("wire_values")[0] as JInt).value.toLong()
        current.state.arr("items").filter { uidOf(it) == itemUid }.forEach { matches.add(it.asObj to "initial") }
        current.inventoryItems.filter { uidOf(it) == itemUid }.forEach { matches.add(it.asObj to "extra") }
        current.acquiredItems.filter { uidOf(it) == itemUid }.forEach { matches.add(it.asObj to "acquired") }
        if (matches.size != 1) throw Acquisition.Rejected("Expected one owned item instance", if (matches.isEmpty()) 2000 else 102)
        return matches[0]
    }

    private fun setOwnedCount(db: SqlConnection, current: Current, item: JObj, location: String, count: Long) {
        val uid = (item.arr("wire_values")[0] as JInt).value.toLong()
        if (count != 0L) item.arr("wire_values")[2] = JInt(count)
        when (location) {
            "initial" -> if (count == 0L) current.state.arr("items").removeIf { it === item }
            "acquired" -> writeAcquired(db, mapOf(uid to count), emptyMap(), current.revision + 1)
            else -> db.execute("UPDATE inventory_records SET item_json=? WHERE item_uid=?", if (count != 0L) stateJson(item) else null, uid)
        }
        if (count == 0L) {
            SaveWriter.ensureInventorySchema(db)
            db.execute("INSERT INTO inventory_retired_uids VALUES(?,?)", uid, current.revision + 1)
        }
    }

    /** Acquired-stack count changes / creations and the table checksum (`_write_acquired`). */
    fun writeAcquired(db: SqlConnection, changes: Map<Long, Long>, newItems: Map<Long, Pair<Long, Long>>, revision: Long): String {
        db.execute(Schemas.SAVE.getValue("acquired_items"))
        db.execute(Schemas.SAVE.getValue("acquired_meta"))
        for ((uid, count) in changes) {
            if (count != 0L) db.execute("UPDATE acquired_items SET count=? WHERE item_uid=?", count, uid)
            else db.execute("DELETE FROM acquired_items WHERE item_uid=?", uid)
        }
        for ((uid, entry) in newItems) if (entry.second != 0L) db.execute("INSERT INTO acquired_items VALUES(?,?,?,?)", uid, entry.first, entry.second, revision)
        val rows = db.query("SELECT item_uid,template,count,created_revision FROM acquired_items ORDER BY item_uid")
        val checksum = sha256Hex(stateJson(JArr(rows.mapTo(ArrayList<JValue>()) {
            jarr(it.long("item_uid"), it.long("template"), it.long("count"), it.long("created_revision"))
        })).toByteArray())
        db.execute("INSERT INTO acquired_meta VALUES(1,1,?) ON CONFLICT(id) DO UPDATE SET rows_sha256=excluded.rows_sha256", checksum)
        return checksum
    }

    /** A plain per-character document (`shops.write_state`): sorted compact JSON in a single-row table. */
    fun writeDocument(db: SqlConnection, table: String, document: JValue) {
        db.execute("CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL)")
        db.execute("INSERT INTO $table VALUES(1,?) ON CONFLICT(id) DO UPDATE SET document_json=excluded.document_json",
            Json.dumps(document, sortKeys = true, itemSeparator = ",", keySeparator = ":"))
    }

    /** `history_values(action, json_path)`: one JSON value of every history row of `action`, in revision order (nulls skipped). */
    fun historyValues(action: String, jsonPath: String): List<Any> = connect(readOnly = true).use { db ->
        db.query("SELECT json_extract(detail_json, ?) AS v FROM state_history WHERE action=? ORDER BY revision", jsonPath, action).mapNotNull { it["v"] }
    }

    /** Values of the state tree used by several systems (role property `id`). */
    fun role(state: JObj, id: Int): JObj = PlayerState.role(state, id)
}

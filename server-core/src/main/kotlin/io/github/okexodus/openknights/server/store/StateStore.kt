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
    )

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
        return Current(row.long("revision"), row.string("source_sha256"), row.string("payload_sha256"), state, payload,
            inventory.schemaVersion, inventory.sha256, inventory.items, inventory.payloads, acquired.items, acquired.payloads,
            acquired.sha256, secondary, god, profile, jewels, documents)
    }

    private class InventoryRead(val schemaVersion: Int, val sha256: String?, val items: JArr, val payloads: List<ByteArray>)

    private fun inventory(db: SqlConnection): InventoryRead {
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
        if (checksum != meta.string("inventory_sha256")) throw IllegalArgumentException("Stored inventory integrity check failed")
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

    /** Values of the state tree used by several systems (role property `id`). */
    fun role(state: JObj, id: Int): JObj = PlayerState.role(state, id)
}

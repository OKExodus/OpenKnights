package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asLong
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared world database (`world.py`, schema v4; v3 is still readable): the character directory (unique names
 * and wire ids), the world-level documents born with the world, `world_meta.world_seed` (the seed a future bot
 * population derives from) and an audited history. Documents are stored as the reference's sorted canonical JSON.
 */
class WorldDirectory(path: Path, private val driver: SqlDriver, val strictPaths: Boolean = false) {
    val path: Path = path.toAbsolutePath().normalize()
    val base: Path = this.path.parent
    val schemaVersion: Int

    companion object {
        const val SCHEMA_VERSION = 4
        val READABLE_VERSIONS = listOf(3, SCHEMA_VERSION)
        const val WIRE_ID_MAX = 0x7FFFFFFF
        const val WIRE_ID_FIRST = 90_000_001L
        /** The wire-id range reserved for bots (plan §5b; `world_participants.BOT_ID_*`). */
        const val BOT_ID_FIRST = 95_000_000L
        const val BOT_ID_LAST = 95_999_999L

        // --- rank list (C705 → S736) -------------------------------------------------------------------------------

        const val RANK_REQUEST_OPCODE = 705
        const val RANK_REPLY_OPCODE = 736
        /** Observed: 8 entries per page, at most 100 ranks listed. */
        const val RANK_PAGE_SIZE = 8
        const val RANK_MAX_LISTED = 100
        const val RANK_LEVEL = 1L
        const val RANK_POWER = 6L
        const val RANK_PRESTIGE = 4L

        /** Rank inputs of a world participant, characters and bots alike (`participant_rank_row`). */
        class RankRow(val roleId: Long, val nameRaw: ByteArray, val level: Long, val reputation: Long, val created: String,
                      val power: java.math.BigInteger?)

        fun participantRankRow(p: io.github.okexodus.openknights.server.game.WorldParticipants.Participant): RankRow =
            RankRow(p.participantId, p.nameRaw, p.level, p.reputation, p.created, p.power)

        /** `u8 type, u32 page` (`decode_rank_request`). */
        fun decodeRankRequest(payload: ByteArray): Pair<Long, Long> {
            if (payload.size != 5) throw io.github.okexodus.openknights.server.game.PyValues.ValueError("Rank request must be u8 type + u32 page")
            val r = io.github.okexodus.openknights.protocol.WireReader(payload)
            return r.u8().toLong() to r.u32()
        }

        /** The requester's wire id from its save (`character_rank_row`: role 0; roles 2 / 3 / 12 are read as well). */
        fun characterRankRoleId(current: StateStore.Current): Long {
            val role = LinkedHashMap<Long, JObj>()
            for (f in current.state.arr("role_properties")) role[(f as JObj).long("id")] = f.obj("value")
            fun key(k: Any): Nothing = throw io.github.okexodus.openknights.server.game.PyDocs.KeyError(k)
            val id = (role[0L] ?: key(0))["bits"] ?: key("bits")
            val name = (role[2L] ?: key(2))["raw_hex"] ?: key("raw_hex")
            name.asStr.hexBytes()
            (role[3L] ?: key(3))["bits"] ?: key("bits")
            return id.asLong
        }

        class RankEntry(val rank: Long, val roleId: Long, val nameRaw: ByteArray, val level: Long, val valueA: java.math.BigInteger, val valueB: Long)
        class RankReply(val type: Long, val totalPages: Long, val page: Long, val entries: List<RankEntry>, val myRank: Long)

        /**
         * One S736 page from the world's participants (`build_rank_reply`, policy for order / tie-breaks): Level (type 1)
         * level, Power, creation; Power (type 6, only when every row has a Power) Power, level, creation; prestige
         * (type 4) Reputation, level, creation; other types an empty page. value_a = Power, value_b = Reputation.
         */
        fun buildRankReply(type: Long, requestPage: Long, rows: List<RankRow>, requesterRoleId: Long): RankReply {
            val page = maxOf(1L, requestPage)
            fun power(r: RankRow): java.math.BigInteger = r.power ?: java.math.BigInteger.ZERO
            val byCreated = Comparator<RankRow> { a, b -> Json.CodePointOrder.compare(a.created, b.created) }
            var listed: List<RankRow> = when {
                type == RANK_LEVEL -> rows.sortedWith(compareByDescending<RankRow> { it.level }.thenByDescending { power(it) }.then(byCreated))
                type == RANK_POWER && rows.isNotEmpty() && rows.all { it.power != null } ->
                    rows.sortedWith(compareByDescending<RankRow> { power(it) }.thenByDescending { it.level }.then(byCreated))
                type == RANK_PRESTIGE -> rows.sortedWith(compareByDescending<RankRow> { it.reputation }.thenByDescending { it.level }.then(byCreated))
                else -> emptyList()
            }
            listed = listed.take(RANK_MAX_LISTED)
            val totalPages = maxOf(1L, Math.floorDiv(listed.size.toLong() + RANK_PAGE_SIZE - 1, RANK_PAGE_SIZE.toLong()))
            val start = (page - 1) * RANK_PAGE_SIZE
            val chunk = if (start >= listed.size) emptyList() else listed.subList(start.toInt(), minOf(listed.size.toLong(), page * RANK_PAGE_SIZE).toInt())
            val entries = chunk.mapIndexed { i, r -> RankEntry(start + i + 1, r.roleId, r.nameRaw, r.level, power(r), r.reputation) }
            val mine = listed.indexOfFirst { it.roleId == requesterRoleId }
            return RankReply(type, totalPages, page, entries, if (mine >= 0) mine + 1L else 0L)
        }

        /** S736 `u8 type, u32 pages, u32 page, u8 n, n × (u32 rank, u32 role, cstr name, u32 level, u64 a, u64 b), u32 my rank`. */
        fun encodeRankReply(reply: RankReply): ByteArray {
            val w = io.github.okexodus.openknights.protocol.WireWriter().u8(reply.type.toInt()).u32(reply.totalPages).u32(reply.page).u8(reply.entries.size)
            for (e in reply.entries) {
                if (e.nameRaw.contains(0.toByte())) throw io.github.okexodus.openknights.server.game.PyValues.ValueError("Rank name must not contain NUL")
                w.u32(e.rank).u32(e.roleId).raw(e.nameRaw).raw(byteArrayOf(0)).u32(e.level).number('Q', e.valueA).number('Q', e.valueB)
            }
            return w.u32(reply.myRank).bytes()
        }

        /** The world-level documents born with the world (`WORLD_DOCUMENTS`), in the reference's order. */
        val WORLD_DOCUMENTS: List<Pair<String, JObj>> get() = listOf(
            "royal_door" to jobj("profile" to "royal_door_world_v1", "level" to 1, "exp" to 0),
            "arena_ladder" to jobj("profile" to "arena_ladder_v1", "ranks" to emptyList<Any>()),
            "guilds" to jobj("profile" to "guilds_world_v1", "next_id" to 1, "guilds" to JObj(), "rejoin" to JObj()),
            "social" to jobj("profile" to "social_world_v1", "friends" to JObj(), "requests" to JObj(), "praise" to JObj(), "helper" to JObj()),
            "chat" to jobj("profile" to "chat_world_v1", "world" to emptyList<Any>(), "guild" to JObj(), "private" to JObj()),
            "mail" to jobj("profile" to "mail_world_v1", "next_id" to 1, "boxes" to JObj(), "blacklist" to JObj()),
            "presence" to jobj("profile" to "presence_world_v1", "players" to JObj()),
            "campaign" to jobj("profile" to "campaign_world_v1", "first_kills" to JObj()),
            "summon_reports" to jobj("profile" to "summon_reports_world_v1", "entries" to emptyList<Any>()),
            "roulette_rank" to jobj("profile" to "roulette_rank_world_v1", "days" to JObj(), "total" to JObj()),
            "bot_progress" to jobj("profile" to "bot_progress_world_v1", "bots" to JObj()),
        )

        const val DOCUMENTS_DDL = """CREATE TABLE world_documents (
                        name TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision>=1),
                        document_json TEXT NOT NULL, born_at_utc TEXT NOT NULL, updated_at_utc TEXT NOT NULL)"""

        val DDL = """
                    CREATE TABLE world_meta (id INTEGER PRIMARY KEY CHECK(id=1), created_at_utc TEXT NOT NULL,
                        contract TEXT NOT NULL, world_seed TEXT);
                    CREATE TABLE world_characters (
                        entry_id TEXT PRIMARY KEY,
                        character_id TEXT UNIQUE,
                        account_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        name_key TEXT NOT NULL UNIQUE,
                        wire_account_id INTEGER NOT NULL UNIQUE CHECK(wire_account_id BETWEEN 1 AND $WIRE_ID_MAX),
                        kind TEXT NOT NULL CHECK(kind IN ('fresh','derived')),
                        starter INTEGER,
                        gender INTEGER,
                        status TEXT NOT NULL CHECK(status IN ('reserved','active','abandoned','deleted')),
                        state_path TEXT,
                        created_at_utc TEXT NOT NULL,
                        updated_at_utc TEXT NOT NULL);
                    CREATE TABLE world_history (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_utc TEXT NOT NULL, actor TEXT NOT NULL,
                        action TEXT NOT NULL, entry_id TEXT, character_id TEXT, detail_json TEXT NOT NULL);
                    $DOCUMENTS_DDL"""

        private fun canonical(value: JValue) = Json.canonical(value)

        fun newSeed(): String = io.github.okexodus.openknights.server.Entropy.current.tokenHex(16)

        /** Publish an empty world (schema v4) with its birth documents; never replaces an existing file. */
        fun initialize(path: Path, driver: SqlDriver, worldSeed: String = newSeed()): WorldDirectory {
            require(worldSeed.isNotEmpty() && worldSeed.length <= 64 && worldSeed.all { it.isLetterOrDigit() && it.code < 128 }) {
                "World seed must be 1-64 letters or digits"
            }
            val target = path.toAbsolutePath().normalize()
            Files.createDirectories(target.parent)
            val temporary = Publish.temporaryBeside(target, "init", ".sqlite3")
            try {
                Files.delete(temporary)
                driver.open(temporary, SqlDriver.Mode.CREATE).use { db ->
                    db.execute("BEGIN IMMEDIATE")
                    db.script(DDL)
                    db.execute("INSERT INTO world_meta VALUES(1, ?, 'docs/CHARACTER_CREATE_CONTRACT.md', ?)", PyTime.nowIsoMillis(), worldSeed)
                    db.execute("PRAGMA user_version=$SCHEMA_VERSION")
                    db.execute("COMMIT")
                    db.immediate { birthDocuments(it, "world-initialize") }
                }
                Publish.publishNew(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return WorldDirectory(target, driver)
        }

        /** Create every missing world-level document with its birth state (audited). */
        fun birthDocuments(db: SqlConnection, actor: String): List<String> {
            val timestamp = PyTime.nowIsoMillis()
            val born = ArrayList<String>()
            for ((name, template) in WORLD_DOCUMENTS) {
                val document = JObj(LinkedHashMap(template.map)).also { it["born_at_utc"] = io.github.okexodus.openknights.exact.JStr(timestamp) }
                if (db.queryOne("SELECT 1 FROM world_documents WHERE name=?", name) == null) {
                    db.execute("INSERT INTO world_documents VALUES(?,?,?,?,?)", name, 1L, canonical(document), timestamp, timestamp)
                    db.execute("INSERT INTO world_history(timestamp_utc,actor,action,entry_id,character_id,detail_json) VALUES(?,?,?,?,?,?)",
                        timestamp, actor, "birth_document", null, null, canonical(jobj("name" to name, "document" to document)))
                    born.add(name)
                }
            }
            return born
        }
    }

    init {
        if (!Files.isRegularFile(this.path)) throw IllegalArgumentException("World database does not exist; initialize it explicitly")
        schemaVersion = connect(readOnly = true).use { it.userVersion() }
        if (schemaVersion !in READABLE_VERSIONS) throw IllegalArgumentException("Unsupported world database format")
    }

    fun connect(readOnly: Boolean = false): SqlConnection = driver.open(path, if (readOnly) SqlDriver.Mode.READ_ONLY else SqlDriver.Mode.READ_WRITE)

    fun audit(db: SqlConnection, actor: String, action: String, entryId: String? = null, characterId: String? = null, detail: JObj? = null) {
        db.execute("INSERT INTO world_history(timestamp_utc,actor,action,entry_id,character_id,detail_json) VALUES(?,?,?,?,?,?)",
            PyTime.nowIsoMillis(), actor, action, entryId, characterId, canonical(detail ?: JObj()))
    }

    /** A directory row with its stored path resolved under the world's directory. */
    fun row(row: SqlRow): JObj {
        val out = JObj()
        row.columns.forEachIndexed { i, column ->
            val v = row.values[i]
            out[column] = when {
                column == "state_path" && v is String -> io.github.okexodus.openknights.exact.JStr(DataPaths.fromStored(v, base).toString())
                else -> io.github.okexodus.openknights.exact.jvalue(v)
            }
        }
        return out
    }

    fun characters(status: String = "active"): List<JObj> = connect(readOnly = true).use { db ->
        db.query("SELECT * FROM world_characters WHERE status=? ORDER BY created_at_utc,entry_id", status).map { row(it) }
    }

    fun member(characterId: String): JObj? = connect(readOnly = true).use { db ->
        db.queryOne("SELECT * FROM world_characters WHERE character_id=? AND status='active'", characterId)?.let { row(it) }
    }

    /** {entry_id: rowid} — the in-game list numbers rows by their directory row number. */
    fun rowNumbers(): Map<String, Long> = connect(readOnly = true).use { db ->
        db.query("SELECT rowid,entry_id FROM world_characters").associate { it.string("entry_id") to it.long("rowid") }
    }

    /** (revision, document) of a world-level document, or null. */
    fun document(name: String): Pair<Long, JObj>? = connect(readOnly = true).use { db ->
        db.queryOne("SELECT revision,document_json FROM world_documents WHERE name=?", name)?.let { it.long("revision") to Json.loads(it.string("document_json")).asObj }
    }

    fun putDocument(name: String, document: JObj, expectedRevision: Long, actor: String, action: String, detail: JObj? = null): Long =
        connect().use { db ->
            db.immediate {
                val row = db.queryOne("SELECT revision FROM world_documents WHERE name=?", name)
                if (row == null || row.long("revision") != expectedRevision) throw IllegalArgumentException("World document changed; reread before writing")
                db.execute("UPDATE world_documents SET revision=?,document_json=?,updated_at_utc=? WHERE name=?",
                    expectedRevision + 1, canonical(document), PyTime.nowIsoMillis(), name)
                val full = jobj("name" to name, "revision" to expectedRevision + 1)
                detail?.forEach { (k, v) -> full[k] = v }
                audit(db, actor, action, detail = full)
            }
            expectedRevision + 1
        }

    /**
     * Read-modify-write of one world document with optimistic retries (`update_document`): `change(document)` edits a
     * copy in place and returns (result, detail) — a null detail means nothing to write.
     */
    fun <T> updateDocument(name: String, actor: String, action: String, attempts: Int = 8, change: (JObj) -> Pair<T, JObj?>): T {
        repeat(attempts) {
            val (revision, document) = document(name) ?: throw IllegalArgumentException("World document $name is missing")
            val (result, detail) = change(document)
            if (detail == null) return result
            try {
                putDocument(name, document, revision, actor, action, detail)
                return result
            } catch (e: IllegalArgumentException) {
                // changed meanwhile: read again
            }
        }
        throw IllegalArgumentException("World document $name kept changing")
    }

    fun stored(path: Path): String = DataPaths.toStored(path, base, strict = strictPaths)

    /** Bot roster names {name_key: bot id} (none in release until bots exist). */
    val botNames: MutableMap<String, Long> = LinkedHashMap()

    /** The world's participant Power (`bind_power`): a character save's universal Power, bound by the service. */
    var powerOf: ((StateStore.Current) -> java.math.BigInteger?)? = null

    /**
     * Reserve a unique name and the next free local wire id for a fresh character (`reserve`): ids run from
     * 90,000,001 upward and skip the bot range 95,000,000–95,999,999.
     */
    fun reserve(name: String, accountId: String, starter: Long, gender: Int, actor: String): JObj {
        val normalized = io.github.okexodus.openknights.server.game.FreshProfile.normalizeName(name)
        val key = io.github.okexodus.openknights.server.game.FreshProfile.nameKey(normalized)
        val entryId = "wc_" + io.github.okexodus.openknights.server.Entropy.current.uuid4Hex()
        return connect().use { db ->
            db.immediate {
                if (db.queryOne("SELECT 1 FROM world_characters WHERE name_key=?", key) != null || key in botNames) {
                    throw io.github.okexodus.openknights.server.game.FreshProfile.CreationRejected("That name is already taken in this world")
                }
                val top = db.queryOne("SELECT MAX(wire_account_id) AS m FROM world_characters WHERE wire_account_id>=?", WIRE_ID_FIRST)?.longOrNull("m")
                var wire = if (top == null) WIRE_ID_FIRST else top + 1
                if (wire in BOT_ID_FIRST..BOT_ID_LAST) wire = BOT_ID_LAST + 1
                if (wire > WIRE_ID_MAX) throw io.github.okexodus.openknights.server.game.FreshProfile.CreationRejected("The world has no free character identities left")
                val timestamp = PyTime.nowIsoMillis()
                db.execute("INSERT INTO world_characters VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", entryId, null, accountId, normalized, key, wire,
                    "fresh", starter, gender.toLong(), "reserved", null, timestamp, timestamp)
                audit(db, actor, "reserve", entryId, detail = jobj("name" to normalized, "wire_account_id" to wire, "starter" to starter, "gender" to gender))
                jobj("entry_id" to entryId, "name" to normalized, "wire_account_id" to wire)
            }
        }
    }

    /**
     * Rename Card (`rename`): an active character's world name, under the creation rules — normalized, and free across
     * every entry (reserved, active, abandoned) and the bound bots. A taken name is refused ("… taken …"). Returns the
     * old name.
     */
    fun rename(characterId: String, name: String, actor: String, reason: String): String {
        val normalized = io.github.okexodus.openknights.server.game.FreshProfile.normalizeName(name)
        val key = io.github.okexodus.openknights.server.game.FreshProfile.nameKey(normalized)
        return connect().use { db ->
            db.immediate {
                val row = db.queryOne("SELECT entry_id,name,name_key FROM world_characters WHERE character_id=? AND status='active'", characterId)
                    ?: throw IllegalArgumentException("Only an active world character can be renamed")
                if (row.string("name_key") != key && (db.queryOne("SELECT 1 FROM world_characters WHERE name_key=?", key) != null || key in botNames)) {
                    throw io.github.okexodus.openknights.server.game.FreshProfile.CreationRejected("That name is already taken in this world")
                }
                db.execute("UPDATE world_characters SET name=?,name_key=?,updated_at_utc=? WHERE entry_id=?", normalized, key,
                    PyTime.nowIsoMillis(), row.string("entry_id"))
                audit(db, actor, "rename_character", row.string("entry_id"), characterId,
                    jobj("name_before" to row.string("name"), "name_after" to normalized, "reason" to reason))
                row.string("name")
            }
        }
    }

    /**
     * Undo of a Rename Card rename the save refused (`restore_name`): the name and name key the character had, put back
     * exactly as stored — no creation rules, no bot check — unless another entry holds that key meanwhile (never a
     * duplicate name).
     */
    fun restoreName(characterId: String, name: String, nameKey: String, actor: String, reason: String) {
        connect().use { db ->
            db.immediate {
                val row = db.queryOne("SELECT entry_id,name FROM world_characters WHERE character_id=? AND status='active'", characterId)
                    ?: throw IllegalArgumentException("Only an active world character can be renamed")
                if (db.queryOne("SELECT 1 FROM world_characters WHERE name_key=? AND entry_id<>?", nameKey, row.string("entry_id")) != null) {
                    throw IllegalArgumentException("The old name is held by another world entry; the rename cannot be undone")
                }
                db.execute("UPDATE world_characters SET name=?,name_key=?,updated_at_utc=? WHERE entry_id=?", name, nameKey,
                    PyTime.nowIsoMillis(), row.string("entry_id"))
                audit(db, actor, "rename_character", row.string("entry_id"), characterId,
                    jobj("name_before" to row.string("name"), "name_after" to name, "reason" to reason))
            }
        }
    }

    fun activate(entryId: String, characterId: String, statePath: Path, actor: String) {
        connect().use { db ->
            db.immediate {
                val storedPath = stored(statePath)
                val updated = db.execute("UPDATE world_characters SET character_id=?,state_path=?,status='active',updated_at_utc=? " +
                    "WHERE entry_id=? AND status='reserved' AND character_id IS NULL", characterId, storedPath, PyTime.nowIsoMillis(), entryId)
                if (updated != 1) throw IllegalArgumentException("World reservation is missing or no longer reserved")
                audit(db, actor, "activate", entryId, characterId, jobj("state_path" to storedPath))
            }
        }
    }

    /** Keep the name / id reserved forever (never reused silently); mark why. */
    fun abandon(entryId: String, actor: String, reason: String) {
        connect().use { db ->
            db.immediate {
                val updated = db.execute("UPDATE world_characters SET status='abandoned',updated_at_utc=? WHERE entry_id=? AND status='reserved'", PyTime.nowIsoMillis(), entryId)
                audit(db, actor, "abandon", entryId, detail = jobj("reason" to reason, "updated" to updated))
            }
        }
    }

    /**
     * Owner deletion (`mark_deleted`): the entry leaves the world; its name is freed, its wire id never reissued. Then
     * its relations leave the world documents (separate audited writes after the directory commit; a failure there
     * leaves the deletion standing and is reported). Returns {"wire_account_id", "departed": {document: detail}}.
     */
    fun markDeleted(characterId: String, actor: String, archivePath: String, reason: String): JObj {
        val row = connect().use { db ->
            db.immediate {
                val row = db.queryOne("SELECT entry_id,name,kind,wire_account_id FROM world_characters WHERE character_id=? AND status='active'", characterId)
                if (row == null || row.string("kind") != "fresh") throw IllegalArgumentException("Only an active fresh world character can be deleted")
                db.execute("UPDATE world_characters SET status='deleted',name_key=?,updated_at_utc=? WHERE entry_id=?",
                    "deleted:" + row.string("entry_id"), PyTime.nowIsoMillis(), row.string("entry_id"))
                val archive = if (!DataPaths.isAbsoluteText(archivePath)) archivePath else stored(Path.of(archivePath))
                audit(db, actor, "delete_character", row.string("entry_id"), characterId,
                    jobj("name" to row.string("name"), "archive_path" to archive, "reason" to reason))
                row
            }
        }
        val departed: JObj = try {
            io.github.okexodus.openknights.server.game.Departure.participantDeparted(this, row.long("wire_account_id"), actor, "character deleted: $reason")
        } catch (e: IllegalArgumentException) {       // e.g. a world document kept changing: rerunnable later
            jobj("error" to (e.message ?: ""))
        }
        return jobj("wire_account_id" to row.long("wire_account_id"), "departed" to departed)
    }

    fun allEntries(): List<JObj> = connect(readOnly = true).use { db ->
        db.query("SELECT * FROM world_characters ORDER BY created_at_utc,entry_id").map { row(it) }
    }

    fun birthMissingDocuments(actor: String): List<String> = connect().use { db -> db.immediate { birthDocuments(it, actor) } }

    fun worldSeed(): String? = if (schemaVersion < 4) null else connect(readOnly = true).use { it.queryOne("SELECT world_seed FROM world_meta WHERE id=1")?.stringOrNull("world_seed") }

    fun bornAt(): String = connect(readOnly = true).use { it.queryOne("SELECT created_at_utc FROM world_meta WHERE id=1")!!.string("created_at_utc") }
}

/** The reserved bot database (`bots.sqlite3`, plan §5b): schema only, created with the world. */
object BotsDatabase {
    fun initialize(path: Path, driver: SqlDriver, worldSeed: String): Path {
        val target = path.toAbsolutePath().normalize()
        val temporary = Publish.temporaryBeside(target, "init", ".sqlite3")
        try {
            Files.delete(temporary)
            driver.open(temporary, SqlDriver.Mode.CREATE).use { db ->
                db.execute("BEGIN IMMEDIATE")
                db.execute("""CREATE TABLE bots_meta (id INTEGER PRIMARY KEY CHECK(id=1), created_at_utc TEXT NOT NULL,
                    world_seed TEXT NOT NULL, contract TEXT NOT NULL)""")
                db.execute("INSERT INTO bots_meta VALUES(1, ?, ?, ?)", PyTime.nowIsoMillis(), worldSeed,
                    "reserved for the bot system: docs/OPENKNIGHTS_PLAN.md section 5b (no bot exists yet)")
                db.execute("PRAGMA user_version=${DataRoot.BOTS_SCHEMA_VERSION}")
                db.execute("COMMIT")
            }
            Publish.publishNew(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }
}

package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

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

        fun newSeed(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

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

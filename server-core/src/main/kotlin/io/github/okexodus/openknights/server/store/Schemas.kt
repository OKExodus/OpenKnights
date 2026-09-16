package io.github.okexodus.openknights.server.store

/**
 * The CREATE statements of every table the saves use, as the reference writes them (same columns, types,
 * constraints and order), so files written here and files written by the reference have the same schema.
 */
object Schemas {
    private val documentTable = { table: String -> "CREATE TABLE IF NOT EXISTS $table (id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL)" }

    private val checksummed = { table: String -> """CREATE TABLE $table (
            id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL,
            document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)""" }

    val SAVE: Map<String, String> = LinkedHashMap<String, String>().apply {
        put("player_state", """CREATE TABLE player_state (
                        id INTEGER PRIMARY KEY CHECK(id=1), format_version INTEGER NOT NULL,
                        revision INTEGER NOT NULL, source_sha256 TEXT NOT NULL,
                        payload_sha256 TEXT NOT NULL, updated_at_utc TEXT NOT NULL, state_json TEXT NOT NULL)""")
        put("state_history", """CREATE TABLE state_history (
                        revision INTEGER PRIMARY KEY, timestamp_utc TEXT NOT NULL,
                        action TEXT NOT NULL, detail_json TEXT NOT NULL, payload_sha256 TEXT NOT NULL)""")
        put(AdminProvenance.TABLE, AdminProvenance.DDL)
        put("inventory_meta", """CREATE TABLE IF NOT EXISTS inventory_meta (
                id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL,
                inventory_sha256 TEXT NOT NULL)""")
        put("inventory_batches", """CREATE TABLE IF NOT EXISTS inventory_batches (
                batch_id INTEGER PRIMARY KEY, source_order INTEGER NOT NULL UNIQUE,
                source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL UNIQUE,
                source_payload BLOB NOT NULL, imported_revision INTEGER NOT NULL,
                imported_at_utc TEXT NOT NULL)""")
        put("inventory_records", """CREATE TABLE IF NOT EXISTS inventory_records (
                item_uid INTEGER PRIMARY KEY, batch_id INTEGER NOT NULL REFERENCES inventory_batches(batch_id),
                item_order INTEGER NOT NULL, item_json TEXT,
                UNIQUE(batch_id,item_order))""")
        put("inventory_retired_uids", """CREATE TABLE IF NOT EXISTS inventory_retired_uids (
                item_uid INTEGER PRIMARY KEY, retired_revision INTEGER NOT NULL)""")
        put("acquired_items", """CREATE TABLE IF NOT EXISTS acquired_items (
            item_uid INTEGER PRIMARY KEY, template INTEGER NOT NULL, count INTEGER NOT NULL CHECK(count>0),
            created_revision INTEGER NOT NULL)""")
        put("acquired_meta", """CREATE TABLE IF NOT EXISTS acquired_meta (
            id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, rows_sha256 TEXT NOT NULL)""")
        put("character_profile", checksummed("character_profile"))
        put("god_skills", checksummed("god_skills"))
        put("secondary_team", checksummed("secondary_team"))
        put("jewelry_list", "CREATE TABLE IF NOT EXISTS jewelry_list(id INTEGER PRIMARY KEY CHECK(id=1), document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)")
        put("game_clock", "CREATE TABLE game_clock (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, revision INTEGER NOT NULL, document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)")
        put("game_clock_history", "CREATE TABLE game_clock_history (revision INTEGER PRIMARY KEY, timestamp_utc TEXT NOT NULL, action TEXT NOT NULL, detail_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)")
        put(StateStore.SUMMON_STATE, documentTable(StateStore.SUMMON_STATE))
        for (table in StateStore.DOCUMENT_TABLES) put(table, documentTable(table))
    }

    val REGISTRY: Map<String, String> = LinkedHashMap<String, String>().apply {
        AccountRegistry.DDL_V1.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(tableName(it), it) }
        put("local_sessions", LocalAuth.DDL_SESSIONS)
        put("character_retirements", AccountRegistry.DDL_RETIREMENTS)
    }

    val REGISTRY_INDEXES = listOf("CREATE INDEX local_sessions_account ON local_sessions(account_id)")

    val WORLD: Map<String, String> = LinkedHashMap<String, String>().apply {
        WorldDirectory.DDL.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(tableName(it), it) }
    }

    const val BOTS_META = """CREATE TABLE bots_meta (id INTEGER PRIMARY KEY CHECK(id=1), created_at_utc TEXT NOT NULL,
                    world_seed TEXT NOT NULL, contract TEXT NOT NULL)"""

    val BOTS: Map<String, String> = mapOf("bots_meta" to BOTS_META)

    /** The CREATE statement for a table of any database kind; null when the table is unknown. */
    fun ddl(table: String): String? = SAVE[table] ?: REGISTRY[table] ?: WORLD[table] ?: BOTS[table]

    fun tableName(ddl: String): String =
        Regex("CREATE TABLE (?:IF NOT EXISTS )?([A-Za-z_][A-Za-z0-9_]*)").find(ddl)?.groupValues?.get(1) ?: error("no table name in $ddl")

    /**
     * A CREATE statement in a comparable form: SQLite keeps the statement text as written (dropping `IF NOT EXISTS`),
     * so compare with whitespace collapsed and removed next to punctuation.
     */
    fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").replace(Regex(" ?([(),]) ?"), "$1")
        .replace("CREATE TABLE IF NOT EXISTS ", "CREATE TABLE ").trim()
}

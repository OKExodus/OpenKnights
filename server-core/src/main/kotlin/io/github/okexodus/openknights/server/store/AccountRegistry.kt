package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local accounts and character ownership (`accounts.py`): schema v1 (accounts, characters, registry_history) plus the
 * v2 `local_sessions` table ([LocalAuth]) and the additive `character_retirements`. Stored save paths are relative to
 * the registry's own directory. Every change is audited in `registry_history` with the reference's compact JSON.
 */
class AccountRegistry(path: Path, private val driver: SqlDriver, val strictPaths: Boolean = false) {
    val path: Path = path.toAbsolutePath().normalize()
    val base: Path = this.path.parent

    companion object {
        const val PASSWORD_ITERATIONS = 600_000
        val USERNAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        val STATE_IDENTITY_FIELDS = listOf("revision", "source_sha256", "payload_sha256", "inventory_schema_version", "inventory_sha256")

        const val DDL_V1 = """
            CREATE TABLE accounts (
                account_id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                username_key TEXT NOT NULL UNIQUE,
                password_algorithm TEXT NOT NULL,
                password_iterations INTEGER NOT NULL,
                password_salt BLOB NOT NULL,
                password_hash BLOB NOT NULL,
                created_at_utc TEXT NOT NULL);
            CREATE TABLE characters (
                character_id TEXT PRIMARY KEY,
                account_id TEXT NOT NULL REFERENCES accounts(account_id),
                name TEXT NOT NULL,
                state_path TEXT NOT NULL,
                state_path_key TEXT NOT NULL UNIQUE,
                source_sha256 TEXT NOT NULL,
                registered_state_json TEXT NOT NULL,
                checkpoint_path TEXT NOT NULL,
                registered_at_utc TEXT NOT NULL);
            CREATE TABLE registry_history (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp_utc TEXT NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                account_id TEXT,
                character_id TEXT,
                detail_json TEXT NOT NULL)"""

        const val DDL_RETIREMENTS = """CREATE TABLE IF NOT EXISTS character_retirements (
                character_id TEXT PRIMARY KEY REFERENCES characters(character_id),
                retired_at_utc TEXT NOT NULL, archive_path TEXT NOT NULL, reason TEXT NOT NULL)"""

        /** Publish an empty registry (schema v1) without replacing an existing file. */
        fun initialize(path: Path, driver: SqlDriver): AccountRegistry {
            val target = path.toAbsolutePath().normalize()
            Files.createDirectories(target.parent)
            val temporary = Publish.temporaryBeside(target, "init", ".sqlite3")
            try {
                Files.delete(temporary)
                driver.open(temporary, SqlDriver.Mode.CREATE).use { db ->
                    db.execute("BEGIN IMMEDIATE")
                    db.script(DDL_V1)
                    db.execute("PRAGMA user_version=1")
                    db.execute("COMMIT")
                }
                Publish.publishNew(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return AccountRegistry(target, driver)
        }

        fun label(value: String, kind: String, maximum: Int = 128): String {
            require(value.length in 1..maximum) { "$kind must be nonempty text of at most $maximum characters" }
            require(value == value.trim() && value.none { it.code < 32 || it.code == 127 }) { "$kind cannot contain surrounding whitespace or control characters" }
            return value
        }

        fun pbkdf2(password: String, salt: ByteArray, iterations: Int = PASSWORD_ITERATIONS): ByteArray {
            // PBKDF2-HMAC-SHA256 of the password's UTF-8 bytes (the JCE provider encodes the chars as UTF-8).
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }
    }

    init {
        if (!Files.isRegularFile(this.path)) throw IllegalArgumentException("Account registry does not exist; initialize it explicitly")
        connect(readOnly = true).use { db ->
            if (db.userVersion() !in listOf(1, 2)) throw IllegalArgumentException("Unsupported account registry format")
        }
    }

    fun connect(readOnly: Boolean = false): SqlConnection {
        val db = driver.open(path, if (readOnly) SqlDriver.Mode.READ_ONLY else SqlDriver.Mode.READ_WRITE)
        db.execute("PRAGMA foreign_keys=ON")
        return db
    }

    fun storedPath(path: Path): String = DataPaths.toStored(path, base, strict = strictPaths)

    fun audit(db: SqlConnection, actor: String, action: String, accountId: String? = null, characterId: String? = null, detail: JObj? = null) {
        db.execute("INSERT INTO registry_history (timestamp_utc,actor,action,account_id,character_id,detail_json) VALUES(?,?,?,?,?,?)",
            PyTime.nowIsoMillis(), actor, action, accountId, characterId, Json.compact(detail ?: JObj()))
    }

    /** A new local account; the password is hashed with PBKDF2-HMAC-SHA256 (600,000 iterations, 32-byte salt). */
    fun createAccount(username: String, password: String, actor: String = "local-cli"): JObj {
        require(USERNAME.matches(username)) { "Username must be 1 to 64 ASCII letters/digits/dot/underscore/hyphen, starting with a letter or digit" }
        label(actor, "Actor")
        val encoded = password.toByteArray(Charsets.UTF_8)
        require(encoded.size in 12..1024) { "Password must contain 12 to 1024 UTF-8 bytes" }
        val salt = io.github.okexodus.openknights.server.Entropy.current.tokenBytes(32)
        val verifier = pbkdf2(password, salt)
        val accountId = "acc_" + io.github.okexodus.openknights.server.Entropy.current.uuid4Hex()
        val timestamp = PyTime.nowIsoMillis()
        connect().use { db ->
            db.immediate {
                if (db.queryOne("SELECT 1 FROM accounts WHERE username_key=?", username.lowercase()) != null) throw IllegalArgumentException("Username already exists")
                db.execute("INSERT INTO accounts VALUES(?,?,?,?,?,?,?,?)", accountId, username, username.lowercase(), "pbkdf2-sha256",
                    PASSWORD_ITERATIONS.toLong(), salt, verifier, timestamp)
                audit(db, actor, "account_created", accountId, detail = jobj("username" to username))
            }
        }
        return jobj("account_id" to accountId, "username" to username, "created_at_utc" to timestamp)
    }

    /**
     * Verify a local password (`authenticate`); the public account or null. Every attempt is audited
     * (`login_verified` / `login_rejected`), and an unknown username still performs the same PBKDF2 derivation.
     */
    fun authenticate(username: Any?, password: Any?, actor: String = "local-cli"): JObj? {
        label(actor, "Actor")
        val validUsername = username is String && USERNAME.matches(username)
        val passwordBytes = (password as? String)?.toByteArray(Charsets.UTF_8)
        if (passwordBytes == null || passwordBytes.size !in 12..1024) {
            connect().use { db -> db.immediate { audit(db, actor, "login_rejected") } }
            return null
        }
        return connect().use { db ->
            val row = db.queryOne("SELECT * FROM accounts WHERE username_key=?", if (validUsername) (username as String).lowercase() else "")
            if (row != null && (row.string("password_algorithm") != "pbkdf2-sha256" || row.long("password_iterations") != PASSWORD_ITERATIONS.toLong()
                    || row.bytes("password_salt").size != 32 || row.bytes("password_hash").size != 32)) {
                throw IllegalArgumentException("Unsupported local password verifier format")
            }
            val salt = row?.bytes("password_salt") ?: ByteArray(32)
            val actual = pbkdf2(password as String, salt)
            val valid = MessageDigest.isEqual(actual, row?.bytes("password_hash") ?: ByteArray(32)) && row != null
            db.immediate { audit(db, actor, if (valid) "login_verified" else "login_rejected", if (valid) row!!.string("account_id") else null) }
            if (valid) jobj("account_id" to row!!.string("account_id"), "username" to row.string("username"), "created_at_utc" to row.string("created_at_utc")) else null
        }
    }

    fun getAccount(accountId: String): JObj = connect(readOnly = true).use { db ->
        val row = db.queryOne("SELECT account_id,username,created_at_utc FROM accounts WHERE account_id=?", accountId)
            ?: throw IllegalArgumentException("Local account does not exist")
        jobj("account_id" to row.string("account_id"), "username" to row.string("username"), "created_at_utc" to row.string("created_at_utc"))
    }

    /**
     * Register an existing save for an account (`register_character`): the save and its checkpoint must hold the same
     * complete state at the expected revision and hashes; the registry keeps the stored (root-relative) paths.
     */
    fun registerCharacter(accountId: String, name: String, statePath: Path, expectedRevision: Long, expectedSourceSha256: String,
                          expectedPayloadSha256: String, checkpointPath: Path, actor: String = "local-cli", characterId: String? = null): Character {
        label(name, "Character name", 80)
        label(actor, "Actor")
        require(characterId == null || Regex("char_[0-9a-f]{32}").matches(characterId)) { "A pre-generated character ID must be char_ plus 32 lowercase hex digits" }
        require(expectedRevision >= 1) { "Expected revision must be a positive integer" }
        for (checksum in listOf(expectedSourceSha256, expectedPayloadSha256)) require(Regex("[0-9a-f]{64}").matches(checksum)) { "Expected hashes must be lowercase SHA-256 hex" }
        val state = statePath.toRealPath()
        val checkpoint = checkpointPath.toRealPath()
        require(!Files.isSameFile(state, checkpoint)) { "Registration requires a separate checkpoint file" }
        val storedState = storedPath(state)
        val storedCheckpoint = storedPath(checkpoint)
        val id = characterId ?: ("char_" + io.github.okexodus.openknights.server.Entropy.current.uuid4Hex())
        val timestamp = PyTime.nowIsoMillis()
        connect().use { db ->
            db.immediate {
                if (db.queryOne("SELECT 1 FROM accounts WHERE account_id=?", accountId) == null) throw IllegalArgumentException("Local account does not exist")
                if (db.queryOne("SELECT 1 FROM characters WHERE character_id=?", id) != null) throw IllegalArgumentException("Character ID already registered")
                for (row in db.query("SELECT state_path,state_path_key FROM characters")) {
                    val existing = DataPaths.fromStored(row.string("state_path"), base)
                    if (row.string("state_path_key") in setOf(DataPaths.keyOf(storedState), state.toString().lowercase())
                        || (Files.exists(existing) && Files.isSameFile(state, existing))) {
                        throw IllegalArgumentException("State database is already owned by a local character")
                    }
                }
                val current = StateStore(state, driver).read()
                val saved = StateStore(checkpoint, driver).read()
                if (current.revision != expectedRevision || current.sourceSha256 != expectedSourceSha256 || current.payloadSha256 != expectedPayloadSha256) {
                    throw IllegalArgumentException("State revision or hash changed; inspect and checkpoint again")
                }
                fun identity(c: StateStore.Current) = jobj("revision" to c.revision, "source_sha256" to c.sourceSha256, "payload_sha256" to c.payloadSha256,
                    "inventory_schema_version" to c.inventorySchemaVersion, "inventory_sha256" to c.inventorySha256)
                if (identity(current) != identity(saved)) throw IllegalArgumentException("Checkpoint does not match the complete current saved state")
                val ident = identity(current)
                db.execute("INSERT INTO characters VALUES(?,?,?,?,?,?,?,?,?)", id, accountId, name, storedState, DataPaths.keyOf(storedState),
                    current.sourceSha256, Json.compact(ident), storedCheckpoint, timestamp)
                val detail = jobj("name" to name, "state_path" to storedState, "checkpoint_path" to storedCheckpoint)
                ident.forEach { (k, v) -> detail[k] = v }
                audit(db, actor, "character_registered", accountId, id, detail)
            }
        }
        return getCharacter(id)
    }

    fun accountByUsername(username: String): SqlRow? =
        connect(readOnly = true).use { it.queryOne("SELECT account_id,username FROM accounts WHERE username_key=?", username.lowercase()) }

    private fun retiredFilter(db: SqlConnection, alias: String = "characters"): String =
        if (db.tableExists("character_retirements")) " $alias.character_id NOT IN (SELECT character_id FROM character_retirements)" else ""

    /** A registered character as the reference returns it (stored paths resolved under the registry's directory). */
    class Character(val characterId: String, val accountId: String, val name: String, val statePathStored: String, val statePath: Path,
                    val sourceSha256: String, val checkpointPath: Path, val registeredAtUtc: String, val registeredState: JValue)

    private fun character(row: SqlRow) = Character(row.string("character_id"), row.string("account_id"), row.string("name"),
        row.string("state_path"), DataPaths.fromStored(row.string("state_path"), base), row.string("source_sha256"),
        DataPaths.fromStored(row.string("checkpoint_path"), base), row.string("registered_at_utc"), Json.loads(row.string("registered_state_json")))

    fun listCharacters(accountId: String? = null): List<Character> = connect(readOnly = true).use { db ->
        val clauses = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (accountId != null) { clauses.add(" account_id=?"); args.add(accountId) }
        val retired = retiredFilter(db)
        if (retired.isNotEmpty()) clauses.add(retired)
        var query = "SELECT * FROM characters"
        if (clauses.isNotEmpty()) query += " WHERE" + clauses.joinToString(" AND")
        query += " ORDER BY registered_at_utc,character_id"
        db.query(query, *args.toTypedArray()).map { character(it) }
    }

    fun getCharacter(characterId: String): Character = connect(readOnly = true).use { db ->
        val retired = retiredFilter(db)
        val row = db.queryOne("SELECT * FROM characters WHERE character_id=?" + (if (retired.isNotEmpty()) " AND$retired" else ""), characterId)
            ?: throw IllegalArgumentException("Local character does not exist")
        character(row)
    }

    /** Validate an owned save before use (the full integrity read); revisions may advance normally. */
    fun resolveStateStore(characterId: String, accountId: String? = null): StateStore {
        val character = getCharacter(characterId)
        if (accountId != null && character.accountId != accountId) throw IllegalArgumentException("Character is not owned by this local account")
        val store = StateStore(character.statePath, driver)
        val current = store.read()
        if (current.sourceSha256 != character.sourceSha256) throw IllegalArgumentException("Character save source no longer matches its registered ownership")
        val secondary = current.secondaryTeam
        if (secondary != null && secondary.obj("document").str("character_id") != characterId) {
            throw IllegalArgumentException("Secondary team does not belong to registered character")
        }
        store.key = character.statePathStored
        store.root = base
        return store
    }
}

/** An expected authentication or authorization denial, without credential detail. */
class AuthenticationRejected(message: String) : IllegalArgumentException(message)

/**
 * Persistent local sessions (`local_auth.py`): a random 256-bit token (base64url, 43 characters) of which only the
 * SHA-256 is stored, a lifetime that slides while the session is used, and the password-free device owner.
 */
class LocalAuth(val registry: AccountRegistry, private val clock: () -> Long = { io.github.okexodus.openknights.exact.Now.epoch() }) {
    companion object {
        const val DEFAULT_SESSION_TTL = 3600
        const val MAX_SESSION_TTL = 86400
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")

        const val DDL_SESSIONS = """CREATE TABLE local_sessions (
                    session_id TEXT PRIMARY KEY,
                    token_sha256 TEXT NOT NULL UNIQUE CHECK(length(token_sha256)=64),
                    account_id TEXT NOT NULL REFERENCES accounts(account_id),
                    character_id TEXT REFERENCES characters(character_id),
                    issued_epoch INTEGER NOT NULL CHECK(issued_epoch>=0),
                    ttl_seconds INTEGER NOT NULL CHECK(ttl_seconds BETWEEN 1 AND 86400),
                    expires_epoch INTEGER NOT NULL CHECK(expires_epoch=issued_epoch+ttl_seconds),
                    revoked_epoch INTEGER CHECK(revoked_epoch IS NULL OR revoked_epoch>=0))"""

        /** The additive v1 → v2 migration (sessions); repeating it is a no-op. */
        fun initialize(registry: AccountRegistry, actor: String = "local-admin"): LocalAuth {
            AccountRegistry.label(actor, "Actor")
            registry.connect().use { db ->
                db.immediate {
                    when (db.userVersion()) {
                        1 -> {
                            db.execute(DDL_SESSIONS)
                            db.execute("CREATE INDEX local_sessions_account ON local_sessions(account_id)")
                            registry.audit(db, actor, "local_sessions_initialized", detail = jobj("schema_version" to 2))
                            db.execute("PRAGMA user_version=2")
                        }
                        2 -> {}
                        else -> throw IllegalArgumentException("Unsupported account registry format")
                    }
                }
            }
            return LocalAuth(registry)
        }

        fun tokenHash(token: String): String {
            if (!TOKEN.matches(token)) throw AuthenticationRejected("Invalid or expired local session")
            return MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
        }

        private fun timestamp(epoch: Long) = PyTime.isoSecondsUtc(epoch)
    }

    /** The password-free owner's username (release mode: `owner`); null = credentials required. */
    var deviceOwner: String? = null

    init {
        registry.connect(readOnly = true).use { db ->
            if (db.userVersion() != 2) throw IllegalArgumentException("Local sessions are not initialized; initialize explicitly after backing up the registry")
            db.query("SELECT session_id,token_sha256,account_id,character_id,issued_epoch,ttl_seconds,expires_epoch,revoked_epoch FROM local_sessions LIMIT 0")
        }
    }

    class Session(val sessionId: String, val accountId: String, val username: String, val characterId: String?,
                  val issuedAtUtc: String, val expiresAtUtc: String)

    class Issued(val token: String, val session: Session)

    private fun session(row: SqlRow) = Session(row.string("session_id"), row.string("account_id"), row.string("username"),
        row.stringOrNull("character_id"), timestamp(row.long("issued_epoch")), timestamp(row.long("expires_epoch")))

    private fun epoch(): Long = clock().also { require(it >= 0) { "Local authentication clock must provide a nonnegative epoch" } }

    /** The session row with every check except its expiry; (row, expired). */
    private fun readRow(db: SqlConnection, tokenHash: String, now: Long): Pair<SqlRow, Boolean> {
        val row = db.queryOne("""SELECT s.*,a.username,c.account_id AS character_owner
            FROM local_sessions s JOIN accounts a ON a.account_id=s.account_id
            LEFT JOIN characters c ON c.character_id=s.character_id
            WHERE s.token_sha256=?""", tokenHash)
        if (row == null || row["revoked_epoch"] != null
            || row["issued_epoch"] !is Long || row["ttl_seconds"] !is Long || row["expires_epoch"] !is Long
            || row.long("issued_epoch") !in 0..now
            || row.long("ttl_seconds") !in 1..MAX_SESSION_TTL.toLong()
            || row.long("expires_epoch") != row.long("issued_epoch") + row.long("ttl_seconds")
            || (row["character_id"] != null && row["character_owner"] != row["account_id"])) {
            throw AuthenticationRejected("Invalid or expired local session")
        }
        return row to (now >= row.long("expires_epoch"))
    }

    private fun readValid(db: SqlConnection, tokenHash: String, now: Long): SqlRow {
        val (row, expired) = readRow(db, tokenHash, now)
        if (expired) throw AuthenticationRejected("Invalid or expired local session")
        return row
    }

    private fun deviceOwned(row: SqlRow): Boolean = deviceOwner?.let { row.string("username").lowercase() == it.lowercase() } ?: false

    /** Sliding lifetime: renewed to a fresh lifetime once less than half remains (device-owner sessions even after expiry). */
    private fun renewIfDue(tokenHash: String) {
        val now = epoch()
        registry.connect().use { db ->
            val (row0, expired0) = readRow(db, tokenHash, now)
            if (expired0 && !deviceOwned(row0)) throw AuthenticationRejected("Invalid or expired local session")
            if ((row0.long("expires_epoch") - now).toDouble() >= row0.long("ttl_seconds") / 2.0) return
            db.immediate {
                val (row, expired) = readRow(db, tokenHash, now)
                if (expired && !deviceOwned(row)) throw AuthenticationRejected("Invalid or expired local session")
                if ((row.long("expires_epoch") - now).toDouble() < row.long("ttl_seconds") / 2.0) {
                    val ttl = row.long("ttl_seconds")
                    db.execute("UPDATE local_sessions SET issued_epoch=?, expires_epoch=? WHERE session_id=?", now, now + ttl, row.string("session_id"))
                    registry.audit(db, "local-service", "session_renewed", row.string("account_id"), row.stringOrNull("character_id"),
                        jobj("session_id" to row.string("session_id"), "was_expired" to expired,
                            "previous_expires_at_utc" to timestamp(row.long("expires_epoch")), "expires_at_utc" to timestamp(now + ttl)))
                }
            }
        }
    }

    /** A session for local credentials (`login`); rejected credentials raise [AuthenticationRejected]. */
    fun login(username: Any?, password: Any?, ttlSeconds: Int = DEFAULT_SESSION_TTL, actor: String = "local-client"): Issued {
        require(ttlSeconds in 1..MAX_SESSION_TTL) { "Session lifetime must be an integer from 1 to $MAX_SESSION_TTL seconds" }
        AccountRegistry.label(actor, "Actor")
        val account = registry.authenticate(username, password, actor) ?: throw AuthenticationRejected("Local credentials rejected")
        return issue(account.str("account_id"), ttlSeconds, actor, JObj())
    }

    /** Password-free session for the device owner (a single-owner install; the gateway is loopback-only). */
    fun deviceLogin(ttlSeconds: Int = DEFAULT_SESSION_TTL, actor: String = "local-device"): Issued {
        require(ttlSeconds in 1..MAX_SESSION_TTL) { "Session lifetime must be an integer from 1 to $MAX_SESSION_TTL seconds" }
        AccountRegistry.label(actor, "Actor")
        val username = deviceOwner ?: throw AuthenticationRejected("Device sign-in is not enabled")
        val row = registry.accountByUsername(username) ?: throw AuthenticationRejected("Device owner account is not registered")
        return issue(row.string("account_id"), ttlSeconds, actor, jobj("device_owner" to true))
    }

    private fun issue(accountId: String, ttlSeconds: Int, actor: String, detail: JObj): Issued {
        val now = epoch()
        val token = io.github.okexodus.openknights.server.Entropy.current.tokenUrlsafe(32)
        val tokenHash = tokenHash(token)
        val sessionId = "sess_" + io.github.okexodus.openknights.server.Entropy.current.uuid4Hex()
        val row = registry.connect().use { db ->
            db.immediate {
                db.execute("INSERT INTO local_sessions VALUES(?,?,?,?,?,?,?,?)", sessionId, tokenHash, accountId, null,
                    now, ttlSeconds.toLong(), now + ttlSeconds, null)
                val full = jobj("session_id" to sessionId, "expires_at_utc" to timestamp(now + ttlSeconds))
                detail.forEach { (k, v) -> full[k] = v }
                registry.audit(db, actor, "session_issued", accountId, detail = full)
                readValid(db, tokenHash, now)
            }
        }
        return Issued(token, session(row))
    }

    /** Validate a token on every use (the lifetime slides first). */
    fun authenticate(token: String): Session {
        val hash = tokenHash(token)
        renewIfDue(hash)
        val now = epoch()
        return registry.connect(readOnly = true).use { db -> session(readValid(db, hash, now)) }
    }

    /** The owned save for a bound session (its character) or an unbound one (any owned character). */
    fun resolveCharacter(token: String, characterId: String): StateStore {
        val session = authenticate(token)
        if (session.characterId != null && session.characterId != characterId) throw AuthenticationRejected("Local character selection rejected")
        return try { registry.resolveStateStore(characterId, session.accountId) } catch (e: IllegalArgumentException) {
            if (e is AuthenticationRejected) throw e
            throw AuthenticationRejected("Local character selection rejected")
        }
    }

    private val preferredBySession = HashMap<String, String?>()

    fun preferred(token: String): String? = preferredBySession[authenticate(token).sessionId]

    fun prefer(token: String, characterId: String?) {
        val session = authenticate(token)
        if (characterId != null) resolveCharacter(token, characterId)
        preferredBySession[session.sessionId] = characterId
    }
}

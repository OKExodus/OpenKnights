package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.game.Guild
import io.github.okexodus.openknights.server.game.NotPorted
import io.github.okexodus.openknights.server.session.ServiceLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A backup cannot be made or used (the message is safe to show). */
class BackupError(message: String) : IllegalArgumentException(message)

/**
 * Back up, restore, delete and reset the saves of a release data root (`save_management.py`). A `.okbackup` is a zip
 * with `manifest.json` (format, kind, versions, schemas, times, every file's size and SHA-256) and consistent page
 * copies of every database. Restores check the manifest and every hash, refuse a newer format, unpack into a new
 * generation and switch the active world in one manifest replace after a safety copy. Nothing is destroyed: deleted
 * characters, replaced and reset worlds go to `trash/`; only [emptyTrash] (explicit confirmation) removes anything.
 *
 * The caller holds the data root exclusively (the service is stopped, or the call runs between its requests). [clock]
 * is the running service's device clock, or null when the service is stopped (then the host clock).
 */
object SaveManagement {
    const val FORMAT = "openknights_backup"
    const val FORMAT_VERSION = 1
    const val SUFFIX = ".okbackup"
    const val DAILY_KEEP = 3
    const val EMPTY_TRASH_WORD = "EMPTY"
    const val RESET_WORD = "RESET"
    private val LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    private fun deleteTree(path: Path) {
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun tempDirectory(): Path = Files.createTempDirectory("okbackup-")

    private fun sqliteCopy(source: Path, target: Path, driver: SqlDriver) = driver.backup(source, target)

    /** `isoformat(timespec="seconds")` of the device-local (or host-local) time now. */
    private fun localNow(clock: DeviceClock?): String {
        if (clock != null) {
            val epoch = clock.now()
            return LOCAL_SECONDS.format(clock.localDateTime(epoch))
        }
        val epoch = Now.epoch()
        return LOCAL_SECONDS.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.ofTotalSeconds(Now.offset(epoch))))
    }

    private fun generationFiles(generation: Path, driver: SqlDriver): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        val files = Files.walk(generation).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sqlite3") }.toList() }
        val tmp = tempDirectory()
        try {
            for (path in files.sortedBy { generation.relativize(it).joinToString("/") }) {
                val relative = generation.relativize(path).joinToString("/")
                if (relative.startsWith(".") || relative.contains("/.")) continue     // a temporary publish file
                val copy = tmp.resolve("copy-${out.size}.sqlite3")
                sqliteCopy(path, copy, driver)
                out["world/$relative"] = Files.readAllBytes(copy)
            }
        } finally {
            deleteTree(tmp)
        }
        return out
    }

    private fun manifest(kind: String, files: Map<String, ByteArray>, schemas: JObj, clock: DeviceClock?, extra: Map<String, Any?>): JObj {
        val document = jobj("format" to FORMAT, "format_version" to FORMAT_VERSION, "kind" to kind, "app_version" to DataRoot.APP_VERSION,
            "layout_version" to DataRoot.LAYOUT_VERSION, "schemas" to schemas, "created_utc" to PyTime.nowIsoMillis(),
            "created_local" to localNow(clock),
            "files" to files.keys.sorted().map { jobj("path" to it, "bytes" to files.getValue(it).size, "sha256" to sha256Hex(files.getValue(it))) })
        extra.forEach { (k, v) -> document[k] = io.github.okexodus.openknights.exact.jvalue(v) }
        return document
    }

    private fun publishZip(entries: Map<String, ByteArray>, manifest: JObj, target: Path): Path {
        Files.createDirectories(target.toAbsolutePath().parent)
        val temporary = Publish.temporaryBeside(target, "init", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write((Json.dumps(manifest, sortKeys = true, indent = 1) + "\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (path in entries.keys.sorted()) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(entries.getValue(path))
                    zip.closeEntry()
                }
            }
            Publish.publishNew(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    // --- backup -----------------------------------------------------------------------------------------------------

    /** The whole active world (registry, world, bots, every character and checkpoint) as one `.okbackup`. */
    fun backupFull(root: DataRoot, target: Path, driver: SqlDriver, clock: DeviceClock?): Pair<Path, JObj> {
        val generation = root.active ?: throw BackupError("There is nothing to back up yet: no character has been created")
        val files = generationFiles(generation, driver)
        val manifest = manifest("full", files, root.schemaVersions(generation.fileName.toString()), clock,
            mapOf("world_seed" to worldSeed(generation.resolve(DataRoot.WORLD), driver)))
        return publishZip(files, manifest, target) to manifest
    }

    private fun worldSeed(path: Path, driver: SqlDriver): String? = if (Files.exists(path)) WorldDirectory(path, driver).worldSeed() else null

    /** What the world holds about a character (informational in a character backup: the world stays authoritative). */
    private fun worldEntries(world: WorldDirectory, characterId: String, wireId: Long): JObj {
        val member = world.member(characterId) ?: world.allEntries().firstOrNull { it.strOrNull("character_id") == characterId }
        val guilds = world.document("guilds")?.second
        val social = world.document("social")?.second
        val arena = world.document("arena_ladder")?.second
        val (gid, guild) = Guild.guildOf(guilds ?: jobj("guilds" to JObj()), wireId)
        val seat = if (guild != null) Guild.memberOf(guild, wireId) else null
        val ranks = ((arena ?: JObj())["ranks"] as? JArr) ?: JArr()
        val rankIndex = ranks.indexOfFirst { (it as? JInt)?.toLong() == wireId }
        val entry = member?.let { m -> JObj().also { o -> for (k in listOf("name", "wire_account_id", "kind", "starter", "gender", "status", "created_at_utc")) o[k] = m[k] ?: JNull } }
        val friends = (((social ?: JObj())["friends"] as? JObj)?.get(wireId.toString()) as? JArr) ?: JArr()
        return jobj("entry" to (entry ?: JNull), "guild" to (if (seat != null) jobj("id" to gid, "position" to seat["position"]) else JNull),
            "friends" to JArr(ArrayList(friends)), "arena_rank" to (if (rankIndex >= 0) JInt(rankIndex + 1L) else JNull))
    }

    private fun roleText(state: JObj, id: Long): JValue? =
        state.arr("role_properties").map { it as JObj }.firstOrNull { it.long("id") == id }?.obj("value")?.get("text")

    /** One character: its save (+ creation checkpoint) and its world entries (name, guild seat, friends, rank row). */
    fun backupCharacter(root: DataRoot, characterId: String, target: Path, driver: SqlDriver, clock: DeviceClock?): Pair<Path, JObj> {
        val generation = root.active ?: throw BackupError("There is no world yet")
        val registry = AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
        val character = registry.getCharacter(characterId)
        val files = LinkedHashMap<String, ByteArray>()
        val current: StateStore.Current
        val tmp = tempDirectory()
        try {
            for ((name, source) in listOf("save.sqlite3" to character.statePath, "checkpoint-0001.sqlite3" to character.checkpointPath)) {
                if (Files.isRegularFile(source)) {
                    val copy = tmp.resolve(name)
                    sqliteCopy(source, copy, driver)
                    files["character/$name"] = Files.readAllBytes(copy)
                }
            }
            current = StateStore(tmp.resolve("save.sqlite3"), driver).read()
        } finally {
            deleteTree(tmp)
        }
        val profile = current.characterProfile ?: throw BackupError("Only characters created in release mode can be backed up alone")
        val wire = profile.obj("document").long("wire_account_id")
        val world = WorldDirectory(generation.resolve(DataRoot.WORLD), driver, strictPaths = true)
        val entries = worldEntries(world, characterId, wire)
        files["character/world-entry.json"] = (Json.dumps(entries, sortKeys = true, indent = 1) + "\n").toByteArray(Charsets.UTF_8)
        val name = roleText(current.state, 2) ?: JStr("")
        val manifest = manifest("character", files, jobj("save" to 1), clock, mapOf("character" to jobj("id" to characterId, "name" to name)))
        return publishZip(files, manifest, target) to manifest
    }

    // --- reading a backup ---------------------------------------------------------------------------------------------

    /** (manifest, {path: bytes}) of a valid backup; [BackupError] with a plain reason otherwise. */
    fun readBackup(path: Path): Pair<JObj, Map<String, ByteArray>> {
        val manifest: JObj
        val files = LinkedHashMap<String, ByteArray>()
        try {
            ZipFile(path.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                if ("manifest.json" !in names) throw BackupError("This is not an OpenKnights backup (no manifest)")
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(zip.getInputStream(zip.getEntry("manifest.json")).readBytes())).toString()
                manifest = Json.loads(text).asObj
                for (n in names) if (n != "manifest.json") files[n] = zip.getInputStream(zip.getEntry(n)).readBytes()
            }
        } catch (e: BackupError) {
            throw e
        } catch (e: java.io.IOException) {
            throw BackupError("The backup file is damaged (not a readable zip)")
        } catch (e: IllegalArgumentException) {
            throw BackupError("The backup manifest is damaged")
        }
        if (manifest.strOrNull("format") != FORMAT) throw BackupError("This is not an OpenKnights backup")
        val version = (manifest["format_version"] as? JInt)?.value
        if (version == null || version < java.math.BigInteger.ONE) throw BackupError("The backup format version is invalid")
        if (version > java.math.BigInteger.valueOf(FORMAT_VERSION.toLong())) {
            throw BackupError("This backup was made by a newer OpenKnights (backup format $version); this version reads up to format $FORMAT_VERSION. Update OpenKnights to restore it.")
        }
        val layout = (manifest["layout_version"] as? JInt)?.value
        if (layout == null || layout > java.math.BigInteger.valueOf(DataRoot.LAYOUT_VERSION.toLong())) {
            throw BackupError("This backup was made by a newer OpenKnights (data layout); update to restore it")
        }
        val listed = LinkedHashMap<String, JObj>()
        for (f in (manifest["files"] as? JArr) ?: JArr()) listed[(f as JObj).str("path")] = f
        if (listed.keys != files.keys) throw BackupError("The backup does not contain exactly the files its manifest lists")
        for ((name, data) in files) {
            val parts = name.split("/")
            if (name.startsWith("/") || ".." in parts || "\\" in name || ":" in name) throw BackupError("The backup contains an unsafe path")
            val entry = listed.getValue(name)
            if (data.size.toLong() != entry.long("bytes") || sha256Hex(data) != entry.str("sha256")) {
                throw BackupError("The backup is damaged: ${parts.last()} does not match its checksum")
            }
        }
        val required = when (manifest.strOrNull("kind")) {
            "full" -> setOf("world/${DataRoot.REGISTRY}", "world/${DataRoot.WORLD}")
            "character" -> setOf("character/save.sqlite3", "character/world-entry.json")
            else -> throw BackupError("Unknown backup kind")
        }
        if (!files.keys.containsAll(required)) throw BackupError("The backup is incomplete")
        return manifest to files
    }

    // --- migrations and checks ------------------------------------------------------------------------------------

    /** Bring every database of an unpacked generation to the current schemas (additive migrations only). */
    fun migrateGeneration(folder: Path, driver: SqlDriver, actor: String): List<String> {
        val steps = ArrayList<String>()
        val root = DataRoot(folder.parent.parent, driver)
        if (root.databaseVersion(folder.resolve(DataRoot.REGISTRY)) == 1) {
            LocalAuth.initialize(AccountRegistry(folder.resolve(DataRoot.REGISTRY), driver), actor)
            steps.add("registry 1 -> 2")
        }
        val version = root.databaseVersion(folder.resolve(DataRoot.WORLD))
        if (version != null && version < WorldDirectory.SCHEMA_VERSION) throw NotPorted("world database migrations $version -> ${WorldDirectory.SCHEMA_VERSION}")
        if (!Files.exists(folder.resolve(DataRoot.BOTS))) {
            BotsDatabase.initialize(folder.resolve(DataRoot.BOTS), driver, WorldDirectory(folder.resolve(DataRoot.WORLD), driver).worldSeed() ?: "migrated")
            steps.add("bots database added")
        }
        return steps
    }

    /** Every database of a generation opens and every registered character resolves (before it becomes active). */
    private fun verifyGeneration(folder: Path, driver: SqlDriver): Int {
        val registry = AccountRegistry(folder.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
        val world = WorldDirectory(folder.resolve(DataRoot.WORLD), driver, strictPaths = true)
        val characters = registry.listCharacters()
        for (character in characters) {
            val current = registry.resolveStateStore(character.characterId).read()
            val member = world.member(character.characterId)
            if (current.characterProfile == null || member == null) throw BackupError("The backup holds a character that is not a member of its world")
            if (member.long("wire_account_id") != current.characterProfile.obj("document").long("wire_account_id")) {
                throw BackupError("The backup's world and a character disagree about its identity")
            }
        }
        return characters.size
    }

    // --- restore ------------------------------------------------------------------------------------------------------

    /** An automatic full copy into auto-backups/ (only when a world exists). */
    fun safetyCopy(root: DataRoot, reason: String, driver: SqlDriver, clock: DeviceClock?): Path? {
        if (root.active == null) return null
        val target = root.autoBackups.resolve("$reason-${PyTime.nowStamp()}$SUFFIX")
        return backupFull(root, target, driver, clock).first
    }

    private fun writeNew(target: Path, data: ByteArray) {
        Files.createDirectories(target.parent)
        java.nio.channels.FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap(data))
            ch.force(true)
        }
    }

    private fun dirs(folder: Path): List<Path> =
        Files.list(folder).use { s -> s.filter { Files.isDirectory(it) }.sorted(compareBy { it.fileName.toString() }).toList() }

    /** Replace the whole data root's world by a full backup (atomic switch; the old world goes to trash/). */
    fun restoreFull(root: DataRoot, backupPath: Path, driver: SqlDriver, clock: DeviceClock?, actor: String = "local-admin"): JObj {
        val (manifest, files) = readBackup(backupPath)
        if (manifest.str("kind") != "full") throw BackupError("This is a character backup; restore it as a character")
        val safety = safetyCopy(root, "pre-restore", driver, clock)
        val generation = root.worlds.resolve(DataRoot.newGenerationName())
        Files.createDirectory(generation)
        val steps: List<String>
        val count: Int
        try {
            for ((name, data) in files) {
                val parts = name.split("/").drop(1)
                writeNew(parts.fold(generation) { p, part -> p.resolve(part) }, data)
            }
            steps = migrateGeneration(generation, driver, actor)
            count = verifyGeneration(generation, driver)
        } catch (e: Exception) {
            root.moveToTrash(generation, "failed-restore")
            throw e
        }
        val previous = root.commitActive(generation.fileName.toString(), reason = "full restore of ${backupPath.fileName}")
        val moved = ArrayList<String>()
        if (previous != null) moved.add(root.moveToTrash(previous, "replaced-by-restore").fileName.toString())
        for (folder in dirs(root.worlds)) {
            if (folder.fileName == generation.fileName) continue
            moved.add(root.moveToTrash(folder, "unborn-before-restore").fileName.toString())
        }
        return jobj("restored" to manifest.str("created_utc"), "characters" to count, "migrations" to steps,
            "safety_copy" to safety?.fileName?.toString(), "trash" to moved, "generation" to generation.fileName.toString())
    }

    private fun ownerAccount(registry: AccountRegistry): JObj =
        registry.listAccounts().firstOrNull { it.str("username").lowercase() == "owner" } ?: throw BackupError("The data root has no device owner account")

    /** The active generation (born); an unborn root is born by the restore (its world starts fresh). */
    private fun ensureWorld(root: DataRoot, driver: SqlDriver): Pair<Path, Boolean> {
        root.active?.let { return it to false }
        val generation = root.unbornStaging()
        io.github.okexodus.openknights.server.session.Service.ownerRegistry(generation, driver)
        val seed = Entropy.current.tokenHex(16)
        if (!Files.exists(generation.resolve(DataRoot.WORLD))) WorldDirectory.initialize(generation.resolve(DataRoot.WORLD), driver, seed)
        if (!Files.exists(generation.resolve(DataRoot.BOTS))) {
            BotsDatabase.initialize(generation.resolve(DataRoot.BOTS), driver, WorldDirectory(generation.resolve(DataRoot.WORLD), driver).worldSeed() ?: seed)
        }
        return generation to true
    }

    private fun nextWire(db: SqlConnection): Long {
        val top = db.queryOne("SELECT MAX(wire_account_id) AS m FROM world_characters WHERE wire_account_id>=?", FreshProfile.WIRE_ID_FIRST)?.longOrNull("m")
        var wire = if (top == null) FreshProfile.WIRE_ID_FIRST else top + 1
        if (wire in WorldDirectory.BOT_ID_FIRST..WorldDirectory.BOT_ID_LAST) wire = WorldDirectory.BOT_ID_LAST + 1
        if (wire > FreshProfile.WIRE_ID_MAX) throw BackupError("The world has no free character identities left")
        return wire
    }

    /**
     * Restore one character into the current world: the save is authoritative for personal progress, the world for
     * shared data; the guild seat is kept only if that guild still lists the character; a name now taken by another
     * character (or a bot) answers `needs_rename` unless [newName] is given; the Arena rank is re-entered by the Arena's
     * own rules; a wire id held by another world entry is replaced by a newly allocated one (save + profile updated).
     */
    fun restoreCharacter(root: DataRoot, backupPath: Path, driver: SqlDriver, clock: DeviceClock?, newName: String? = null,
                         actor: String = "local-admin"): JObj {
        val (manifest, files) = readBackup(backupPath)
        if (manifest.str("kind") != "character") throw BackupError("This is a full backup; restore it as the whole data root")
        val characterId = manifest.obj("character").str("id")
        val current: StateStore.Current
        val tmp = tempDirectory()
        try {
            val staged = tmp.resolve("save.sqlite3")
            Files.write(staged, files.getValue("character/save.sqlite3"))
            current = StateStore(staged, driver).read()
        } finally {
            deleteTree(tmp)
        }
        val profile = current.characterProfile?.obj("document")
        if (profile == null || profile.strOrNull("character_id") != characterId) throw BackupError("The character backup's save does not belong to its manifest")
        val wire = profile.long("wire_account_id")
        var name = manifest.obj("character").str("name")
        if (newName != null) {
            name = try { FreshProfile.normalizeName(newName) } catch (e: FreshProfile.CreationRejected) { throw BackupError(e.message ?: "") }
        }
        val (generation, bornNow) = ensureWorld(root, driver)
        val registry = AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
        val world = WorldDirectory(generation.resolve(DataRoot.WORLD), driver, strictPaths = true)
        val owner = ownerAccount(registry)
        val entries = world.allEntries()
        val mine = entries.firstOrNull { it.strOrNull("character_id") == characterId }
        val key = FreshProfile.nameKey(name)
        val holder = entries.firstOrNull { it.str("name_key") == key }
        if ((holder != null && holder.strOrNull("character_id") != characterId) || key in world.botNames) {
            return jobj("status" to "needs_rename", "character_id" to characterId, "name" to name,
                "reason" to "That name is already taken in this world; choose another (--new-name)")
        }
        val wireHolder = entries.firstOrNull { it.long("wire_account_id") == wire }
        val newWire = wireHolder != null && wireHolder.strOrNull("character_id") != characterId
        var safety: Path? = null
        val registered = registry.listCharacters().associateBy { it.characterId }
        val folder = generation.resolve("characters").resolve(characterId)
        if (characterId in registered) {
            safety = root.autoBackups.resolve("pre-restore-${characterId.takeLast(8)}-${PyTime.nowStamp()}$SUFFIX")
            backupCharacter(root, characterId, safety, driver, clock)
            root.moveToTrash(registered.getValue(characterId).statePath.parent, "replaced-${characterId.takeLast(8)}")
        } else if (Files.exists(folder)) {
            root.moveToTrash(folder, "stale-folder")
        }
        Files.createDirectories(folder)
        val save = folder.resolve("save.sqlite3")
        writeNew(save, files.getValue("character/save.sqlite3"))
        val checkpoint = folder.resolve("checkpoint-0001.sqlite3")
        writeNew(checkpoint, files["character/checkpoint-0001.sqlite3"] ?: files.getValue("character/save.sqlite3"))
        // World entry: reuse this character's own entry (reactivated after a deletion) or make one.
        val wireFinal: Long = world.connect().use { db ->
            db.immediate {
                val final: Long
                val entryId: String
                if (mine != null) {
                    final = if (!newWire) wire else nextWire(db)
                    db.execute("UPDATE world_characters SET status='active',name=?,name_key=?,wire_account_id=?,account_id=?,state_path=?,updated_at_utc=? WHERE entry_id=?",
                        name, key, final, owner.str("account_id"), world.stored(save), PyTime.nowIsoMillis(), mine.str("entry_id"))
                    entryId = mine.str("entry_id")
                } else {
                    final = if (!newWire) wire else nextWire(db)
                    entryId = "wc_" + Entropy.current.uuid4Hex()
                    db.execute("INSERT INTO world_characters VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", entryId, characterId, owner.str("account_id"),
                        name, key, final, "fresh", (profile["starter"] as? JInt)?.toLong(), (profile["gender"] as? JInt)?.toLong(), "active",
                        world.stored(save), PyTime.nowIsoMillis(), PyTime.nowIsoMillis())
                }
                world.audit(db, actor, "restore_character", entryId, characterId,
                    jobj("name" to name, "wire_account_id" to final, "backup_created_utc" to manifest.str("created_utc")))
                final
            }
        }
        val store = StateStore(save, driver)
        val savedName = roleText(current.state, 2)
        val renamed = savedName != JStr(name)
        if (wireFinal != wire || renamed) {
            store.restoreReidentify(store.read().revision, actor, "character restore into this world",
                wireAccountId = if (wireFinal != wire) wireFinal else null, name = if (renamed) name else null)
        }
        if (characterId in registered) {
            registry.relocateCharacter(characterId, save, checkpoint, actor, "character restore")
            if (registered.getValue(characterId).name != name) registry.renameCharacter(characterId, name, actor, "character restore")
        } else if (registry.isRetired(characterId)) {
            registry.reinstateCharacter(characterId, save, checkpoint, owner.str("account_id"), name, actor, "character restored from a backup")
        } else {
            // A new registration requires the save and its checkpoint at the same revision / hashes.
            val now = store.read()
            if (now.revision != StateStore(checkpoint, driver).read().revision) {
                Files.delete(checkpoint)
                sqliteCopy(save, checkpoint, driver)
            }
            registry.registerCharacter(owner.str("account_id"), name, save, now.revision, now.sourceSha256, now.payloadSha256, checkpoint,
                actor, characterId)
        }
        if (bornNow) root.commitActive(generation.fileName.toString(), reason = "world born by a character restore")
        // The world stays authoritative for shared data: report what the character finds there.
        val member = world.member(characterId)!!
        val report = worldEntries(world, characterId, member.long("wire_account_id"))
        val backed = Json.loads(files.getValue("character/world-entry.json")).asObj
        val guildNote = if (report["guild"] != JNull) "kept" else if (io.github.okexodus.openknights.server.game.Py.truthy(backed["guild"])) "dropped: the guild no longer lists the character" else "none"
        return jobj("status" to "restored", "character_id" to characterId, "name" to name,
            "wire_account_id" to member.long("wire_account_id"), "wire_id_reallocated" to (wireFinal != wire),
            "guild_seat" to guildNote, "arena" to "re-entered by the Arena's own rules at the next list",
            "safety_copy" to safety?.fileName?.toString(), "world_born" to bornNow)
    }

    // --- delete / trash / reset -----------------------------------------------------------------------------------------

    /**
     * Delete a character: a restorable `.okbackup` goes to trash/ first, then the registry retires it, the world releases
     * its entries (name freed, guild by the guild rules, friends, rank row, mail, chat …), and its save folder moves to
     * trash/. Undo = [restoreCharacter] of the trash backup until the trash is emptied.
     */
    fun deleteCharacter(root: DataRoot, characterId: String, driver: SqlDriver, clock: DeviceClock?, actor: String = "local-admin",
                        reason: String = "Deleted by its owner", accountId: String? = null, registry: AccountRegistry? = null,
                        world: WorldDirectory? = null): JObj {
        val generation = root.active ?: throw BackupError("There is no world yet")
        val reg = registry ?: AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
        val wor = world ?: WorldDirectory(generation.resolve(DataRoot.WORLD), driver, strictPaths = true)
        val character = reg.getCharacter(characterId)
        if (accountId != null && character.accountId != accountId) throw IllegalArgumentException("Only your own created characters can be deleted")
        val member = wor.member(characterId)
        if (member == null || member.strOrNull("kind") != "fresh") throw IllegalArgumentException("Only an active created character can be deleted")
        val undo = root.trash.resolve("${PyTime.nowStamp()}-deleted-${characterId.takeLast(8)}$SUFFIX")
        backupCharacter(root, characterId, undo, driver, clock)
        val relativeUndo = root.root.relativize(undo).joinToString("/")
        reg.retireCharacter(characterId, character.accountId, relativeUndo, actor, reason)
        val left = wor.markDeleted(characterId, actor, relativeUndo, reason)
        val moved = root.moveToTrash(character.statePath.parent, "deleted-${characterId.takeLast(8)}")
        return jobj("character_id" to characterId, "name" to member["name"], "undo_backup" to relativeUndo,
            "save_folder" to root.root.relativize(moved).joinToString("/"), "departed" to (left["departed"] ?: JObj()))
    }

    fun listTrash(root: DataRoot): List<String> =
        if (Files.exists(root.trash)) Files.list(root.trash).use { s -> s.map { it.fileName.toString() }.toList() }.sorted() else emptyList()

    /** Permanently remove everything in trash/ (explicit confirmation word required). */
    fun emptyTrash(root: DataRoot, confirm: String?): List<String> {
        if (confirm != EMPTY_TRASH_WORD) throw BackupError("Emptying the trash deletes it for good; confirm with the word $EMPTY_TRASH_WORD")
        val removed = ArrayList<String>()
        for (path in Files.list(root.trash).use { s -> s.toList() }.sortedBy { it.fileName.toString() }) {
            if (Files.isDirectory(path)) deleteTree(path) else Files.delete(path)
            removed.add(path.fileName.toString())
        }
        return removed
    }

    /** Everything to trash/ (after a safety copy); the next start is a brand-new world at the next creation. */
    fun reset(root: DataRoot, confirm: String?, driver: SqlDriver, clock: DeviceClock?): JObj {
        if (confirm != RESET_WORD) throw BackupError("Reset moves every character and the whole world to the trash; confirm with $RESET_WORD")
        val safety = safetyCopy(root, "pre-reset", driver, clock)
        val previous = root.clearActive("reset")
        val moved = ArrayList<String>()
        if (previous != null) moved.add(root.moveToTrash(previous, "reset").fileName.toString())
        for (folder in dirs(root.worlds)) moved.add(root.moveToTrash(folder, "reset-unborn").fileName.toString())
        return jobj("safety_copy" to safety?.fileName?.toString(), "trash" to moved)
    }

    // --- automatic safety copies -----------------------------------------------------------------------------------------

    /**
     * At service start: a copy before an app update (the manifest's app version differs) and the rolling daily copy
     * at the first start of a device-local day (the last [DAILY_KEEP] kept; older ones go to trash/).
     */
    fun startupCopies(root: DataRoot, driver: SqlDriver, clock: DeviceClock, log: ServiceLog? = null): List<String> {
        val made = ArrayList<String>()
        val manifest = root.readManifest() ?: JObj()
        if (root.active == null) {
            if (manifest.strOrNull("app_version") != DataRoot.APP_VERSION) root.writeManifest(null, "app version recorded")
            return made
        }
        val active = (manifest["active"] as JStr).value
        if (manifest.strOrNull("app_version") != DataRoot.APP_VERSION) {
            val path = safetyCopy(root, "pre-update-${manifest.strOrNull("app_version") ?: "unknown"}", driver, clock)!!
            made.add(path.fileName.toString())
            root.writeManifest(active, "app updated to ${DataRoot.APP_VERSION}")
        }
        val today = clock.localDay(clock.now())
        val current = root.readManifest()!!
        if (current.strOrNull("daily_backup_day") != today) {
            val target = root.autoBackups.resolve("daily-$today$SUFFIX")
            if (!Files.exists(target)) {
                backupFull(root, target, driver, clock)
                made.add(target.fileName.toString())
            }
            root.writeManifest(active, "daily safety copy", mapOf("daily_backup_day" to JStr(today)))
            val dailies = Files.list(root.autoBackups).use { s -> s.filter { it.fileName.toString().startsWith("daily-") && it.fileName.toString().endsWith(SUFFIX) }.toList() }
                .sortedBy { it.fileName.toString() }
            for (old in dailies.dropLast(DAILY_KEEP)) made.add("trash:" + root.moveToTrash(old, "old-daily").fileName)
        }
        if (log != null && made.isNotEmpty()) log.log("safety_copies", "made" to made)
        return made
    }
}

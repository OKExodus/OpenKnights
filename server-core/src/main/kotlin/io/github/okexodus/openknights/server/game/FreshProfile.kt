package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Names
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.protocol.TypedValues
import io.github.okexodus.openknights.server.store.SqlConnection
import io.github.okexodus.openknights.server.store.StateStore

/**
 * Fresh characters (`fresh_profile.py`): the starting profile, its generator and its startup. A fresh character's
 * opcode 18 is the release template's record with exactly these substitutions: role 0 (wire id), role 2 (name),
 * role 21 (signature), role 26 (gender), the starter hero map (UID 1), the starter in the hero collection, login_mode
 * 10006 and, in release mode, the rolling Fate roulette window.
 */
object FreshProfile {
    const val TEMPLATE_PROFILE = "fresh_character_template_v1"
    const val PROFILE = "fresh_character_v1"
    const val EVIDENCE_CONTRACT = "docs/CHARACTER_CREATE_CONTRACT.md"
    const val ROLE_ACCOUNT_ID = 0
    const val ROLE_USER_NAME = 2
    const val ROLE_LEVEL = 3
    const val ROLE_REPUTATION = 12
    const val ROLE_SIGNATURE = 21
    const val ROLE_GENDER = 26
    val GENDERS = mapOf(0 to "male", 1 to "female")
    const val NO_TUTORIAL_LOGIN_MODE = 10006L
    const val STARTER_UID = 1L
    const val SIGNATURE_MAX_BYTES = 64
    const val WIRE_ID_FIRST = 90_000_001L
    const val WIRE_ID_MAX = 0x7FFFFFFFL

    /** An expected validation failure; the message is safe to show. */
    class CreationRejected(message: String) : IllegalArgumentException(message)

    fun canonicalJson(document: JObj): String = Json.canonical(document)
    fun checksum(document: JObj): String = sha256Hex(canonicalJson(document).toByteArray(Charsets.UTF_8))

    /** The template in its loaded shape: `{"document", "path", "sha256", "checksum"}`. */
    class Template(val document: JObj, val path: String, val sha256: String, val checksum: String)

    fun validateTemplate(document: JObj) {
        require(document.strOrNull("profile") == TEMPLATE_PROFILE) { "Unsupported fresh-character template profile" }
        val payload = document.str("template_player_init_hex").hexBytes()
        require(sha256Hex(payload) == document.obj("sources").obj("player_init").str("sha256")) {
            "Template player initialization does not match its recorded source hash"
        }
        val state = PlayerState.parse(payload)
        val mode = state.long("login_mode")
        require(state["complete"] == io.github.okexodus.openknights.exact.JBool(true) && PlayerState.encode(state).contentEquals(payload) && mode != 1L && mode != 2L) {
            "Template player initialization is not a complete lossless full state"
        }
        require(state.arr("heroes").size == 1 && state.obj("subsystems").obj("hero_collection").long("count") == 1L) {
            "Template must hold exactly the starter hero and its collection entry"
        }
        val offers = document.arr("offers")
        require(offers.size == 3 && offers.toSet().size == 3 && offers.map { it.toString() }.toSet() == document.obj("starters").keys) {
            "Template offers and starter definitions disagree"
        }
        for ((key, starter) in document.obj("starters")) {
            val values = starter.asObj.arr("hero_fields").associate { it.asObj.long("id") to (it.asObj.obj("value")["bits"] as? JInt)?.value?.toLong() }
            require(values[0] == STARTER_UID && values[1] == key.toLong()) { "Starter $key hero map must be UID $STARTER_UID of that template" }
            require(starter.asObj.arrOrNull("god_skills")?.isNotEmpty() == true) { "Starter $key has no god-skill initial list" }
        }
        val keys = document.obj("signatures").arr("keys")
        require(keys.isNotEmpty() && keys.all { it.toString() in document.obj("signatures").obj("texts").keys }) { "Signature pool is incomplete" }
        document.str("clock_payload_hex").hexBytes()
        document.str("jewelry_list_payload_hex").hexBytes()
        require(document.str("clock_payload_hex").hexBytes().size == 8) { "Template clock must be an eight-byte S14" }
    }

    private fun role(state: JObj, id: Int): JObj = TypedValues.field(state.arr("role_properties"), id)

    private fun setText(value: JObj, raw: ByteArray) {
        require(value.long("tag") == 0x61L) { "Expected a text property" }
        require(!raw.contains(0.toByte())) { "Text must not contain NUL" }
        value["raw_hex"] = JStr(raw.toHexString())
        value["text"] = JStr(String(raw, Charsets.UTF_8))
    }

    private fun setBits(value: JObj, bits: Long) {
        val width = when (TypedValues.WIDTH_FORMAT[value.long("tag").toInt()]) { 'B' -> 8; 'H' -> 16; 'I' -> 32; else -> 64 }
        require(bits >= 0 && (width == 64 || bits < (1L shl width))) { "Property value does not fit its captured width" }
        value["bits"] = JInt(bits)
    }

    fun normalizeName(name: String): String = try { Names.normalizeName(name) } catch (e: Names.CreationRejected) { throw CreationRejected(e.message ?: "") }
    fun nameKey(name: String): String = Names.nameKey(name)

    fun validateGender(gender: Long): Int {
        if (gender.toInt().toLong() != gender || gender.toInt() !in GENDERS) throw CreationRejected("Gender must be 0 (male) or 1 (female)")
        return gender.toInt()
    }

    fun validateStarter(templateDocument: JObj, starter: Long): Long {
        if (templateDocument.arr("offers").none { (it as JInt).value.toLong() == starter }) throw CreationRejected("Choose one of the three offered starter heroes")
        return starter
    }

    /** Uniform pick from the signature pool (policy): the index comes from `secrets.choice`. */
    fun pickSignature(templateDocument: JObj): Long {
        val keys = templateDocument.obj("signatures").arr("keys")
        return (keys[io.github.okexodus.openknights.server.Entropy.current.choice(keys.size)] as JInt).value.toLong()
    }

    fun signatureBytes(templateDocument: JObj, key: Long): ByteArray {
        val signatures = templateDocument.obj("signatures")
        val text = signatures.obj("texts").str(key.toString())
        return Names.truncateUtf8(text, signatures.longOrNull("max_bytes")?.toInt() ?: SIGNATURE_MAX_BYTES)
    }

    /** The template with the character's own fields substituted; the S18 payload. */
    fun buildPlayerInit(templateDocument: JObj, name: String, gender: Int, starter: Long, wireId: Long, signatureKey: Long,
                        loginMode: Long = NO_TUTORIAL_LOGIN_MODE, rouletteWindow: Pair<Long, Long>? = null): ByteArray {
        val payload = templateDocument.str("template_player_init_hex").hexBytes()
        val state = PlayerState.parse(payload)
        setBits(role(state, ROLE_ACCOUNT_ID), wireId)
        setText(role(state, ROLE_USER_NAME), name.toByteArray(Charsets.UTF_8))
        setText(role(state, ROLE_SIGNATURE), signatureBytes(templateDocument, signatureKey))
        setBits(role(state, ROLE_GENDER), gender.toLong())
        require(loginMode != 1L && loginMode != 2L && loginMode in 0..0xFFFFFFFFL) { "Invalid login mode" }
        state["login_mode"] = JInt(loginMode)
        state["heroes"] = JArr(mutableListOf(templateDocument.obj("starters").obj(starter.toString()).arr("hero_fields").deepCopy()))
        val entry = state.obj("subsystems").obj("hero_collection").arr("entries")[0].asObj
        require(entry.arr("wire_values").size == 1) { "Unexpected hero collection entry shape" }
        entry["wire_values"] = JArr(mutableListOf(JInt(starter)))
        val roulette = state.obj("subsystems").obj("game_activities")["roulette"] as? JObj
        if (rouletteWindow != null && roulette != null) {
            roulette.arr("wire_values")[1] = JInt(rouletteWindow.first)
            roulette.arr("wire_values")[2] = JInt(rouletteWindow.second)
        }
        val out = PlayerState.encode(state)
        val parsed = PlayerState.parse(out)
        require(parsed["complete"] == io.github.okexodus.openknights.exact.JBool(true) && PlayerState.encode(parsed).contentEquals(out)) {
            "Generated initialization does not round-trip"
        }
        return out
    }

    fun godSkillDocument(templateDocument: JObj, characterId: String, starter: Long, createdAtUtc: String, creationPayloadSha256: String): JObj {
        val skills = JArr(templateDocument.obj("starters").obj(starter.toString()).arr("god_skills").mapTo(ArrayList()) { JArr(it.asArr.toMutableList()) })
        return jobj("profile" to GodSkills.PROFILE, "character_id" to characterId,
            "heroes" to listOf(jobj("uid" to STARTER_UID, "skills" to skills, "provenance" to "fresh_starter_initial_group_991")),
            "source_path" to "fresh_character_template", "source_sha256" to templateDocument.obj("sources").obj("god_skills").str("sha256"),
            "source_player_sha256" to creationPayloadSha256, "imported_revision" to 1, "imported_at_utc" to createdAtUtc,
            "actor" to "character-create", "reason" to "Fresh character starter")
    }

    fun profileDocument(template: Template, characterId: String, accountId: String, name: String, gender: Int, starter: Long,
                        wireId: Long, signatureKey: Long, creationPayloadSha256: String, createdAtUtc: String, clockPayloadHex: String?): JObj {
        val document = template.document
        return jobj("profile" to PROFILE, "contract" to EVIDENCE_CONTRACT, "character_id" to characterId, "account_id" to accountId,
            "name" to name, "gender" to gender, "starter" to starter, "wire_account_id" to wireId, "signature_key" to signatureKey,
            "created_at_utc" to createdAtUtc, "creation_payload_sha256" to creationPayloadSha256,
            "template" to jobj("path" to template.path, "sha256" to template.sha256, "checksum" to template.checksum,
                "player_init_sha256" to document.obj("sources").obj("player_init").str("sha256")),
            "clock_payload_hex" to (clockPayloadHex ?: document.str("clock_payload_hex")),
            "jewelry_list_payload_hex" to document.str("jewelry_list_payload_hex"),
            "startup_inventory_payloads_hex" to JArr(document.arr("startup_inventory_payloads_hex").toMutableList()),
            "policies" to jobj(
                "login_mode" to jobj("value" to NO_TUTORIAL_LOGIN_MODE, "class" to "preservation_policy_no_tutorial"),
                "signature" to jobj("class" to "preservation_policy_rng", "pool" to "text 501-600", "max_bytes" to SIGNATURE_MAX_BYTES),
                "wire_account_id" to jobj("class" to "preservation_policy_local_range", "first" to WIRE_ID_FIRST),
                "name" to jobj("class" to "preservation_policy", "max_chars" to Names.NAME_MAX_CHARS, "extra" to Names.NAME_EXTRA),
                "clock" to jobj("class" to "historical_login_origin_template", "source" to "template creation S14"),
                "template_values" to listOf("alchemy", "game_activities", "xinggong", "roulette")))
    }

    fun writeCharacterProfile(db: SqlConnection, document: JObj): String {
        val value = checksum(document)
        db.execute("""CREATE TABLE character_profile (
        id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL,
        document_json TEXT NOT NULL, document_sha256 TEXT NOT NULL)""")
        db.execute("INSERT INTO character_profile VALUES(1,1,?,?)", canonicalJson(document), value)
        return value
    }

    /** S14, S18, S64 × the profile's empty frames + imported and acquired batches, S2848, S2976. */
    fun freshStartup(current: StateStore.Current, characterId: String, clockPayload: ByteArray?): List<Frame> {
        val profile = current.characterProfile!!.obj("document")
        require(profile.str("character_id") == characterId) { "Character profile does not belong to the selected character" }
        require(current.sourceSha256 == profile.str("creation_payload_sha256")) { "Fresh character save no longer descends from its creation payload" }
        val clock = clockPayload ?: profile.str("clock_payload_hex").hexBytes()
        require(clock.size == 8) { "Invalid fresh-character clock" }
        val packets = ArrayList<Frame>()
        packets.add(14 to clock)
        packets.add(18 to current.payload)
        profile.arr("startup_inventory_payloads_hex").forEach { packets.add(64 to (it as JStr).value.hexBytes()) }
        current.inventoryPayloads.forEach { packets.add(64 to it) }
        current.acquiredPayloads.forEach { packets.add(64 to it) }
        val god = current.godSkills
        if (god != null) {
            require(god.obj("document").strOrNull("character_id") == characterId) { "God-skill state does not belong to the selected character" }
            packets.add(GodSkills.INIT_OPCODE to GodSkills.startupPayload(god.obj("document"), SecondaryTeam.ownedHeroes(current.state).keys))
        }
        packets.add(2976 to ByteArray(0))
        return packets
    }

    fun wireAccountId(current: StateStore.Current, default: Long?): Long? =
        current.characterProfile?.obj("document")?.long("wire_account_id") ?: default

    /** The unequipped-jewelry list frame (S3072): the stored list once seeded, else the profile's payload. */
    fun jewelryList(current: StateStore.Current): List<Frame> {
        val stored = current.jewelryList
        if (stored != null) return listOf(EquipFormation.S_JEWEL_LIST to EquipFormation.jewelryListPayloadOf(stored.obj("document")))
        val profile = current.characterProfile ?: return emptyList()
        return listOf(3072 to profile.obj("document").str("jewelry_list_payload_hex").hexBytes())
    }

    /**
     * The deployment preflight of a fresh character (`FreshDeploymentPolicy`): the save descends from its creation
     * payload and every owned hero is the starter, a labeled test-fixture injection or an acquisition's hero.
     */
    class DeploymentPolicy(private val profile: JObj) {
        val sourcePath: String = "character_profile:${profile.obj("document").str("character_id")}"
        val sha256: String = profile.str("document_sha256")
        val document: JObj get() = profile.obj("document")

        fun validateCurrent(current: StateStore.Current, characterId: String) {
            val p = current.characterProfile
            if (p == null || p.str("document_sha256") != sha256 || p.obj("document").str("character_id") != characterId ||
                current.sourceSha256 != p.obj("document").str("creation_payload_sha256")) {
                throw SecondaryTeam.Rejected("No fresh-character profile for this owned save")
            }
            val heroes = SecondaryTeam.ownedHeroes(current.state)
            val allowed = setOf(STARTER_UID) + current.fixtureInjectedHeroUids + current.acquiredHeroUids
            if (!allowed.containsAll(heroes.keys)) throw SecondaryTeam.Rejected("Deployment evidence requires the fresh character's evidenced hero set")
        }
    }
}

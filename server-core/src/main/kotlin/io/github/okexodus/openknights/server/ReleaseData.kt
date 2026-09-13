package io.github.okexodus.openknights.server

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.sha256Hex
import java.nio.file.Files
import java.nio.file.Path

/** The release data is missing, damaged or of an unsupported version (the message is safe to show). */
class ReleaseDataError(message: String) : IllegalArgumentException(message)

/**
 * The shipped release data (`release_data.py`): every file listed in `MANIFEST.json` must match its SHA-256 and carry
 * `schema_version` 1 before anything is used. Documents are handed out as fresh copies.
 */
class ReleaseData(directory: Path) {
    val directory: Path = directory.toAbsolutePath().normalize()
    val manifest: JObj
    private val documents = LinkedHashMap<String, JObj>()

    companion object {
        const val SCHEMA_VERSION = 1
        const val MANIFEST_PROFILE = "openknights_release_data_v1"
    }

    init {
        val manifestPath = this.directory.resolve("MANIFEST.json")
        if (!Files.isRegularFile(manifestPath)) throw ReleaseDataError("release-data/MANIFEST.json is missing")
        manifest = Json.loads(Files.readAllBytes(manifestPath)).asObj
        if (manifest.strOrNull("profile") != MANIFEST_PROFILE || (manifest["schema_version"] as? JInt)?.value?.toInt() != SCHEMA_VERSION) {
            throw ReleaseDataError("Unsupported release data version")
        }
        for ((name, info) in manifest.obj("files")) {
            val file = this.directory.resolve(name)
            if (!Files.isRegularFile(file)) throw ReleaseDataError("release-data/$name is missing")
            val raw = Files.readAllBytes(file)
            if (sha256Hex(raw) != info.asObj.str("sha256")) throw ReleaseDataError("release-data/$name is damaged (SHA-256 differs from its manifest)")
            val document = Json.loads(raw).asObj
            if ((document["schema_version"] as? JInt)?.value?.toInt() != SCHEMA_VERSION) throw ReleaseDataError("release-data/$name has an unsupported schema version")
            documents[name] = document
        }
    }

    fun document(name: String): JObj = Json.loads(Json.compact(documents[name] ?: throw ReleaseDataError("release-data/$name is not listed"))).asObj

    fun label(name: String) = "release-data/$name"

    fun sha256(name: String): String = manifest.obj("files").obj(name).str("sha256")

    /**
     * A labeled local policy in its loaded shape (`policy`): `{document, path, sha256, all_characters, characters}`, bound
     * to every character (`characters` is empty).
     */
    fun policy(name: String): JObj {
        val file = "policies/$name.json"
        return io.github.okexodus.openknights.exact.jobj("document" to document(file), "path" to label(file), "sha256" to sha256(file),
            "all_characters" to true, "characters" to io.github.okexodus.openknights.exact.JArr())
    }

    fun startupDefaults(): JObj = document("startup-defaults.json")

    /** The three starter templates the creation screens offer (fresh profile `offers`). */
    fun offers(): List<Long> = document("fresh-profile.json").arr("offers").map { (it as JInt).value.toLong() }

    /** The player's APK tables, bound by the service (text references resolve from `text.csv`). */
    var tables: io.github.okexodus.openknights.gamedata.GameTables? = null
    private var textCache: Map<Long, String>? = null

    /** {text id: column 102} of the APK's `text.csv` (rows whose first cell is all digits). */
    fun texts(): Map<Long, String> {
        textCache?.let { return it }
        val table = (tables ?: throw ReleaseDataError("Text references need the game APK tables")).table("text")
        val out = HashMap<Long, String>()
        for (row in table.rows) {
            val id = row.cells.firstOrNull() ?: continue
            if (id.isEmpty() || !id.codePoints().allMatch { io.github.okexodus.openknights.exact.PyText.isDigit(it) }) continue
            out[id.toLong()] = row.field("102") ?: ""
        }
        textCache = out
        return out
    }

    fun resolveText(value: io.github.okexodus.openknights.exact.JValue): io.github.okexodus.openknights.exact.JValue {
        if (value is JObj && value.containsKey("text")) {
            val suffix = value.strOrNull("suffix") ?: ""
            return io.github.okexodus.openknights.exact.JStr(texts().getValue((value["text"] as JInt).value.toLong()) + suffix)
        }
        return value
    }

    /** The fresh-character template (`fresh_template`), in the shape the character creation uses. */
    fun freshTemplate(): io.github.okexodus.openknights.server.game.FreshProfile.Template {
        val profile = document("fresh-profile.json")
        val defaults = startupDefaults()
        val signature = profile.obj("signature")
        val texts = texts()
        val payload = profile.str("player_init_template_hex").hexBytes()
        val godLists = JObj()
        for ((k, v) in profile.obj("starters")) godLists[k] = (v as JObj)["god_skills"]!!
        val starters = JObj()
        for ((k, v) in profile.obj("starters")) starters[k] = io.github.okexodus.openknights.exact.jobj("hero_fields" to (v as JObj)["hero_fields"], "god_skills" to v["god_skills"])
        val signatureTexts = JObj()
        for (k in signature.arr("keys")) signatureTexts[k.toString()] = io.github.okexodus.openknights.exact.JStr(texts.getValue((k as JInt).value.toLong()))
        val document = io.github.okexodus.openknights.exact.jobj(
            "profile" to io.github.okexodus.openknights.server.game.FreshProfile.TEMPLATE_PROFILE, "class" to "openknights_release_template",
            "sources" to io.github.okexodus.openknights.exact.jobj("player_init" to io.github.okexodus.openknights.exact.jobj("sha256" to sha256Hex(payload)),
                "god_skills" to io.github.okexodus.openknights.exact.jobj("sha256" to sha256Hex(Json.canonical(godLists).toByteArray()))),
            "template_player_init_hex" to profile.str("player_init_template_hex"),
            "clock_payload_hex" to "0000000000000000",
            "jewelry_list_payload_hex" to defaults.str("jewelry_list_payload_hex"),
            "startup_inventory_payloads_hex" to io.github.okexodus.openknights.exact.JArr(defaults.arr("startup_inventory_payloads_hex").toMutableList()),
            "offers" to io.github.okexodus.openknights.exact.JArr(profile.arr("offers").toMutableList()),
            "starters" to starters,
            "signatures" to io.github.okexodus.openknights.exact.jobj("keys" to io.github.okexodus.openknights.exact.JArr(signature.arr("keys").toMutableList()),
                "max_bytes" to signature["max_bytes"], "texts" to signatureTexts),
            "roulette_window" to (profile["roulette_window"] ?: io.github.okexodus.openknights.exact.JNull))
        io.github.okexodus.openknights.server.game.FreshProfile.validateTemplate(document)
        return io.github.okexodus.openknights.server.game.FreshProfile.Template(document, label("fresh-profile.json"), sha256("fresh-profile.json"),
            sha256Hex(Json.canonical(document).toByteArray()))
    }

    fun gate(): JObj {
        val gate = document("release-gate.json")
        if (gate.strOrNull("profile") != "openknights_release_gate_v1" || gate.strOrNull("routes") != "all") throw ReleaseDataError("Unsupported release gate")
        return gate
    }
}

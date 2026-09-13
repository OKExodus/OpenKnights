package io.github.okexodus.openknights.server

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
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

    fun startupDefaults(): JObj = document("startup-defaults.json")

    /** The three starter templates the creation screens offer (fresh profile `offers`). */
    fun offers(): List<Long> = document("fresh-profile.json").arr("offers").map { (it as JInt).value.toLong() }

    fun gate(): JObj {
        val gate = document("release-gate.json")
        if (gate.strOrNull("profile") != "openknights_release_gate_v1" || gate.strOrNull("routes") != "all") throw ReleaseDataError("Unsupported release gate")
        return gate
    }
}

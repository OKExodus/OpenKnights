package io.github.okexodus.openknights.gamedata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The game build the patcher supports, described by content hashes only (no game data). A copy is supported when
 * its package and version match and every listed DEX file, the native library and every game table hash the same.
 */
data class SupportedInput(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    /** DEX file name (classes.dex, classes2.dex, ...) to SHA-256. */
    val dex: Map<String, String>,
    val nativeLibraryPath: String,
    val nativeLibrarySha256: String,
    val tablePrefix: String,
    val tableKey: ByteArray,
    /** Table file name to the SHA-256 of its decrypted bytes. */
    val tables: Map<String, String>,
) {
    companion object {
        const val PROFILE = "openknights_supported_input_v1"
        private const val RESOURCE = "/io/github/okexodus/openknights/gamedata/supported-input.json"

        /** The definition shipped with this patcher. */
        val bundled: SupportedInput by lazy {
            val text = SupportedInput::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
                ?: error("supported-input.json is missing")
            parse(text)
        }

        fun parse(text: String): SupportedInput {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.string("profile") == PROFILE && root["schema_version"]?.jsonPrimitive?.int == 1) {
                "unknown supported-input profile"
            }
            val native = root.obj("native_library")
            val tables = root.obj("tables")
            require(tables.string("cipher") == "rc4" && tables.string("sha256_of") == "decrypted") { "unknown table cipher" }
            return SupportedInput(
                label = root.string("label"),
                packageName = root.string("package"),
                versionName = root.string("version_name"),
                versionCode = root["version_code"]!!.jsonPrimitive.int,
                dex = root.obj("dex").mapValues { it.value.jsonPrimitive.content },
                nativeLibraryPath = native.string("path"),
                nativeLibrarySha256 = native.string("sha256"),
                tablePrefix = tables.string("prefix"),
                tableKey = tables.string("key").toByteArray(Charsets.US_ASCII),
                tables = tables.obj("files").mapValues { it.value.jsonPrimitive.content },
            )
        }

        private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content ?: error("supported-input.json has no $key")
        private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: error("supported-input.json has no $key")
    }
}

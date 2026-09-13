package io.github.okexodus.openknights.patcher.patch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Reads the patch data shipped inside the patcher (the repository's `patches/` folder). */
object PatchData {
    private const val ROOT = "/io/github/okexodus/openknights/patches/"

    fun bytes(path: String): ByteArray =
        PatchData::class.java.getResourceAsStream(ROOT + path)?.use { it.readBytes() }
            ?: error("patch data $path is missing from the patcher")

    fun text(path: String): String = bytes(path).decodeToString()

    fun json(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    fun exists(path: String): Boolean = PatchData::class.java.getResource(ROOT + path) != null
}

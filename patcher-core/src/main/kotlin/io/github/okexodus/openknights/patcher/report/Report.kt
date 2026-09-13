package io.github.okexodus.openknights.patcher.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds the JSON patch report: plain values, lists and maps become JSON in the order they were added. */
class Report {
    private val fields = LinkedHashMap<String, JsonElement>()

    operator fun set(key: String, value: Any?) {
        fields[key] = toJson(value)
    }

    fun step(name: String, details: Map<String, Any?>) {
        val steps = (fields["steps"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        steps += toJson(linkedMapOf<String, Any?>("step" to name) + details)
        fields["steps"] = JsonArray(steps)
    }

    fun toJson(): JsonObject = JsonObject(fields)

    fun encode(): String = PRETTY.encodeToString(JsonObject.serializer(), toJson()) + "\n"

    companion object {
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name.lowercase())
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
            is Iterable<*> -> JsonArray(value.map { toJson(it) })
            is Array<*> -> JsonArray(value.map { toJson(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}

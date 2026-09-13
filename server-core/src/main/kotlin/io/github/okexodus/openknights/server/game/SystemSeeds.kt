package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.server.ReleaseData
import io.github.okexodus.openknights.server.ReleaseDataError
import io.github.okexodus.openknights.server.store.StateStore

/**
 * Where a character's daily / social systems start from (`system_seeds.py`): each system keeps a per-character
 * document that is seeded once from a frame of the same kind, with its provenance recorded. Fresh characters seed from
 * the release data's day-zero frames (`fresh-systems.json`); later the document is the only authority.
 */
object SystemSeeds {
    /** `{opcode: [payload, ...]}` with a provenance label. */
    class SeedFrames(val frames: Map<Int, List<ByteArray>>, val source: String) {
        fun first(opcode: Int): ByteArray? = frames[opcode]?.firstOrNull()

        fun all(opcode: Int): List<ByteArray> = frames[opcode]?.toList() ?: emptyList()

        fun provenance(opcode: Int, index: Int = 0): JObj {
            val payload = all(opcode)[index]
            return jobj("source" to source, "opcode" to opcode, "index" to index, "sha256" to sha256Hex(payload))
        }
    }

    /**
     * The seed frames of the selected character, or null (`seeds_for`): a fresh character (with its character
     * profile) seeds from the fresh-systems frames; release mode has no startup snapshot for any other save.
     */
    fun seedsFor(freshSystems: Map<Int, List<ByteArray>>?, current: StateStore.Current): SeedFrames? {
        if (current.characterProfile != null) return freshSystems?.let { SeedFrames(it, "fresh_systems_template") }
        return null
    }

    /** `ReleaseData.fresh_systems()`: {opcode: [payload, ...]} of `fresh-systems.json`, every frame hash-checked. */
    fun freshSystems(data: ReleaseData): Map<Int, List<ByteArray>> {
        val out = LinkedHashMap<Int, MutableList<ByteArray>>()
        for (frame in data.document("fresh-systems.json").arr("frames")) {
            val f = frame.asObj
            val payload = f.str("payload_hex").hexBytes()
            if (sha256Hex(payload) != f.str("sha256")) throw ReleaseDataError("release-data/fresh-systems.json frame hash mismatch")
            out.getOrPut((f["opcode"] as JInt).value.toInt()) { ArrayList() }.add(payload)
        }
        return out
    }
}

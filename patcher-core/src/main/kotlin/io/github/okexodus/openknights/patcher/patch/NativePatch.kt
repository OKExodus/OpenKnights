package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.util.hexToBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Byte patches to a native library, kept as data: for each site the offset, the bytes expected there and the bytes
 * that replace them. The library's hash is checked before, every site is checked, and the result's hash after.
 */
class NativePatchSet(
    val file: String,
    val sourceSha256: String,
    val resultSha256: String?,
    val sites: List<Site>,
) {
    class Site(val id: String, val offset: Int, val before: ByteArray, val after: ByteArray, val beforeLabel: String, val afterLabel: String)

    data class Applied(val id: String, val offset: String, val before: String, val after: String)

    init {
        val sorted = sites.sortedBy { it.offset }
        for ((a, b) in sorted.zipWithNext()) require(a.offset + a.before.size <= b.offset) { "patch sites ${a.id} and ${b.id} overlap" }
        for (s in sites) require(s.after.size == s.before.size) { "site ${s.id}: replacement size differs" }
    }

    fun apply(library: ByteArray): Pair<ByteArray, List<Applied>> {
        val sourceHash = Hashing.sha256(library)
        if (sourceHash != sourceSha256) {
            throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "The program library is not the supported build (SHA-256 $sourceHash). Nothing was patched.")
        }
        val result = library.copyOf()
        val applied = ArrayList<Applied>()
        for (site in sites) {
            if (site.offset < 0 || site.offset + site.before.size > library.size) {
                throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "Patch site ${site.id} is outside the program library. Nothing was patched.")
            }
            val found = library.copyOfRange(site.offset, site.offset + site.before.size)
            if (!found.contentEquals(site.before)) {
                throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "Patch site ${site.id} (offset 0x%x) does not hold the expected bytes. Nothing was patched.".format(site.offset))
            }
            site.after.copyInto(result, site.offset)
            applied += Applied(site.id, "0x%x".format(site.offset), site.beforeLabel, site.afterLabel)
        }
        val resultHash = Hashing.sha256(result)
        if (resultSha256 != null && resultHash != resultSha256) {
            throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "The patched program library has an unexpected SHA-256 ($resultHash). Nothing was written.")
        }
        return result to applied
    }

    companion object {
        val bundled: NativePatchSet by lazy { parse(PatchData.json("native/libhelloworld.json")) }

        fun parse(root: JsonObject): NativePatchSet {
            val sites = root["patches"]!!.jsonArray.map { element ->
                val site = element.jsonObject
                val id = site["id"]!!.jsonPrimitive.content
                val offset = site["offset"]!!.jsonPrimitive.content.let { require(it.startsWith("0x")) { "$id: offset must be hex" }; it.substring(2).toInt(16) }
                val (before, beforeLabel) = bytesOf(site["before"]!!.jsonObject, null, id)
                val (after, afterLabel) = bytesOf(site["after"]!!.jsonObject, before.size, id)
                Site(id, offset, before, after, beforeLabel, afterLabel)
            }
            return NativePatchSet(
                file = root["file"]!!.jsonPrimitive.content,
                sourceSha256 = root["source_sha256"]!!.jsonPrimitive.content,
                resultSha256 = root["result_sha256"]?.jsonPrimitive?.content,
                sites = sites,
            )
        }

        /** Text sites include the text's end byte; a replacement text is padded with zero bytes to the original size. */
        private fun bytesOf(value: JsonObject, padTo: Int?, id: String): Pair<ByteArray, String> {
            value["hex"]?.let { hex ->
                val bytes = hex.jsonPrimitive.content.hexToBytes()
                require(padTo == null || bytes.size == padTo) { "$id: hex replacement must have the original size" }
                return bytes to hex.jsonPrimitive.content
            }
            val text = value["text"]?.jsonPrimitive?.content ?: error("$id: a site needs text or hex")
            val raw = text.toByteArray(Charsets.US_ASCII) + 0
            if (padTo == null) return raw to text
            require(raw.size <= padTo) { "$id: replacement text is longer than the original" }
            return raw.copyOf(padTo) to text
        }
    }
}

package io.github.okexodus.openknights.protocol

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.sha256Hex
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only: every frame of a maintainer's private capture corpus, described by an index file that
 * OPENKNIGHTS_CORPUS points at (made by a private tool from the reference implementation). Skipped everywhere else,
 * including CI. For each frame: its bytes, its wire size re-encoded, and — for the kinds this module decodes — the
 * decoded tree (compared by the SHA-256 of its stored form) or the same refusal as the reference.
 */
class PrivateCorpusTest {
    private val index: Path? = System.getenv("OPENKNIGHTS_CORPUS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?.takeIf { Files.isRegularFile(it) }

    private fun decode(kind: String, payload: ByteArray): JValue = when (kind) {
        "player_state" -> PlayerState.parse(payload).also { require(PlayerState.encode(it).contentEquals(payload)) { "re-encoding differs" } }
        "battle_report" -> BattleReport.parse(payload).also { require(BattleReport.encode(it).contentEquals(payload)) { "re-encoding differs" } }
        "inventory" -> Inventory.decode(payload).also { require(Inventory.encode(it).contentEquals(payload)) { "re-encoding differs" } }
        "activity_update" -> PlayerSections.readActivityUpdate(payload).also {
            require(PlayerSections.encodeActivityUpdate(it.long("activity_id"), it.obj("rows")).contentEquals(payload)) { "re-encoding differs" }
        }
        else -> BattleReport.parseCampaignPacket(kind.substringAfter('_').toInt(), payload)
    }

    @Test
    fun `every frame of the private corpus matches the reference`() {
        assumeTrue(index != null, "OPENKNIGHTS_CORPUS is not set: local-only test skipped")
        val doc = Json.loads(Files.readAllBytes(index!!)).asObj
        val root = Path.of(doc.str("root"))
        val checks = doc.obj("checks")
        val verified = HashMap<String, String>()
        var frames = 0
        var wireChecked = 0
        var streamsRedecoded = 0
        val failures = ArrayList<String>()
        var anchor = 0
        for (s in doc.arr("streams")) {
            val stream = s.asObj
            val folder = root.resolve(stream.str("folder"))
            var wire = ByteArray(0)
            val expected = ArrayList<Pair<Int, String>>()
            for (f in stream.arr("frames")) {
                val row = f.asArr
                val payload = Files.readAllBytes(folder.resolve(row[0].asStr))
                val opcode = row[1].asInt.toInt()
                val digest = sha256Hex(payload)
                frames++
                if (digest != row[5].asStr) { failures.add("${stream.str("folder")}/${row[0].asStr}: bytes changed"); continue }
                val encoded = Frames.encode(opcode, payload)
                val wireBytes = row[4].asInt.toInt()
                if (wireBytes > 0) {
                    wireChecked++
                    if (encoded.size != wireBytes) failures.add("${stream.str("folder")}/${row[0].asStr}: wire size ${encoded.size} != $wireBytes")
                }
                wire += encoded
                expected.add(opcode to digest)
                val check = checks[digest] as? JObj ?: continue
                if (digest in verified) continue
                val kind = check.str("kind")
                val result = try { "ok:" + sha256Hex(Json.compact(decode(kind, payload)).toByteArray()) } catch (e: IllegalArgumentException) { "error" }
                val want = if (check.str("result") == "ok") "ok:" + check.str("tree_sha256") else "error"
                verified[digest] = kind
                if (result != want) failures.add("${stream.str("folder")}/${row[0].asStr} ($kind, ${payload.size} bytes): $result != $want")
                else if (kind == "player_state" && payload.size == 47281) anchor++
            }
            // The whole stream, re-framed and fed through the decoder in odd-sized pieces, gives the same messages.
            val decoder = FrameDecoder()
            val out = ArrayList<Pair<Int, String>>()
            var position = 0
            var step = 1
            while (position < wire.size) {
                val size = minOf(step, wire.size - position)
                decoder.feed(wire.copyOfRange(position, position + size)).forEach { out.add(it.opcode to sha256Hex(it.payload)) }
                position += size
                step = (step * 7 + 3) % 70001 + 1
            }
            decoder.finish()
            streamsRedecoded++
            if (out != expected) failures.add("${stream.str("folder")}: re-framed stream decodes differently")
        }
        val byKind = verified.values.groupingBy { it }.eachCount().toSortedMap()
        println("private corpus: $frames frames in $streamsRedecoded streams, $wireChecked wire sizes, " +
            "${verified.size} distinct decoded payloads $byKind, 47,281-byte S18 matches: $anchor, failures: ${failures.size}")
        assertTrue(failures.isEmpty()) { failures.take(20).joinToString("\n") }
    }
}

package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.ApkTables
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.SummonPoolPolicy
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

/** Private reference vectors prove release policy draws and grants without changing baseline parity vectors. */
class SupremeSummonPolicyTest {
    @Test
    fun `expanded pool matches reference tables and complete summon packets`() {
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")?.let { Path.of(it).resolve("supreme-pool") }
        val originals = System.getenv("OPENKNIGHTS_ORIGINALS")?.let { Path.of(it) }
        assumeTrue(dev != null && originals != null && Files.isRegularFile(dev.resolve("vectors.json")),
            "Private Supreme policy vectors are unavailable")
        val policy = SummonPoolPolicy.parse(Files.readAllBytes(dev!!.resolve("supreme-summon-pool.json")))
        val source = policy.transform(ApkTables(originals!!.resolve("com.enjoygame.hero2d.apk")))
        for (name in listOf("xinniudan.csv", "niudanhero.csv")) {
            assertArrayEquals(Files.readAllBytes(dev.resolve(name)), source.raw(name), name)
        }
        val inputs = AcquisitionInputs(GameTables(source))
        val fixture = Json.loads(Files.readAllBytes(dev.resolve("vectors.json"))).asObj
        val save = fixture.obj("save")
        for (raw in fixture.arr("vectors")) {
            val vector = raw.asObj
            val docs = LinkedHashMap<String, JValue?>()
            for ((key, value) in save.obj("documents")) docs[key] = value.deepCopy()
            val current = StateStore.Current(save.long("revision"), "", "", save.obj("state").deepCopy(),
                ByteArray(0), 0, null, save.arr("inventory_items").deepCopy(), emptyList(),
                save.arr("acquired_items").deepCopy(), emptyList(), null, null,
                (save["god_skills"] as? JObj)?.deepCopy(), (save["character_profile"] as? JObj)?.deepCopy(),
                (save["jewelry_list"] as? JObj)?.deepCopy(), docs)
            val owned = Owned(current, inputs)
            val seed = vector.long("seed")
            val plan = Summon.planSummon(vector.obj("request"), owned, inputs, vector.obj("document"),
                1800000000L, BigInteger.valueOf(seed))
            assertEquals(vector.obj("plan"), plan.data, "seed $seed plan")
            val expected = vector.arr("packets").map { p -> "${p.asArr[0]}:${(p.asArr[1] as JStr).value}" }
            val actual = plan.packets.map { (opcode, payload) -> "$opcode:${payload.toHexString()}" }
            assertEquals(expected, actual, "seed $seed packets")
        }
        println("Supreme release policy: ${fixture.arr("vectors").size} reference requests matched")
    }
}

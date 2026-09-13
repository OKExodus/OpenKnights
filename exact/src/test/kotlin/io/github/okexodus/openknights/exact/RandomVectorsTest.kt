package io.github.okexodus.openknights.exact

import org.junit.jupiter.api.Test
import java.math.BigInteger

class RandomVectorsTest {
    private fun run(rng: PyRandom, op: JArr): Any = when (op[0].asStr) {
        "random" -> "%016x".format(java.lang.Double.doubleToRawLongBits(rng.random()))
        "getrandbits" -> rng.getrandbits(op[1].asInt.toInt()).toString()
        "randrange1" -> rng.randbelow(op[1].asInt).toString()
        "randrange3" -> rng.randrange(op[1].asLong, op[2].asLong, op[3].asLong).toString()
        "randint" -> rng.randint(op[1].asLong, op[2].asLong).toString()
        "choice" -> rng.choice((0 until op[1].asInt.toInt()).toList())
        "shuffle" -> (0 until op[1].asInt.toInt()).toMutableList().also { rng.shuffle(it) }
        "sample" -> rng.sample((0 until op[1].asInt.toInt()).toList(), op[2].asInt.toInt())
        "choices_weights" -> {
            val weights = op[1].asArr.map { it.asLong }
            rng.choices(weights.indices.toList(), weights, op[2].asInt.toInt())
        }
        "choices" -> rng.choices((0 until op[1].asInt.toInt()).toList(), op[2].asInt.toInt())
        "uniform" -> "%016x".format(java.lang.Double.doubleToRawLongBits(rng.uniform((op[1] as JFloat).value, (op[2] as JFloat).value)))
        else -> error("unknown op ${op[0]}")
    }

    private fun expected(v: JValue): Any = when (v) {
        is JStr -> v.value
        is JInt -> v.value.toInt()
        is JArr -> v.map { it.asInt.toInt() }
        else -> error("unexpected output $v")
    }

    @Test
    fun `seeded Mersenne Twister and every method match the reference`() {
        val t = Tally("random.Random")
        for ((i, v) in Vectors.load("cpython-random").arr("vectors").withIndex()) {
            val case = v.asObj
            val rng = when {
                case.containsKey("seed_int") -> PyRandom.seeded(BigInteger(case.str("seed_int")))
                case.containsKey("seed_text") -> PyRandom.seeded(case.str("seed_text"))
                else -> PyRandom.seeded(case.str("seed_bytes_hex").hexBytes())
            }
            val outputs = case.arr("outputs")
            for ((k, op) in case.arr("script").withIndex()) {
                val actual = run(rng, op.asArr)
                val want = expected(outputs[k])
                t.check(want, if (actual is Int) actual else actual) { "case $i step $k ${op}" }
            }
        }
        t.assertAll()
    }

    @Test
    fun `SplitMix64 of the battle engine and of the campaign match the reference`() {
        val t = Tally("SplitMix64")
        for (v in Vectors.load("splitmix64").arr("vectors")) {
            val case = v.asObj
            val seed = BigInteger(case.str("seed"))
            val battle = SplitMix64(seed)
            for (s in case.arr("battle")) {
                val step = s.asArr
                when (step[0].asStr) {
                    "next_u64" -> t.check(step[1].asStr, battle.nextU64().toString()) { "next $seed" }
                    "below" -> {
                        val n = BigInteger(step[1].asStr)
                        val got = battle.below(n.toString().toULong()).toString()
                        t.check(step[2].asStr, got) { "below $n $seed" }
                    }
                    "chance" -> t.check((step[2] as JBool).value, battle.chance(step[1].asLong)) { "chance $seed" }
                }
            }
            t.check(case.long("battle_draws"), battle.draws) { "draws $seed" }
            val campaign = SplitMix64(seed)
            for (s in case.arr("campaign")) {
                val step = s.asArr
                if (step[0].asStr == "random") t.check(step[1].asStr, hexOf(campaign.random())) { "random $seed" }
                else t.check(step[3].asLong, campaign.randint(step[1].asLong, step[2].asLong)) { "randint $seed" }
            }
        }
        t.assertAll()
    }

    @Test
    fun `hash-derived seeds match the reference`() {
        val t = Tally("hash seeds")
        for (v in Vectors.load("hash-seeds").arr("vectors")) {
            val case = v.asObj
            if (case.str("kind") == "seed_for") {
                val seed = Seeds.seedFor(case.str("request_hex").hexBytes(), case.long("revision"), case.str("salt"))
                t.check(case.str("seed"), seed.toString()) { "seed_for" }
            } else {
                val parts = case.arr("parts").map { p -> if (p is JInt) p.value else p.asStr }.toTypedArray<Any>()
                val seed = Seeds.joined(*parts)
                t.check(case.str("seed"), seed.toString()) { "joined ${case.str("text")}" }
                t.check(case.long("first_randrange_10000"), PyRandom.seeded(seed).randrange(10000)) { "first draw" }
            }
        }
        t.assertAll()
    }
}

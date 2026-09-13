package io.github.okexodus.openknights.exact

import java.time.Instant
import java.time.ZoneId

/**
 * The clock every part of the server reads, as the reference reads `time.time()` / `datetime.now()`: epoch seconds as
 * a double, and the device's GMT offset at an epoch. The live server pins it to one value per request (every write of
 * one request carries the same time); the differential harness pins it to the recorded time of each step.
 */
object Now {
    @Volatile
    var source: () -> Double = { System.currentTimeMillis() / 1000.0 }

    @Volatile
    var offsetSource: (Long) -> Int = { epoch -> ZoneId.systemDefault().rules.getOffset(Instant.ofEpochSecond(epoch)).totalSeconds }

    fun seconds(): Double = source()

    /** `int(time.time())`. */
    fun epoch(): Long = seconds().toLong()

    fun offset(epoch: Long): Int = offsetSource(epoch)

    /** Run [block] with the clock pinned to [at] (restored afterwards). */
    fun <T> pinned(at: Double, block: () -> T): T {
        val previous = source
        source = { at }
        try {
            return block()
        } finally {
            source = previous
        }
    }
}

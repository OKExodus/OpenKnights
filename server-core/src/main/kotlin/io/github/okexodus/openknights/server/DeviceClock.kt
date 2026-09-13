package io.github.okexodus.openknights.server

import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.store.Publish
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The device clock (`device_clock.py`, release contract §8): all time follows the device, daily resets turn at the
 * device's local midnight, and a high-water mark keeps time from going backwards — `now()` = max(device epoch,
 * highest epoch seen) and the local day of the present never goes back either. The mark lives in `<root>/clock.json`
 * (outside every world), written when the day advances, otherwise at most every [PERSIST_SECONDS] and at shutdown.
 */
class DeviceClock(
    private val path: Path?,
    private val timeSource: () -> Long = { io.github.okexodus.openknights.exact.Now.epoch() },
    private val offsetSource: (Long) -> Int = { epoch -> io.github.okexodus.openknights.exact.Now.offset(epoch) },
) {
    companion object {
        const val PROFILE = "openknights_device_clock_v1"
        const val PERSIST_SECONDS = 60
        const val MAX_EPOCH = 0x7FFFFFFFL
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** The service's device clock (`device_clock.ACTIVE`, installed by the release service; read by `day_of` and friends). */
        @Volatile
        var active: DeviceClock? = null
    }

    var hwmEpoch = 0L
        private set
    var hwmDay = ""
        private set
    private var persistedAt = 0L

    init {
        if (path != null && Files.isRegularFile(path)) {
            val document = Json.loads(Files.readAllBytes(path)).asObj
            require(document.strOrNull("profile") == PROFILE) { "Unsupported device clock file" }
            hwmEpoch = (document.getValue("hwm_epoch") as JInt).value.toLong()
            hwmDay = (document.getValue("hwm_day") as JStr).value
        }
    }

    fun offset(epoch: Long): Int {
        val value = offsetSource(epoch)
        require(value > -86400 && value < 86400) { "GMT offset out of range" }
        return value
    }

    fun localDateTime(epoch: Long): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.ofTotalSeconds(offset(epoch)))

    fun plainDay(epoch: Long): String = DAY.format(localDateTime(epoch))

    /** The local day of `epoch`; for the present and the future never earlier than the highest day seen. */
    fun localDay(epoch: Long): String {
        val day = plainDay(epoch)
        return if (epoch >= hwmEpoch) maxOf(day, hwmDay) else day
    }

    /** The first epoch after `epoch` whose (effective) local day is later than the day of `epoch`. */
    fun nextDayStart(epoch: Long): Long {
        val today = localDay(epoch)
        val moment = localDateTime(epoch).withHour(0).withMinute(0).withSecond(0).withNano(0)
        for (days in 1L..3L) {
            val candidate = moment.plusDays(days).toEpochSecond()
            if (plainDay(candidate) > today) return candidate
        }
        return moment.plusDays(1).toEpochSecond()
    }

    @Synchronized
    fun now(): Long {
        val real = timeSource()
        require(real <= MAX_EPOCH) { "Device clock beyond the client's signed 32-bit time" }
        val effective = maxOf(real, hwmEpoch)
        val day = maxOf(plainDay(effective), hwmDay)
        val dayChanged = day != hwmDay
        hwmEpoch = effective
        hwmDay = day
        if (dayChanged || effective - persistedAt >= PERSIST_SECONDS) persist()
        return effective
    }

    /** The served clock frame S14: device epoch (effective) + the device GMT offset, `<Ii`. */
    fun s14(): ByteArray {
        val epoch = now()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(epoch.toInt()).putInt(offset(epoch)).array()
    }

    @Synchronized
    fun checkpoint() = persist()

    private fun persist() {
        persistedAt = hwmEpoch
        if (path == null) return
        Publish.writeJsonAtomic(path, jobj("profile" to PROFILE, "hwm_epoch" to hwmEpoch, "hwm_day" to hwmDay,
            "written_at_utc" to io.github.okexodus.openknights.exact.PyTime.nowIsoSeconds()))
    }
}

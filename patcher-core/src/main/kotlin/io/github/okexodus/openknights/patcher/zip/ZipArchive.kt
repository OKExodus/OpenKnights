package io.github.okexodus.openknights.patcher.zip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The file is not a readable zip archive (the message is safe to show to a user). */
class ZipFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One entry of a zip archive, as its central directory describes it. */
class ZipEntry internal constructor(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val dosTime: Int,
    val dosDate: Int,
) {
    val isEncrypted: Boolean get() = flags and 1 != 0
    val isDirectory: Boolean get() = name.endsWith("/")

    /** Offset of the entry's (compressed) data, filled in when the archive is opened. */
    var dataOffset: Long = -1
        internal set

    override fun toString(): String = "ZipEntry($name, method=$method, size=$size)"

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
    }
}

/**
 * A read-only zip archive over a [DataSource]. Entries keep their raw compressed bytes available, so an unchanged
 * entry can be copied into a new archive without recompressing it.
 */
class ZipArchive private constructor(val source: DataSource, val entries: List<ZipEntry>) : AutoCloseable {
    private val byName: Map<String, ZipEntry> = entries.associateBy { it.name }

    operator fun get(name: String): ZipEntry? = byName[name]

    fun contains(name: String): Boolean = name in byName

    /** The raw (possibly compressed) bytes of an entry. */
    fun rawSource(entry: ZipEntry): DataSource = source.slice(entry.dataOffset, entry.compressedSize)

    /** The uncompressed bytes of an entry; the CRC and size are checked. */
    fun read(entry: ZipEntry): ByteArray {
        require(entry.size <= Int.MAX_VALUE) { "${entry.name} is too large to hold in memory" }
        val output = ByteArrayOutputStream(entry.size.toInt())
        openStream(entry).use { it.copyTo(output, 1 shl 16) }
        return output.toByteArray()
    }

    fun read(name: String): ByteArray = read(get(name) ?: throw ZipFormatException("the archive has no entry $name"))

    /** A stream of the uncompressed bytes; reading it to the end checks the CRC and size. */
    fun openStream(entry: ZipEntry): InputStream {
        if (entry.isEncrypted) throw ZipFormatException("${entry.name} is encrypted")
        val raw = rawSource(entry).openStream()
        val content = when (entry.method) {
            ZipEntry.STORED -> {
                if (entry.compressedSize != entry.size) throw ZipFormatException("${entry.name}: stored sizes differ")
                raw
            }
            ZipEntry.DEFLATED -> InflatingStream(raw, entry.name)
            else -> throw ZipFormatException("${entry.name} uses unsupported compression method ${entry.method}")
        }
        return CheckedEntryStream(content, entry)
    }

    /** Reads every entry to the end, checking CRCs; returns the names of damaged entries. */
    fun verifyAll(): List<String> = entries.filter { !it.isDirectory }.mapNotNull { entry ->
        try {
            openStream(entry).use { stream ->
                val buffer = ByteArray(1 shl 16)
                do { val read = stream.read(buffer) } while (read >= 0)
            }
            null
        } catch (_: IOException) {
            entry.name
        }
    }

    override fun close() = source.close()

    companion object {
        private const val EOCD = 0x06054b50
        private const val ZIP64_EOCD = 0x06064b50
        private const val ZIP64_LOCATOR = 0x07064b50
        private const val CENTRAL = 0x02014b50
        private const val LOCAL = 0x04034b50

        /** Quick test: does the source start like a zip archive? */
        fun looksLikeZip(source: DataSource): Boolean {
            if (source.size < 22) return false
            val head = source.readBytes(0, 4)
            val signature = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
            return signature == LOCAL || signature == EOCD
        }

        fun open(source: DataSource): ZipArchive {
            val eocdPosition = findEndOfCentralDirectory(source)
            val eocd = source.buffer(eocdPosition, 22)
            val disk = eocd.short(4)
            val cdDisk = eocd.short(6)
            var count = eocd.short(10).toLong()
            var cdSize = eocd.int(12).toLong() and 0xFFFFFFFFL
            var cdOffset = eocd.int(16).toLong() and 0xFFFFFFFFL
            if (disk != 0 || cdDisk != 0) throw ZipFormatException("split (multi-part) zip archives are not supported")
            if (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val locatorPosition = eocdPosition - 20
                if (locatorPosition < 0 || source.buffer(locatorPosition, 4).int(0) != ZIP64_LOCATOR) {
                    throw ZipFormatException("the zip64 end record is missing")
                }
                val zip64Position = source.buffer(locatorPosition, 20).long(8)
                if (zip64Position < 0 || zip64Position + 56 > source.size) throw ZipFormatException("the zip64 end record is outside the file")
                val zip64 = source.buffer(zip64Position, 56)
                if (zip64.int(0) != ZIP64_EOCD) throw ZipFormatException("the zip64 end record is damaged")
                count = zip64.long(32)
                cdSize = zip64.long(40)
                cdOffset = zip64.long(48)
            }
            if (cdOffset < 0 || cdOffset + cdSize > source.size) throw ZipFormatException("the central directory is outside the file")
            if (cdSize > Int.MAX_VALUE) throw ZipFormatException("the central directory is too large")
            val cd = source.buffer(cdOffset, cdSize.toInt())
            val entries = ArrayList<ZipEntry>(minOf(count, 1_000_000L).toInt())
            val names = HashSet<String>()
            var at = 0
            for (index in 0 until count) {
                if (at + 46 > cdSize) throw ZipFormatException("the central directory is truncated")
                if (cd.int(at) != CENTRAL) throw ZipFormatException("the central directory is damaged at entry $index")
                val flags = cd.short(at + 8)
                val method = cd.short(at + 10)
                val time = cd.short(at + 12)
                val date = cd.short(at + 14)
                val crc = cd.int(at + 16).toLong() and 0xFFFFFFFFL
                var compressed = cd.int(at + 20).toLong() and 0xFFFFFFFFL
                var size = cd.int(at + 24).toLong() and 0xFFFFFFFFL
                val nameLength = cd.short(at + 28)
                val extraLength = cd.short(at + 30)
                val commentLength = cd.short(at + 32)
                var localOffset = cd.int(at + 42).toLong() and 0xFFFFFFFFL
                val end = at + 46 + nameLength + extraLength + commentLength
                if (end > cdSize) throw ZipFormatException("the central directory is truncated")
                val nameBytes = ByteArray(nameLength).also { cd.get(at + 46, it) }
                val name = String(nameBytes, if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1)
                if (compressed == 0xFFFFFFFFL || size == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                    var extraAt = at + 46 + nameLength
                    val extraEnd = extraAt + extraLength
                    while (extraAt + 4 <= extraEnd) {
                        val id = cd.short(extraAt)
                        val length = cd.short(extraAt + 2)
                        if (id == 0x0001) {
                            var field = extraAt + 4
                            if (size == 0xFFFFFFFFL) { size = cd.long(field); field += 8 }
                            if (compressed == 0xFFFFFFFFL) { compressed = cd.long(field); field += 8 }
                            if (localOffset == 0xFFFFFFFFL) { localOffset = cd.long(field) }
                            break
                        }
                        extraAt += 4 + length
                    }
                }
                if (!names.add(name)) throw ZipFormatException("the archive lists $name twice")
                entries += ZipEntry(name, method, flags, crc, compressed, size, localOffset, time, date)
                at = end
            }
            for (entry in entries) {
                if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + 30 > source.size) {
                    throw ZipFormatException("${entry.name}: its data is outside the file")
                }
                val local = source.buffer(entry.localHeaderOffset, 30)
                if (local.int(0) != LOCAL) throw ZipFormatException("${entry.name}: its local header is damaged")
                entry.dataOffset = entry.localHeaderOffset + 30 + local.short(26) + local.short(28)
                if (entry.dataOffset + entry.compressedSize > source.size) {
                    throw ZipFormatException("${entry.name}: its data is cut off (the file is incomplete)")
                }
            }
            return ZipArchive(source, entries)
        }

        private fun findEndOfCentralDirectory(source: DataSource): Long {
            if (source.size < 22) throw ZipFormatException("the file is too small to be a zip archive")
            val window = minOf(source.size, 22L + 0xFFFF).toInt()
            val start = source.size - window
            val tail = source.buffer(start, window)
            for (at in window - 22 downTo 0) {
                if (tail.int(at) == EOCD && at + 22 + tail.short(at + 20) == window) return start + at
            }
            for (at in window - 22 downTo 0) {
                if (tail.int(at) == EOCD) return start + at
            }
            throw ZipFormatException("the end of the zip directory was not found (the file is not a zip archive or is incomplete)")
        }

        private fun DataSource.buffer(position: Long, length: Int): ByteBuffer =
            ByteBuffer.wrap(readBytes(position, length)).order(ByteOrder.LITTLE_ENDIAN)

        private fun ByteBuffer.int(at: Int): Int = getInt(at)
        private fun ByteBuffer.short(at: Int): Int = getShort(at).toInt() and 0xFFFF
        private fun ByteBuffer.long(at: Int): Long = getLong(at)
    }
}

private class InflatingStream(private val raw: InputStream, private val name: String) : InputStream() {
    private val inflater = Inflater(true)
    private val input = ByteArray(1 shl 16)
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        try {
            while (true) {
                val count = inflater.inflate(b, off, len)
                if (count > 0) return count
                if (inflater.finished()) {
                    finished = true
                    return -1
                }
                if (inflater.needsDictionary()) throw ZipFormatException("$name: unsupported compressed data")
                if (inflater.needsInput()) {
                    val read = raw.read(input)
                    if (read < 0) throw ZipFormatException("$name: its compressed data is cut off")
                    inflater.setInput(input, 0, read)
                }
            }
        } catch (error: DataFormatException) {
            throw ZipFormatException("$name: its compressed data is damaged", error)
        }
    }

    override fun close() {
        inflater.end()
        raw.close()
    }
}

private class CheckedEntryStream(private val content: InputStream, private val entry: ZipEntry) : InputStream() {
    private val crc = CRC32()
    private var count = 0L
    private var checked = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = content.read(b, off, len)
        if (read > 0) {
            crc.update(b, off, read)
            count += read
            if (count > entry.size) throw ZipFormatException("${entry.name}: it holds more data than its size says")
        } else if (read < 0 && !checked) {
            checked = true
            if (count != entry.size) throw ZipFormatException("${entry.name}: its size does not match (damaged)")
            if (crc.value != entry.crc) throw ZipFormatException("${entry.name}: its checksum does not match (damaged)")
        }
        return read
    }

    override fun close() = content.close()
}

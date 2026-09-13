package io.github.okexodus.openknights.patcher.zip

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Writes a zip archive whose bytes depend only on what is added: fixed timestamps, no data descriptors, no comments,
 * and stored entries aligned with an alignment extra field (the same field apksig and zipalign understand).
 */
class ZipWriter(output: OutputStream) : Closeable {
    private val out = CountingStream(BufferedOutputStream(output, 1 shl 20))
    private val central = ArrayList<CentralRecord>()
    private val names = HashSet<String>()
    private var closed = false

    /** Adds [data] uncompressed, its data aligned to [alignment] bytes. */
    fun addStored(name: String, data: ByteArray, alignment: Int = 4) {
        val crc = CRC32().apply { update(data) }.value
        writeEntry(name, ZipEntry.STORED, crc, data.size.toLong(), data.size.toLong(), alignment) { out.write(data) }
    }

    /** Copies an entry of another archive without recompressing it. Stored entries are aligned to [alignment]. */
    fun copy(archive: ZipArchive, entry: ZipEntry, name: String = entry.name, alignment: Int = 4) {
        if (entry.isEncrypted) throw ZipFormatException("${entry.name} is encrypted")
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw ZipFormatException("${entry.name} uses unsupported compression method ${entry.method}")
        }
        val raw = archive.rawSource(entry)
        writeEntry(name, entry.method, entry.crc, entry.compressedSize, entry.size, if (entry.method == ZipEntry.STORED) alignment else 1) {
            raw.openStream().use { it.copyTo(out, 1 shl 16) }
        }
    }

    private fun writeEntry(name: String, method: Int, crc: Long, compressed: Long, size: Long, alignment: Int, body: () -> Unit) {
        check(!closed) { "the archive is already closed" }
        require(names.add(name)) { "duplicate entry $name" }
        require(alignment >= 1) { "alignment must be positive" }
        if (compressed > 0xFFFFFFFEL || size > 0xFFFFFFFEL || out.count > 0xFFFFFFFEL) {
            throw ZipFormatException("$name: entries of 4 GB and more are not supported in an APK")
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val utf8 = nameBytes.any { it < 0 }
        val flags = if (utf8) 0x800 else 0
        val headerOffset = out.count
        var extra = ByteArray(0)
        if (alignment > 1) {
            val dataWithoutExtra = headerOffset + 30 + nameBytes.size
            var padding = ((alignment - (dataWithoutExtra + ALIGNMENT_FIELD_MIN) % alignment) % alignment).toInt()
            if (dataWithoutExtra % alignment == 0L) padding = -1
            if (padding >= 0) {
                extra = ByteArray(ALIGNMENT_FIELD_MIN + padding)
                val buffer = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putShort(ALIGNMENT_FIELD_ID.toShort())
                buffer.putShort((2 + padding).toShort())
                buffer.putShort(alignment.toShort())
            }
        }
        val version = if (method == ZipEntry.STORED) 10 else 20
        val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x04034b50).putShort(version.toShort()).putShort(flags.toShort()).putShort(method.toShort())
            .putShort(DOS_TIME.toShort()).putShort(DOS_DATE.toShort()).putInt(crc.toInt())
            .putInt(compressed.toInt()).putInt(size.toInt())
            .putShort(nameBytes.size.toShort()).putShort(extra.size.toShort())
        out.write(header.array())
        out.write(nameBytes)
        out.write(extra)
        val dataStart = out.count
        body()
        if (out.count - dataStart != compressed) throw ZipFormatException("$name: wrote ${out.count - dataStart} bytes, expected $compressed")
        central += CentralRecord(nameBytes, flags, method, version, crc, compressed, size, headerOffset)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (central.size > 0xFFFE) throw ZipFormatException("too many entries for an APK (${central.size})")
        val cdStart = out.count
        for (record in central) {
            val buffer = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x02014b50).putShort(record.version.toShort()).putShort(record.version.toShort())
                .putShort(record.flags.toShort()).putShort(record.method.toShort())
                .putShort(DOS_TIME.toShort()).putShort(DOS_DATE.toShort()).putInt(record.crc.toInt())
                .putInt(record.compressed.toInt()).putInt(record.size.toInt())
                .putShort(record.name.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0).putInt(0)
                .putInt(record.offset.toInt())
            out.write(buffer.array())
            out.write(record.name)
        }
        val cdSize = out.count - cdStart
        if (cdStart > 0xFFFFFFFEL || cdSize > 0xFFFFFFFEL) throw ZipFormatException("the archive is too large for an APK")
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06054b50).putShort(0).putShort(0).putShort(central.size.toShort()).putShort(central.size.toShort())
            .putInt(cdSize.toInt()).putInt(cdStart.toInt()).putShort(0)
        out.write(eocd.array())
        out.close()
    }

    private class CentralRecord(
        val name: ByteArray, val flags: Int, val method: Int, val version: Int,
        val crc: Long, val compressed: Long, val size: Long, val offset: Long,
    )

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    companion object {
        /** 1981-01-01 01:01:02, the fixed timestamp Android's bundle tool writes (MS-DOS format). */
        const val DOS_DATE = ((1981 - 1980) shl 9) or (1 shl 5) or 1
        const val DOS_TIME = (1 shl 11) or (1 shl 5) or (2 / 2)

        /** The alignment extra field used by apksig: id, size, then the alignment and zero padding. */
        const val ALIGNMENT_FIELD_ID = 0xD935
        private const val ALIGNMENT_FIELD_MIN = 6
    }
}

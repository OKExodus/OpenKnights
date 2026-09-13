package io.github.okexodus.openknights.patcher.zip

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Read-only random access to bytes: a file, a slice of a file, or an array. */
interface DataSource : Closeable {
    val size: Long

    /** Reads exactly [length] bytes at [position] into [target] from [offset]. */
    fun read(position: Long, target: ByteArray, offset: Int = 0, length: Int = target.size - offset)

    fun readBytes(position: Long, length: Int): ByteArray = ByteArray(length).also { read(position, it) }

    fun slice(position: Long, length: Long): DataSource {
        require(position >= 0 && length >= 0 && position + length <= size) { "slice $position+$length is outside 0..$size" }
        return SliceSource(this, position, length)
    }

    fun openStream(position: Long = 0, length: Long = size - position): InputStream = SourceStream(this, position, length)

    override fun close() {}
}

class FileSource private constructor(private val channel: FileChannel) : DataSource {
    override val size: Long = channel.size()

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > size) throw EOFException("read $position+$length is outside 0..$size")
        val buffer = ByteBuffer.wrap(target, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            if (count < 0) throw EOFException("file ended at $at")
            at += count
        }
    }

    override fun close() = channel.close()

    companion object {
        fun open(path: Path): FileSource = FileSource(FileChannel.open(path, StandardOpenOption.READ))
    }
}

class ByteArraySource(private val bytes: ByteArray) : DataSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > bytes.size) throw EOFException("read $position+$length is outside 0..${bytes.size}")
        System.arraycopy(bytes, position.toInt(), target, offset, length)
    }
}

private class SliceSource(private val parent: DataSource, private val start: Long, override val size: Long) : DataSource {
    override fun read(position: Long, target: ByteArray, offset: Int, length: Int) {
        if (position < 0 || position + length > size) throw EOFException("read $position+$length is outside 0..$size")
        parent.read(start + position, target, offset, length)
    }
}

private class SourceStream(private val source: DataSource, private var position: Long, length: Long) : InputStream() {
    private val end = position + length

    override fun read(): Int {
        if (position >= end) return -1
        val one = ByteArray(1)
        source.read(position++, one)
        return one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= end) return -1
        val count = minOf(len.toLong(), end - position).toInt()
        source.read(position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), end - position).toInt()
}

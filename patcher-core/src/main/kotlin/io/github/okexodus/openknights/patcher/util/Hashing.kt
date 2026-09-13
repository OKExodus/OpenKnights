package io.github.okexodus.openknights.patcher.util

import io.github.okexodus.openknights.patcher.zip.DataSource
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Hashing {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun sha256(source: DataSource): String = sha256(source.openStream())

    fun sha256(path: Path): String = sha256(Files.newInputStream(path))
}

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for ((i, b) in withIndex()) {
        val v = b.toInt() and 0xFF
        chars[2 * i] = HEX[v ushr 4]
        chars[2 * i + 1] = HEX[v and 0xF]
    }
    return String(chars)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }
}

private val HEX = "0123456789abcdef".toCharArray()

package io.github.okexodus.openknights.patcher.input

/**
 * The game's table cipher: RC4 with the client's fixed key, restarted for every file. Because every file starts a
 * fresh cipher, one keystream long enough for the largest file decrypts them all.
 */
class AssetCipher(private val key: ByteArray) {
    private var stream = ByteArray(0)

    fun decrypt(data: ByteArray): ByteArray {
        if (stream.size < data.size) stream = keystream(maxOf(data.size, stream.size * 2))
        return ByteArray(data.size) { (data[it].toInt() xor stream[it].toInt()).toByte() }
    }

    /** Encrypting is the same operation; used by tests to build synthetic tables. */
    fun encrypt(data: ByteArray): ByteArray = decrypt(data)

    private fun keystream(length: Int): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(length)
        var i = 0
        j = 0
        for (n in 0 until length) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = s[(s[i] + s[j]) and 0xFF].toByte()
        }
        return out
    }
}

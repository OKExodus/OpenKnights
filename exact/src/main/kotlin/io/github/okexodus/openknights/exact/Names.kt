package io.github.okexodus.openknights.exact

/**
 * Character names (fresh_profile rules, a labeled local policy of the reference): NFC, 1 to 10 characters, each a
 * letter or digit by the reference's `isalnum` or one of `._-`, at most 30 UTF-8 bytes. The world-wide uniqueness key
 * is NFC + full case folding.
 */
object Names {
    const val NAME_MAX_CHARS = 10
    const val NAME_MAX_BYTES = 30
    const val NAME_EXTRA = "._-"

    /** A name the local rules refuse; the message is the reference's and is safe to show. */
    class CreationRejected(message: String) : IllegalArgumentException(message)

    fun normalizeName(name: String): String {
        val normalized = PyText.nfc(name)
        val length = PyText.length(normalized)
        if (length !in 1..NAME_MAX_CHARS) throw CreationRejected("Name must have 1 to $NAME_MAX_CHARS characters")
        if (!normalized.codePoints().allMatch { PyText.isAlnum(it) || (it < 128 && NAME_EXTRA.indexOf(it.toChar()) >= 0) }) {
            throw CreationRejected("Name may only use letters, digits, dot, underscore and hyphen")
        }
        if (normalized.toByteArray(Charsets.UTF_8).size > NAME_MAX_BYTES) throw CreationRejected("Name is too long")
        return normalized
    }

    fun nameKey(name: String): String = PyText.casefold(PyText.nfc(name))

    /** Cut UTF-8 text at a byte limit without splitting a character (signatures). */
    fun truncateUtf8(text: String, limit: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= limit) return raw
        var end = limit
        while (end > 0 && (raw[end - 1].toInt() and 0xC0) == 0x80) end--
        if (end > 0 && (raw[end - 1].toInt() and 0xFF) >= 0xC0) end--
        return raw.copyOf(end)
    }
}

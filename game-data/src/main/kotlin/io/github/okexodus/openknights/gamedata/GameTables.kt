package io.github.okexodus.openknights.gamedata

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Where the decrypted game tables come from: the player's APK on the PC, the app's own assets on a device (P5). */
interface TableSource {
    /** Names of every table (with `.csv`). */
    fun names(): List<String>

    /** The decrypted bytes of one table; `name` with or without `.csv`. */
    fun raw(name: String): ByteArray
}

/** The game build is not the supported one, or the file is damaged (the message is safe to show). */
class UnsupportedGameFile(message: String) : IllegalArgumentException(message)

/**
 * Every table of the supported game, read from the player's own APK: each `assets/data/config/<name>` entry is
 * RC4-decrypted in memory (fresh cipher state per file) and must hash to the supported definition. Nothing is written
 * to disk. A missing or different table refuses the APK before anything else starts.
 */
class ApkTables(apk: Path, private val supported: SupportedInput = SupportedInput.bundled,
                private val preferPreservedTables: Boolean = false) : TableSource {
    private val bytes: Map<String, ByteArray>

    init {
        if (!Files.isRegularFile(apk)) throw UnsupportedGameFile("The game APK was not found")
        val zip = try { ZipFile(apk.toFile()) } catch (e: ZipException) {
            throw UnsupportedGameFile("The game APK is damaged (not a valid APK/zip file)")
        }
        bytes = zip.use {
            val wanted = supported.tables
            fun entryName(name: String): String {
                val preserved = "assets/openknights/original-tables/$name"
                return if (preferPreservedTables && name in setOf("xinniudan.csv", "niudanhero.csv") && zip.getEntry(preserved) != null)
                    preserved else supported.tablePrefix + name
            }
            val missing = wanted.keys.sorted().filter { name -> zip.getEntry(entryName(name)) == null }
            if (missing.isNotEmpty()) {
                throw UnsupportedGameFile("This APK is not ${supported.label}: ${missing.size} game tables are missing (e.g. ${missing[0]})")
            }
            val cipher = AssetCipher(supported.tableKey)
            val out = LinkedHashMap<String, ByteArray>()
            val mismatched = ArrayList<String>()
            for ((name, expected) in wanted.toSortedMap()) {
                val plain = cipher.decrypt(zip.getInputStream(zip.getEntry(entryName(name))).use { s -> s.readBytes() })
                if (sha256(plain) != expected) mismatched.add(name) else out[name] = plain
            }
            if (mismatched.isNotEmpty()) {
                throw UnsupportedGameFile("This APK is not ${supported.label}: ${mismatched.size} game tables differ (e.g. ${mismatched[0]})")
            }
            out
        }
    }

    override fun names(): List<String> = bytes.keys.sorted()

    override fun raw(name: String): ByteArray {
        val file = if (name.endsWith(".csv")) name else "$name.csv"
        return bytes[file] ?: throw NoSuchElementException("Missing table: $file")
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}

/** One CSV table as the reference's catalog shapes it: header row, then rows of raw text cells keyed by cell 0. */
class GameTable(val name: String, val headers: List<String>, val rows: List<Row>) {
    /** A data row; [csvRow] is the record number + 1 (the header is record 1), as in the reference's row refs. */
    class Row(val table: String, val csvRow: Int, val cells: List<String>, private val headerIndex: Map<String, Int>) {
        val key: String get() = cells[0]

        /** The cell under a header (headers are field ids such as `"101"`); null when the table has no such field. */
        fun field(header: String): String? = headerIndex[header]?.let { cells[it] }
        fun field(header: Int): String? = field(header.toString())

        /** `{"header": cell}` in header order (the reference's `row["fields"]`). */
        fun fields(): Map<String, String> = LinkedHashMap<String, String>().also { m -> headerIndex.forEach { (h, i) -> m[h] = cells[i] } }
    }

    private val index: Map<String, List<Row>> = rows.groupBy { it.key }

    /** Every row whose first cell equals `key` exactly, in file order (duplicates kept). */
    fun lookup(key: String): List<Row> = index[key] ?: emptyList()
    fun lookup(key: Long): List<Row> = lookup(key.toString())
}

/**
 * The catalog of game tables over a [TableSource], parsed lazily and cached. Parsing follows the reference: text is
 * strict UTF-8 with an optional BOM, the CSV dialect is the reference's default (comma, `"` quotes doubled, strict),
 * headers must be unique and every row must have exactly one cell per header.
 */
class GameTables(private val source: TableSource) {
    private val cache = HashMap<String, GameTable>()

    fun names(): List<String> = source.names()

    @Synchronized
    fun table(name: String): GameTable {
        val file = if (name.endsWith(".csv")) name else "$name.csv"
        return cache.getOrPut(file) { parse(file, source.raw(file)) }
    }

    fun lookup(name: String, key: String): List<GameTable.Row> = table(name).lookup(key)

    companion object {
        fun parse(file: String, raw: ByteArray): GameTable {
            val text = decodeUtf8Sig(raw)
            val parsed = Csv.parse(text)
            if (parsed.isEmpty()) throw IllegalArgumentException("Empty table: $file")
            val headers = parsed[0]
            if (headers.toSet().size != headers.size) throw IllegalArgumentException("Duplicate field headers: $file; cannot use field map")
            val headerIndex = LinkedHashMap<String, Int>().also { m -> headers.forEachIndexed { i, h -> m[h] = i } }
            val rows = ArrayList<GameTable.Row>(parsed.size - 1)
            for (i in 1 until parsed.size) {
                val cells = parsed[i]
                if (cells.size != headers.size) throw IllegalArgumentException("Ragged CSV: $file row ${i + 1}")
                rows.add(GameTable.Row(file, i + 1, cells, headerIndex))
            }
            return GameTable(file, headers, rows)
        }

        /** Strict UTF-8, a leading BOM removed (`utf-8-sig`). */
        fun decodeUtf8Sig(raw: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try { decoder.decode(ByteBuffer.wrap(raw)).toString() } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("table is not valid UTF-8")
            }
            return if (text.startsWith(0xFEFF.toChar())) text.substring(1) else text
        }
    }
}

/**
 * The reference's CSV reader (default dialect, `strict=True`) over text read with universal line breaks kept:
 * the same state machine, so quoted line breaks, doubled quotes, blank lines (an empty record) and the errors match.
 */
object Csv {
    class CsvError(message: String) : IllegalArgumentException(message)

    const val FIELD_LIMIT = 131072
    private const val EOL = -1

    private enum class State { START_RECORD, START_FIELD, IN_FIELD, IN_QUOTED_FIELD, QUOTE_IN_QUOTED_FIELD, EAT_CRNL }

    fun parse(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var state = State.START_RECORD
        var fields = ArrayList<String>()
        val field = StringBuilder()

        fun save() {
            fields.add(field.toString())
            field.setLength(0)
        }

        fun add(c: Char) {
            if (field.length >= FIELD_LIMIT) throw CsvError("field larger than field limit ($FIELD_LIMIT)")
            field.append(c)
        }

        fun process(c: Int) {
            when (state) {
                State.START_RECORD -> {
                    if (c == EOL) return          // an empty line: the record stays empty
                    if (c == '\n'.code || c == '\r'.code) { state = State.EAT_CRNL; return }
                    state = State.START_FIELD
                    process(c)
                }
                State.START_FIELD -> when {
                    c == '\n'.code || c == '\r'.code || c == EOL -> { save(); state = if (c == EOL) State.START_RECORD else State.EAT_CRNL }
                    c == '"'.code -> state = State.IN_QUOTED_FIELD
                    c == ','.code -> save()
                    else -> { add(c.toChar()); state = State.IN_FIELD }
                }
                State.IN_FIELD -> when {
                    c == '\n'.code || c == '\r'.code || c == EOL -> { save(); state = if (c == EOL) State.START_RECORD else State.EAT_CRNL }
                    c == ','.code -> { save(); state = State.START_FIELD }
                    else -> add(c.toChar())
                }
                State.IN_QUOTED_FIELD -> when {
                    c == EOL -> {}
                    c == '"'.code -> state = State.QUOTE_IN_QUOTED_FIELD
                    else -> add(c.toChar())
                }
                State.QUOTE_IN_QUOTED_FIELD -> when {
                    c == '"'.code -> { add('"'); state = State.IN_QUOTED_FIELD }
                    c == ','.code -> { save(); state = State.START_FIELD }
                    c == '\n'.code || c == '\r'.code || c == EOL -> { save(); state = if (c == EOL) State.START_RECORD else State.EAT_CRNL }
                    else -> throw CsvError("',' expected after '\"'")
                }
                State.EAT_CRNL -> when {
                    c == '\n'.code || c == '\r'.code -> {}
                    c == EOL -> state = State.START_RECORD
                    else -> throw CsvError("new-line character seen in unquoted field - do you need to open the file with newline=''?")
                }
            }
        }

        // Lines end after "\n", "\r\n" or a lone "\r" (universal line breaks, kept in the text); the reader sees an
        // end-of-line mark after each, and a record is complete when a line ends in the START_RECORD state.
        var i = 0
        val n = text.length
        var lineHasChars = false
        while (i < n) {
            val c = text[i]
            process(c.code)
            lineHasChars = true
            val lineEnds = c == '\n' || (c == '\r' && (i + 1 >= n || text[i + 1] != '\n'))
            i++
            if (lineEnds) {
                process(EOL)
                lineHasChars = false
                if (state == State.START_RECORD) { records.add(fields); fields = ArrayList() }
            }
        }
        if (lineHasChars) {
            process(EOL)
            if (state == State.START_RECORD) { records.add(fields); fields = ArrayList() }
        }
        if (state == State.IN_QUOTED_FIELD || field.isNotEmpty()) throw CsvError("unexpected end of data")
        return records
    }
}

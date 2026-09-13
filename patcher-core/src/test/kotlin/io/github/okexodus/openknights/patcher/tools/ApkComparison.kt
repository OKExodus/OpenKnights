package io.github.okexodus.openknights.patcher.tools

import io.github.okexodus.openknights.patcher.dex.Smali
import io.github.okexodus.openknights.patcher.report.Report
import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.ResourceTable
import io.github.okexodus.openknights.patcher.res.TypeChunk
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.FileSource
import io.github.okexodus.openknights.patcher.zip.ZipArchive
import io.github.okexodus.openknights.patcher.zip.ZipEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Maintainer tool (not shipped): compares two patched APKs of the same game, entry by entry, with the original split
 * set as the third reference, and writes a JSON summary plus text diffs of the manifest and the changed classes.
 *
 *     gradlew :patcher-core:compareApks -Pours=<apk> -Pearlier=<apk> -Poriginal=<folder of split APKs> -Pout=<folder>
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: <ours.apk> <earlier.apk> <original folder> <output folder>" }
    ApkComparison(Path.of(args[0]), Path.of(args[1]), Path.of(args[2])).write(Path.of(args[3]))
}

class ApkComparison(private val oursPath: Path, private val earlierPath: Path, originalFolder: Path) {
    private val ours = ZipArchive.open(FileSource.open(oursPath))
    private val earlier = ZipArchive.open(FileSource.open(earlierPath))
    private val originals = Files.list(originalFolder).use { s -> s.filter { it.toString().endsWith(".apk") }.sorted().toList() }
        .map { ZipArchive.open(FileSource.open(it)) }
    private val originalEntries: Map<String, Pair<ZipArchive, ZipEntry>> =
        originals.flatMap { zip -> zip.entries.map { it.name to (zip to it) } }.toMap()

    fun write(out: Path) {
        Files.createDirectories(out)
        val report = Report()
        report["ours"] = fileSummary(oursPath, ours)
        report["earlier"] = fileSummary(earlierPath, earlier)
        report["entries"] = entries()
        report["manifest"] = manifest(out)
        report["code"] = code(out)
        report["resources"] = resources()
        report["native_library"] = native()
        Files.writeString(out.resolve("diff-report.json"), report.encode())
    }

    private fun fileSummary(path: Path, zip: ZipArchive): Map<String, Any?> {
        val signer = zip.entries.firstOrNull { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".EC")) }
        val certificate = signer?.let { pkcs7Certificate(zip.read(it)) }
        return mapOf(
            "file" to path.fileName.toString(), "size" to Files.size(path), "sha256" to Hashing.sha256(path),
            "entries" to zip.entries.size,
            "stored" to zip.entries.count { it.method == ZipEntry.STORED }, "deflated" to zip.entries.count { it.method == ZipEntry.DEFLATED },
            "v1_signer_file" to signer?.name,
            "certificate_sha256" to certificate?.let { Hashing.sha256(it.encoded) },
            "certificate_subject" to certificate?.subjectX500Principal?.name,
        )
    }

    private fun pkcs7Certificate(bytes: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream()).filterIsInstance<X509Certificate>().firstOrNull()
    }.getOrNull()

    /** Every entry name of both APKs, grouped by what the original split set says about it. */
    private fun entries(): Map<String, Any?> {
        val names = (ours.entries.map { it.name } + earlier.entries.map { it.name }).toSortedSet()
        val groups = sortedMapOf<String, MutableList<String>>()
        fun add(group: String, name: String) { groups.getOrPut(group) { ArrayList() } += name }
        for (name in names) {
            val a = ours[name]
            val b = earlier[name]
            val o = originalEntries[name]?.second
            val group = when {
                a != null && b != null && a.crc == b.crc && a.size == b.size -> "same in both"
                a != null && b != null -> when {
                    o != null && a.crc == o.crc -> "different: ours = original, earlier changed it"
                    o != null && b.crc == o.crc -> "different: earlier = original, ours changed it"
                    o != null -> "different: both changed the original"
                    else -> "different: not in the original"
                }
                a != null -> if (o != null) "only ours: original entry (earlier build renamed or dropped it)" else "only ours: added by the patch"
                else -> if (o != null) "only earlier: original entry dropped here" else "only earlier: added by the earlier build"
            }
            add(group, name)
        }
        return groups.mapValues { (_, list) -> mapOf("count" to list.size, "by_folder" to list.groupingBy { folderOf(it) }.eachCount().toSortedMap(),
            "names" to if (list.size <= 60) list else list.take(60) + "... ${list.size - 60} more") }
    }

    private fun folderOf(name: String): String = when {
        name.startsWith("res/") -> "res/" + name.removePrefix("res/").substringBefore('/')
        name.startsWith("assets/") -> name.split('/').take(4).joinToString("/")
        name.contains('/') -> name.substringBefore('/') + "/"
        else -> name
    }

    private fun manifest(out: Path): Map<String, Any?> {
        val a = BinaryXml.read(ours.read("AndroidManifest.xml")).toText()
        val b = BinaryXml.read(earlier.read("AndroidManifest.xml")).toText()
        val diff = unifiedDiff(b.lines(), a.lines(), "earlier/AndroidManifest.xml", "ours/AndroidManifest.xml")
        Files.writeString(out.resolve("manifest.diff"), diff)
        Files.writeString(out.resolve("manifest-ours.txt"), a)
        Files.writeString(out.resolve("manifest-earlier.txt"), b)
        return mapOf("changed_lines" to diff.lines().count { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") })
    }

    private fun dexFiles(zip: ZipArchive) = zip.entries.map { it.name }.filter { Regex("classes\\d*\\.dex").matches(it) }.associateWith { zip.read(it) }

    private fun code(out: Path): Map<String, Any?> {
        val oursDex = dexFiles(ours).mapValues { Smali.open(it.value) }
        val earlierDex = dexFiles(earlier).mapValues { Smali.open(it.value) }
        val originalDex = originals.flatMap { z -> dexFiles(z).values }.map { Smali.open(it) }
        val api = oursDex.values.first().opcodes.api
        val a = oursDex.flatMap { (n, d) -> d.classes.map { it.type to (n to it) } }.toMap()
        val b = earlierDex.flatMap { (n, d) -> d.classes.map { it.type to (n to it) } }.toMap()
        val o = originalDex.flatMap { d -> d.classes.map { it.type to it } }.toMap()
        val addedOurs = (a.keys - o.keys).sorted()
        val addedEarlier = (b.keys - o.keys).sorted()
        val packageOf = { t: String -> t.substring(0, t.lastIndexOf('/') + 1) }
        val oursPackage = addedOurs.map(packageOf).distinct().singleOrNull()
        val earlierPackage = addedEarlier.map(packageOf).distinct().singleOrNull()
        val diffs = StringBuilder()
        val changedGame = ArrayList<String>()
        val defaultsOnly = ArrayList<String>()
        val earlierReencoded = ArrayList<String>()
        for (type in o.keys.sorted()) {
            val source = Smali.disassemble(o.getValue(type), api)
            val mine = Smali.disassemble(a.getValue(type).second, api)
            val theirs = Smali.disassemble(b.getValue(type).second, api)
            if (mine != source) {
                if (withoutDefaults(mine) == withoutDefaults(source)) defaultsOnly += type else changedGame += type
            }
            if (theirs != source && withoutDefaults(theirs) == withoutDefaults(source)) earlierReencoded += type
        }
        for (type in changedGame) {
            val theirs = Smali.disassemble(b.getValue(type).second, api).let { if (earlierPackage != null && oursPackage != null) it.replace(earlierPackage, oursPackage) else it }
            val original = Smali.disassemble(o.getValue(type), api)
            val mine = Smali.disassemble(a.getValue(type).second, api)
            diffs.append(unifiedDiff(original.lines(), mine.lines(), "original/$type", "ours/$type"))
            diffs.append(unifiedDiff(theirs.lines(), mine.lines(), "earlier(renamed)/$type", "ours/$type"))
        }
        for (type in addedOurs) {
            val theirType = if (earlierPackage != null && oursPackage != null) earlierPackage + type.removePrefix(oursPackage) else type
            val mine = Smali.disassemble(a.getValue(type).second, api)
            val theirs = b[theirType]?.let { Smali.disassemble(it.second, api).replace(earlierPackage ?: "", oursPackage ?: "") } ?: ""
            diffs.append(unifiedDiff(theirs.lines(), mine.lines(), "earlier(renamed)/$theirType", "ours/$type"))
        }
        Files.writeString(out.resolve("code.diff"), diffs.toString())
        return mapOf(
            "ours_dex" to oursDex.mapValues { (n, d) -> mapOf("classes" to d.classes.size, "sha256" to Hashing.sha256(ours.read(n)),
                "same_as_original" to (originals.firstNotNullOfOrNull { it[n] }?.let { oe -> Hashing.sha256(ours.read(n)) == Hashing.sha256(originals.first { z -> z[n] != null }.read(oe)) })) },
            "earlier_dex" to earlierDex.mapValues { (_, d) -> mapOf("classes" to d.classes.size) },
            "added_classes_ours" to addedOurs.map { "${a.getValue(it).first}: $it" },
            "added_classes_earlier" to addedEarlier.map { "${b.getValue(it).first}: $it" },
            "game_classes_changed_ours" to changedGame,
            "game_classes_only_default_values_omitted_ours" to defaultsOnly.size,
            "game_classes_only_default_values_omitted_earlier" to earlierReencoded.size,
        )
    }

    private val defaultValue = Regex("""^(\.field .*\bstatic\b.*?) = (false|null|0x0[tsL]?|0\.0f?|'\\u0000')$""", RegexOption.MULTILINE)
    private fun withoutDefaults(smali: String): String = smali.replace(defaultValue, "$1")

    private fun resources(): Map<String, Any?> {
        fun resolve(zip: ZipArchive): Map<Pair<Int, String>, Pair<String, String>> {
            val table = ResourceTable.read(zip.read("resources.arsc"))
            val pkg = table.packages.single()
            val out = HashMap<Pair<Int, String>, Pair<String, String>>()
            for (chunk in pkg.chunks.filterIsInstance<TypeChunk>()) {
                val config = chunk.config.drop(4).joinToString("") { "%02x".format(it) }.trimEnd('0')
                for (index in chunk.presentIndices()) {
                    val entry = chunk.entry(index)!!
                    val id = (pkg.id shl 24) or (chunk.typeId shl 16) or index
                    val name = "${pkg.typeName(chunk.typeId)}/${pkg.keyStrings[entry.keyIndex]}"
                    val v = entry.value
                    val value = when {
                        v == null -> {
                            val b = ByteBuffer.wrap(entry.bytes).order(ByteOrder.LITTLE_ENDIAN)
                            val headerSize = b.getShort(0).toInt()
                            "map:" + (0 until b.getInt(12)).map { i ->
                                val at = headerSize + 12 * i
                                val type = entry.bytes[at + 7].toInt() and 0xFF
                                val data = b.getInt(at + 8)
                                if (type == ResValue.TYPE_STRING) "%08x=string:%s".format(b.getInt(at), table.globalPool[data])
                                else "%08x=%02x:%08x".format(b.getInt(at), type, data)
                            }.sorted()
                        }
                        v.type == ResValue.TYPE_STRING -> table.globalPool[v.data].let { s ->
                            zip[s]?.let { "file:" + Hashing.sha256(zip.openStream(it)) } ?: "string:$s"
                        }
                        else -> "value:%02x:%08x".format(v.type, v.data)
                    }
                    out[id to config] = name to value
                }
            }
            return out
        }
        val a = resolve(ours)
        val b = resolve(earlier)
        val o = HashMap<Pair<Int, String>, Pair<String, String>>().also { m -> originals.filter { it.contains("resources.arsc") }.forEach { m.putAll(resolve(it)) } }
        val kinds = sortedMapOf<String, Int>()
        val examples = sortedMapOf<String, MutableList<String>>()
        for (key in (a.keys + b.keys).toSortedSet(compareBy({ it.first }, { it.second }))) {
            val kind = when {
                a[key] == b[key] -> "same"
                b[key] == null -> "only ours" + if (o[key] == null) " (added)" else " (original)"
                a[key] == null -> "only earlier"
                a[key] == o[key] -> "earlier differs from the original" + when {
                    b.getValue(key).second.startsWith("map:") -> " (map values)"
                    b.getValue(key).second.startsWith("file:") -> " (file content)"
                    else -> " (value)"
                }
                else -> "ours differs from the original"
            }
            kinds.merge(kind, 1, Int::plus)
            if (kind != "same") examples.getOrPut(kind) { ArrayList() }.let { if (it.size < 12) it += "0x%08x %s %s".format(key.first, key.second, (a[key] ?: b[key])!!.first) }
        }
        return mapOf("values" to kinds, "examples" to examples)
    }

    private fun native(): Map<String, Any?> {
        val name = "lib/arm64-v8a/libhelloworld.so"
        val a = ours.read(name)
        val b = earlier.read(name)
        var differing = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) differing++
        return mapOf("ours_sha256" to Hashing.sha256(a), "earlier_sha256" to Hashing.sha256(b), "identical" to a.contentEquals(b),
            "differing_bytes" to differing + kotlin.math.abs(a.size - b.size),
            "ours_stored" to (ours[name]!!.method == ZipEntry.STORED), "earlier_stored" to (earlier[name]!!.method == ZipEntry.STORED))
    }

    companion object {
        /** A plain unified diff (whole-file hunks) from the longest common subsequence of lines. */
        fun unifiedDiff(a: List<String>, b: List<String>, nameA: String, nameB: String): String {
            if (a == b) return ""
            val n = a.size
            val m = b.size
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            val out = StringBuilder("--- $nameA\n+++ $nameB\n")
            var i = 0
            var j = 0
            var context = ArrayList<String>()
            fun flushContext() { context.takeLast(2).forEach { out.append(" ").append(it).append('\n') }; context = ArrayList() }
            while (i < n || j < m) {
                when {
                    i < n && j < m && a[i] == b[j] -> { context += a[i]; i++; j++ }
                    j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j]) -> { flushContext(); out.append("+").append(b[j]).append('\n'); j++ }
                    else -> { flushContext(); out.append("-").append(a[i]).append('\n'); i++ }
                }
            }
            return out.append('\n').toString()
        }
    }
}

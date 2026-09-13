package io.github.okexodus.openknights.patcher

import io.github.okexodus.openknights.patcher.input.AssetCipher
import io.github.okexodus.openknights.patcher.input.SupportedInput
import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.XmlElement
import io.github.okexodus.openknights.patcher.util.Hashing
import io.github.okexodus.openknights.patcher.zip.ZipWriter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Small synthetic APKs for tests. Nothing here comes from the game: the "game" is a made-up package whose program,
 * library and tables are a few bytes of text, described by [supported].
 */
object Fixtures {
    const val PACKAGE = "com.example.fixturegame"
    const val VERSION_CODE = 7
    const val VERSION_NAME = "1.7"
    private val tableKey = "fixture-key".toByteArray()

    val dex = mapOf("classes.dex" to "dex one".toByteArray(), "classes2.dex" to "dex two".toByteArray())
    val library = "fake arm64 library".toByteArray()
    val tables = mapOf("alpha.csv" to "id,name\n1,a\n".toByteArray(), "beta.csv" to "id\n2\n".toByteArray())

    val supported = SupportedInput(
        label = "Fixture Game 1.7",
        packageName = PACKAGE,
        versionName = VERSION_NAME,
        versionCode = VERSION_CODE,
        dex = dex.mapValues { Hashing.sha256(it.value) },
        nativeLibraryPath = "lib/arm64-v8a/libhelloworld.so",
        nativeLibrarySha256 = Hashing.sha256(library),
        tablePrefix = "assets/data/config/",
        tableKey = tableKey,
        tables = tables.mapValues { Hashing.sha256(it.value) },
    )

    fun manifest(
        packageName: String = PACKAGE,
        versionCode: Int = VERSION_CODE,
        versionName: String? = VERSION_NAME,
        split: String? = null,
        requiredSplitTypes: String? = null,
        splitTypes: String? = null,
    ): ByteArray {
        val root = XmlElement(null, "manifest")
        root.set(BinaryXml.ANDROID_NS, "versionCode", 0x0101021b, ResValue.int(versionCode), raw = null)
        versionName?.let { root.set(BinaryXml.ANDROID_NS, "versionName", 0x0101021c, ResValue.string(it)) }
        requiredSplitTypes?.let { root.set(BinaryXml.ANDROID_NS, "requiredSplitTypes", 0x0101064e, ResValue.string(it)) }
        splitTypes?.let { root.set(BinaryXml.ANDROID_NS, "splitTypes", 0x0101064f, ResValue.string(it)) }
        root.set(null, "package", 0, ResValue.string(packageName))
        split?.let { root.set(null, "split", 0, ResValue.string(it)) }
        root.children += XmlElement(null, "application")
        return BinaryXml.create(root).encode()
    }

    fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipWriter(out).use { writer ->
            entries.forEach { (name, data) -> writer.addStored(name, data, alignment = if (name.endsWith(".so")) 16384 else 4) }
        }
        return out.toByteArray()
    }

    fun encryptedTables(overrides: Map<String, ByteArray> = emptyMap()): Map<String, ByteArray> {
        val cipher = AssetCipher(tableKey)
        return (tables + overrides).map { (name, plain) -> "assets/data/config/$name" to cipher.encrypt(plain) }.toMap()
    }

    fun base(
        split: Boolean = true,
        versionCode: Int = VERSION_CODE,
        packageName: String = PACKAGE,
        dexOverride: Map<String, ByteArray> = emptyMap(),
        tableOverrides: Map<String, ByteArray> = emptyMap(),
        withLibrary: Boolean = !split,
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["AndroidManifest.xml"] = manifest(packageName, versionCode,
            requiredSplitTypes = if (split) "base__abi,base__density" else null, splitTypes = if (split) "" else null)
        (dex + dexOverride).forEach { (n, d) -> entries[n] = d }
        entries["resources.arsc"] = "not a real table".toByteArray()
        entries.putAll(encryptedTables(tableOverrides))
        entries["assets/data/config/notes.xlsx"] = "ignored".toByteArray()
        if (withLibrary) entries["lib/arm64-v8a/libhelloworld.so"] = library
        return zip(entries)
    }

    fun abiSplit(versionCode: Int = VERSION_CODE, lib: ByteArray = library): ByteArray = zip(linkedMapOf(
        "AndroidManifest.xml" to manifest(versionCode = versionCode, versionName = null, split = "config.arm64_v8a", splitTypes = "base__abi"),
        "lib/arm64-v8a/libhelloworld.so" to lib,
    ))

    fun densitySplit(versionCode: Int = VERSION_CODE): ByteArray = zip(linkedMapOf(
        "AndroidManifest.xml" to manifest(versionCode = versionCode, versionName = null, split = "config.mdpi", splitTypes = "base__density"),
        "resources.arsc" to "density table".toByteArray(),
        "res/drawable-mdpi-v4/a.png" to "png".toByteArray(),
    ))

    /** Writes base + splits as loose files into [folder]. */
    fun writeSplitSet(folder: Path, base: ByteArray = base(), abi: ByteArray? = abiSplit(), density: ByteArray? = densitySplit()): Path {
        Files.createDirectories(folder)
        Files.write(folder.resolve("$PACKAGE.apk"), base)
        abi?.let { Files.write(folder.resolve("config.arm64_v8a.apk"), it) }
        density?.let { Files.write(folder.resolve("config.mdpi.apk"), it) }
        return folder
    }

    /** An XAPK-style bundle: the split APKs stored inside one zip, plus a manifest.json and an icon. */
    fun xapk(): ByteArray = zip(linkedMapOf(
        "manifest.json" to """{"package_name":"$PACKAGE"}""".toByteArray(),
        "icon.png" to "icon".toByteArray(),
        "$PACKAGE.apk" to base(),
        "config.arm64_v8a.apk" to abiSplit(),
        "config.mdpi.apk" to densitySplit(),
    ))

    /** An APKM-style bundle whose APKs are compressed inside the zip. */
    fun apkmDeflated(): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, data) in linkedMapOf("info.json" to "{}".toByteArray(), "base.apk" to base(),
                "split_config.arm64_v8a.apk" to abiSplit(), "split_config.mdpi.apk" to densitySplit())) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

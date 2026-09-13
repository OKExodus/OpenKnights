package io.github.okexodus.openknights.patcher.input

import io.github.okexodus.openknights.patcher.res.BinaryXml
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.zip.ZipArchive

/** What an APK's manifest says about it. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Int?,
    val versionName: String?,
    /** The split name (`config.arm64_v8a`), or null for a base or universal APK. */
    val split: String?,
    /** Split types a base needs (`base__abi`, `base__density`). */
    val requiredSplitTypes: List<String>,
    /** Split types a split provides. */
    val splitTypes: List<String>,
) {
    val isBase: Boolean get() = split == null

    companion object {
        fun read(manifest: ByteArray): ManifestInfo {
            val root = BinaryXml.read(manifest).root
            if (root.name != "manifest") throw IllegalArgumentException("the manifest's root element is <${root.name}>")
            fun plain(name: String) = root.attribute(null, name)?.value
            fun android(name: String) = root.androidAttribute(name)?.value
            fun text(v: ResValue?): String? = when {
                v == null -> null
                v.type == ResValue.TYPE_STRING -> v.string
                v.type == ResValue.TYPE_INT_DEC || v.type == ResValue.TYPE_INT_HEX -> v.data.toString()
                else -> null
            }
            fun list(v: ResValue?): List<String> = text(v)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val versionCode = android("versionCode")?.let { if (it.type == ResValue.TYPE_STRING) it.string?.toIntOrNull() else it.data }
            return ManifestInfo(
                packageName = text(plain("package")) ?: throw IllegalArgumentException("the manifest names no package"),
                versionCode = versionCode,
                versionName = text(android("versionName")),
                split = text(plain("split")),
                requiredSplitTypes = list(android("requiredSplitTypes")),
                splitTypes = list(android("splitTypes")),
            )
        }
    }
}

/** One APK of the input: its display name, its archive and its manifest. */
class ApkSource(val name: String, val archive: ZipArchive, val manifest: ManifestInfo) {
    override fun toString(): String = name
}

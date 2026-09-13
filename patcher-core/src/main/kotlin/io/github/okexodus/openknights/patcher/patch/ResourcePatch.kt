package io.github.okexodus.openknights.patcher.patch

import io.github.okexodus.openknights.patcher.FailureCode
import io.github.okexodus.openknights.patcher.PatchFailure
import io.github.okexodus.openknights.patcher.res.ResEntry
import io.github.okexodus.openknights.patcher.res.ResPackage
import io.github.okexodus.openknights.patcher.res.ResValue
import io.github.okexodus.openknights.patcher.res.ResourceTable
import io.github.okexodus.openknights.patcher.res.TypeChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resource-table changes: the split APKs' resources are merged into the base table at their original numeric ids
 * (an id that means something else in the base is an error), and new resources can be added after the last id of a
 * type. Files the merged entries point at are reported so they can be copied from their split.
 */
class ResourcePatch(private val table: ResourceTable) {
    private val base: ResPackage = table.packages.singleOrNull() ?: mismatch("the base resource table must hold exactly one package")

    data class MergeReport(val split: String, val entries: Int, val newIds: List<Int>, val newConfigs: Int, val files: List<String>)

    fun resourceId(typeId: Int, entry: Int): Int = (base.id shl 24) or (typeId shl 16) or entry

    /** Merges [split]'s entries into the base table. */
    fun merge(splitName: String, split: ResourceTable): MergeReport {
        val pkg = split.packages.singleOrNull() ?: mismatch("$splitName: a split resource table must hold exactly one package")
        if (pkg.id != base.id) mismatch("$splitName: package id ${pkg.id} differs from the base's ${base.id}")
        val existingBefore = presentIds()
        var entries = 0
        var newConfigs = 0
        val files = ArrayList<String>()
        for (chunk in pkg.chunks.filterIsInstance<TypeChunk>()) {
            val typeId = chunk.typeId
            val typeName = pkg.typeName(typeId)
            if (typeName != base.typeName(typeId)) mismatch("$splitName: type $typeId is ${typeName} here but ${base.typeName(typeId)} in the base")
            val baseSpec = base.spec(typeId) ?: mismatch("$splitName: the base has no type $typeName")
            val splitSpec = pkg.spec(typeId) ?: mismatch("$splitName: type $typeName has no spec")
            baseSpec.grow(splitSpec.entryCount)
            for (i in 0 until splitSpec.entryCount) baseSpec.setFlags(i, baseSpec.flags(i) or splitSpec.flags(i))
            var target = base.types(typeId).firstOrNull { it.config.contentEquals(chunk.config) }
            if (target == null) {
                target = TypeChunk.create(typeId, chunk.config.copyOf(), baseSpec.entryCount)
                base.addType(target)
                newConfigs++
            }
            for (index in chunk.presentIndices()) {
                val entry = chunk.entry(index)!!
                val name = pkg.keyStrings[entry.keyIndex]
                val known = base.entryName(typeId, index)
                if (known != null && known != name) {
                    mismatch("$splitName: resource ${resourceId(typeId, index).hexId()} is $typeName/$name there but $typeName/$known in the base")
                }
                val remapped = entry.remap(key = { base.keyStrings.intern(name) }, string = { internString(split, it) })
                val existing = target.entry(index)
                if (existing != null) {
                    if (!existing.bytes.contentEquals(remapped.bytes)) {
                        mismatch("$splitName: resource ${resourceId(typeId, index).hexId()} ($typeName/$name) already has a different value for this configuration")
                    }
                    continue
                }
                target.setEntry(index, remapped)
                entries++
                if (remapped.value?.type == ResValue.TYPE_STRING) files += table.globalPool[remapped.value!!.data]
            }
            target.grow(baseSpec.entryCount)
        }
        val newIds = (presentIds() - existingBefore).sorted()
        return MergeReport(splitName, entries, newIds, newConfigs, files)
    }

    private fun internString(split: ResourceTable, index: Int): Int = table.globalPool.intern(split.globalPool[index])

    /** Adds a new resource of [type] with one file per configuration (config bytes → file path). */
    fun addFileResource(type: String, name: String, configs: List<Pair<ByteArray, String>>): Int {
        val typeId = base.typeId(type).takeIf { it > 0 } ?: mismatch("the base has no resource type $type")
        val spec = base.spec(typeId) ?: mismatch("the base has no spec for $type")
        if ((0 until spec.entryCount).any { base.entryName(typeId, it) == name }) mismatch("the base already has a resource $type/$name")
        val index = spec.entryCount
        spec.grow(index + 1, flags = configFlags(configs.map { it.first }))
        val key = base.keyStrings.intern(name)
        for ((config, path) in configs) {
            val chunk = base.types(typeId).firstOrNull { it.config.contentEquals(config) }
                ?: TypeChunk.create(typeId, config, index + 1).also { base.addType(it) }
            chunk.setEntry(index, ResEntry.simple(key, ResValue(ResValue.TYPE_STRING, table.globalPool.intern(path))))
        }
        for (chunk in base.types(typeId)) chunk.grow(index + 1)
        return resourceId(typeId, index)
    }

    fun encode(): ByteArray = table.encode()

    private fun presentIds(): Set<Int> = base.chunks.filterIsInstance<TypeChunk>()
        .flatMap { t -> t.presentIndices().map { resourceId(t.typeId, it) } }.toSet()

    private fun configFlags(configs: List<ByteArray>): Int {
        var flags = 0
        for (c in configs) {
            if (density(c) != 0) flags = flags or CONFIG_DENSITY
            if (sdk(c) != 0) flags = flags or CONFIG_VERSION
        }
        return flags
    }

    private fun Int.hexId(): String = "0x%08x".format(this)

    private fun mismatch(what: String): Nothing =
        throw PatchFailure(FailureCode.PATCH_SITE_MISMATCH, "The app resources are not as expected: $what. Nothing was patched.")

    companion object {
        /** `ResTable_config` change flags used in type specs. */
        const val CONFIG_DENSITY = 0x0100
        const val CONFIG_VERSION = 0x0400

        const val DENSITY_ANY = 0xFFFE

        /** A 64-byte `ResTable_config` with only a density and a platform version set. */
        fun config(density: Int = 0, sdk: Int = 0): ByteArray {
            val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0, 64)
            b.putShort(14, density.toShort())
            b.putShort(24, sdk.toShort())
            return b.array()
        }

        fun density(config: ByteArray): Int = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN).getShort(14).toInt() and 0xFFFF
        fun sdk(config: ByteArray): Int = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN).getShort(24).toInt() and 0xFFFF
    }
}

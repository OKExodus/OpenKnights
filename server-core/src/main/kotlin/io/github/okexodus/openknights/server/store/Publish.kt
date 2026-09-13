package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jobj
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Publishing a new file without hard links (`publish.py`): the caller writes and closes the complete file under a
 * temporary name in the target's directory; [publishNew] fsyncs it, takes an exclusive claim `<target>.claim`
 * (create-new: exactly one publisher wins), refuses an existing target, renames atomically and fsyncs the directory
 * where the OS supports it, then drops the claim. A crash never leaves a half target.
 */
object Publish {
    const val CLAIM_SUFFIX = ".claim"
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    fun fsyncFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    /** Persist a directory entry change (POSIX); Windows has no directory fsync (NTFS journals the rename). */
    fun fsyncDirectory(path: Path): Boolean {
        if (windows) return false
        return try { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }; true } catch (_: Exception) { false }
    }

    fun publishNew(temporary: Path, target: Path, afterClaim: (() -> Unit)? = null): Path {
        require(temporary.toAbsolutePath().parent.normalize() == target.toAbsolutePath().parent.normalize()) {
            "Publish within one directory (the rename must stay on one volume)"
        }
        fsyncFile(temporary)
        val claim = target.resolveSibling(target.fileName.toString() + CLAIM_SUFFIX)
        try {
            Files.createFile(claim)
        } catch (e: FileAlreadyExistsException) {
            throw FileAlreadyExistsException(claim.fileName.toString(), null, "Another publisher holds it (or a crashed one left it)")
        }
        try {
            Files.writeString(claim, Json.dumps(jobj("pid" to ProcessHandle.current().pid(), "time" to io.github.okexodus.openknights.exact.Now.seconds())))
            afterClaim?.invoke()
            if (Files.exists(target)) throw FileAlreadyExistsException(target.fileName.toString(), null, "already exists; publishing never overwrites")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            fsyncDirectory(target.parent)
        } finally {
            Files.deleteIfExists(claim)
        }
        return target
    }

    /** Claims and temporary publish files of a crashed publisher move into `quarantine` (never deleted). */
    fun clearStaleClaims(directory: Path, quarantine: Path): List<String> {
        val moved = ArrayList<String>()
        if (!Files.isDirectory(directory)) return moved
        val names = Files.list(directory).use { s -> s.map { it.fileName.toString() }.toList() }
        val stale = names.filter { it.endsWith(CLAIM_SUFFIX) }.sorted() + names.filter { it.startsWith(".") && it.contains(".init-") }.sorted()
        for (name in stale) {
            Files.createDirectories(quarantine)
            Files.move(directory.resolve(name), quarantine.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            moved.add(name)
        }
        return moved
    }

    /** A temporary file next to `target` (`.<name>.<tag>-<random><suffix>`), like the reference's mkstemp. */
    fun temporaryBeside(target: Path, tag: String, suffix: String): Path =
        Files.createTempFile(target.toAbsolutePath().parent, ".${target.fileName}.$tag-", suffix)

    /**
     * Replace a small JSON file atomically: `json.dumps(document, indent=1, sort_keys=True) + "\n"` written in text
     * mode (the platform's line separator, as the reference writes it), fsync, rename, directory fsync.
     */
    fun writeJsonAtomic(path: Path, document: io.github.okexodus.openknights.exact.JValue) {
        val text = (Json.dumps(document, sortKeys = true, indent = 1) + "\n").replace("\n", System.lineSeparator())
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".${path.fileName}.", ".tmp")
        try {
            Files.write(temporary, text.toByteArray(Charsets.UTF_8))
            fsyncFile(temporary)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(path.toAbsolutePath().parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Root-relative stored paths (`data_paths.py`): POSIX, relative to the directory of the database that stores them. */
object DataPaths {
    class OutsideDataRoot(message: String) : IllegalArgumentException(message)

    fun isAbsoluteText(value: String): Boolean =
        value.startsWith("/") || value.startsWith("\\") || (value.length > 1 && value[1] == ':')

    fun toStored(path: Path, base: Path, strict: Boolean = false): String {
        val resolved = path.toAbsolutePath().normalize()
        val root = base.toAbsolutePath().normalize()
        if (!resolved.startsWith(root)) {
            if (strict) throw OutsideDataRoot("Path is outside the data root")
            return resolved.toString()
        }
        val relative = root.relativize(resolved)
        if (relative.toString().isEmpty()) throw OutsideDataRoot("A stored path must name something inside the data root, not the root itself")
        return relative.joinToString("/") { it.toString() }
    }

    fun fromStored(value: String, base: Path): Path {
        require(value.isNotEmpty()) { "Stored path must be nonempty text" }
        if (isAbsoluteText(value)) return Path.of(value)
        val parts = value.split('/')
        require(".." !in parts) { "Stored relative path escapes the data root" }
        return parts.fold(base) { p, part -> p.resolve(part) }
    }

    /** Uniqueness key of a stored relative path: the text itself. */
    fun keyOf(value: String): String = if (isAbsoluteText(value)) value.lowercase() else value
}

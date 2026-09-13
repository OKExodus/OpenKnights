package io.github.okexodus.openknights.patcher

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parity tests compare the patcher's output with an earlier patched build of the same game. They run only where
 * OPENKNIGHTS_REFERENCE_APK points at that APK (a developer machine), never in CI. Names that differ on purpose
 * (the earlier build's class package, labels) are discovered from the files, not written here.
 */
object LocalReference {
    fun require(): Path {
        val path = System.getenv("OPENKNIGHTS_REFERENCE_APK")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(path != null && Files.isRegularFile(path), "OPENKNIGHTS_REFERENCE_APK is not set: parity test skipped")
        return path!!
    }

    /** The package of a class descriptor: `La/b/C;` → `La/b/`. */
    fun packageOf(type: String): String = type.substring(0, type.lastIndexOf('/') + 1)

    /** Line-by-line differences of two texts of the same length, as (line a, line b) pairs. */
    fun differingLines(a: String, b: String): List<Pair<String, String>> {
        val left = a.lines()
        val right = b.lines()
        if (left.size != right.size) return listOf("${left.size} lines" to "${right.size} lines")
        return left.indices.filter { left[it] != right[it] }.map { left[it] to right[it] }
    }
}

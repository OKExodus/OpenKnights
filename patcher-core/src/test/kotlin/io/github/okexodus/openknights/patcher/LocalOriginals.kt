package io.github.okexodus.openknights.patcher

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests that need a real copy of the game run only on a developer machine where OPENKNIGHTS_ORIGINALS points at the
 * folder holding the split set (base APK plus its splits). They are skipped everywhere else, including CI.
 */
object LocalOriginals {
    const val BASE = "com.enjoygame.hero2d.apk"
    const val ABI_SPLIT = "config.arm64_v8a.apk"
    const val DENSITY_SPLIT = "config.mdpi.apk"

    val folder: Path? by lazy {
        System.getenv("OPENKNIGHTS_ORIGINALS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    }

    /** The folder, or skips the calling test when no originals are configured. */
    fun require(): Path {
        val path = folder
        assumeTrue(path != null && Files.isRegularFile(path.resolve(BASE)), "OPENKNIGHTS_ORIGINALS is not set: local-only test skipped")
        return path!!
    }

    fun file(name: String): Path = require().resolve(name)
}

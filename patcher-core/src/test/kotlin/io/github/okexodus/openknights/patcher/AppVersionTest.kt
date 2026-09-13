package io.github.okexodus.openknights.patcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AppVersionTest {
    @Test
    fun `parses and prints the four parts`() {
        val version = AppVersion.parse("0.1.0.0")
        assertEquals(AppVersion(0, 1, 0, 0), version)
        assertEquals("0.1.0.0", version.toString())
    }

    @Test
    fun `version codes follow the documented formula`() {
        assertEquals(10_000, AppVersion.parse("0.1.0.0").versionCode)
        assertEquals(1_020_304, AppVersion.parse("1.2.3.4").versionCode)
    }

    @Test
    fun `a higher version always has a higher code`() {
        val ordered = listOf("0.1.0.0", "0.1.0.1", "0.1.1.0", "0.2.0.0", "0.99.99.99", "1.0.0.0").map(AppVersion::parse)
        assertEquals(ordered, ordered.shuffled().sorted())
        ordered.zipWithNext().forEach { (lower, higher) -> assertTrue(lower.versionCode < higher.versionCode) }
    }

    @Test
    fun `the largest version stays within Android's limit`() {
        val largest = AppVersion(AppVersion.MAX_MAJOR, 99, 99, 99)
        assertTrue(largest.versionCode <= 2_100_000_000)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0.1.0", "0.1.0.0.0", "0.1.0.100", "01.0.0.0", "a.b.c.d", "0.1.-1.0", "2100.0.0.0", "0. 1.0.0"])
    fun `rejects malformed versions`(text: String) {
        assertThrows<IllegalArgumentException> { AppVersion.parse(text) }
    }

    @Test
    fun `the build carries the project version`() {
        assertTrue(BuildInfo.version >= AppVersion(0, 1, 0, 0))
    }
}

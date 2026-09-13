package io.github.okexodus.openknights.server.pc.harness

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.protocol.WireWriter
import io.github.okexodus.openknights.server.session.CharacterSelect
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BundleRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private val token = "T".repeat(20) + "o".repeat(20) + "k12"

    /** A made-up bundle: an empty root, one sign-in session, a login hop, a game hop, and what a reference answered. */
    private fun bundle(list: ByteArray, extraGameRequest: Boolean = true): Path {
        val dir = tmp.resolve("bundle")
        val root = DataRoot(dir.resolve("baseline").resolve("root"), JdbcSqlDriver()).open()
        val generation = root.unbornStaging()
        val registry = Service.ownerRegistry(generation, JdbcSqlDriver())
        val account = registry.accountByUsername("owner")!!.string("account_id")
        val now = 1_800_000_000L
        fun cstr(t: String) = t.toByteArray() + byteArrayOf(0)
        val signIn = cstr("d") + cstr(token) + byteArrayOf(1, 0) + cstr("") + cstr("") + cstr("") + cstr("")
        val login = jarr(jarr("c", 7683, now, signIn.toHexString()), jarr("s", 7680, now, "00"),
            jarr("c", 7713, now + 1, (cstr("1") + cstr("2")).toHexString()), jarr("s", 7720, now + 1, list.toHexString()))
        val c3 = WireWriter().u32(12_345_678).cstring("a".toByteArray()).cstring("b".toByteArray()).u8(0).cstring(token.toByteArray()).u8(0).bytes()
        val game = jarr(jarr("c", 3, now + 2, c3.toHexString()), jarr("s", 14, now + 2, "0000000000000000"), jarr("s", 18, now + 2, "0001000000"))
        if (extraGameRequest) game.add(jarr("c", 257, now + 3, ""))
        val document = jobj("profile" to "openknights_fixture_bundle_v1", "name" to "made-up", "kind" to "scenario", "mode" to "release",
            "baseline" to jobj("layout" to "release_data_root", "path" to "baseline/root", "generation" to generation.fileName.toString(),
                "registry" to "worlds/${generation.fileName}/registry.sqlite3", "world" to null),
            "service" to jobj("device_owner" to "owner", "announcement" to "Hello", "create_row_label" to "+ Create a character",
                "create_enabled" to true, "offers" to listOf(1, 2, 3), "row_ids" to listOf(10001, 10002), "device_game_port" to 19121,
                "captured_wire_account_id" to null),
            "clock" to jobj("offsets" to JArr()),
            "sessions" to jarr(jobj("session_id" to "sess_" + "a".repeat(32), "token_sha256" to sha256Hex(token.toByteArray()),
                "account_id" to account, "character_id" to null, "issued_epoch" to now - 10, "ttl_seconds" to 3600, "expires_epoch" to now + 3590)),
            "connections" to jarr(jobj("service" to "login", "frames" to login), jobj("service" to "game", "frames" to game)),
            "revisions" to JArr(), "created" to JArr(), "groups" to jobj("3" to 1, "257" to 1), "exclusions" to listOf(8, 14, 768))
        Files.writeString(dir.resolve("bundle.json"), Json.dumps(document))
        return dir
    }

    @Test
    fun `a bundle replays with the login hop compared byte for byte, the game hop classified and the rest listed as waiting`() {
        val list = CharacterSelect.serverList(listOf(CharacterSelect.Row(10002, "+ Create a character", 1)), 10002, "Hello")
        val report = BundleRunner().run(bundle(list), tmp.resolve("work"))
        val covered = report.obj("covered")
        assertEquals(2L, covered.obj("login_hop").long("passed"))
        assertEquals(0L, covered.obj("login_hop").long("failed"))
        // that wire id is not a character of the baseline world: its game hop waits (a character made during the run)
        val waiting = report.obj("waiting")
        assertEquals(2L, waiting.long("client_requests"))
        assertEquals(2L, waiting.obj("by_port_group").long("group 1"))
        assertTrue(report.bool("passed"))
    }

    @Test
    fun `a difference in a reply byte fails the bundle`() {
        val list = CharacterSelect.serverList(listOf(CharacterSelect.Row(10002, "+ Create a character", 1)), 10002, "Hellp")
        val report = BundleRunner().run(bundle(list, extraGameRequest = false), tmp.resolve("work"))
        assertEquals(1L, report.obj("covered").obj("login_hop").long("failed"))
        assertFalse(report.bool("passed"))
    }

    /** Local only: every private bundle of a maintainer's corpus (OPENKNIGHTS_BUNDLES) passes what this server covers. */
    @Test
    fun `the private bundles pass`() {
        val folder = System.getenv("OPENKNIGHTS_BUNDLES")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(folder != null && Files.isDirectory(folder), "OPENKNIGHTS_BUNDLES is not set: local-only test skipped")
        val bundles = Files.list(folder!!).use { s -> s.filter { Files.isRegularFile(it.resolve("bundle.json")) }.sorted().toList() }
        val failed = ArrayList<String>()
        val apk = System.getenv("OPENKNIGHTS_APK")?.let { Path.of(it) }
            ?: System.getenv("OPENKNIGHTS_ORIGINALS")?.let { Path.of(it, "com.enjoygame.hero2d.apk") }
        val releaseData = System.getenv("OPENKNIGHTS_RELEASE_DATA")?.let { Path.of(it) }
        for (b in bundles) {
            if (Files.readString(b.resolve("bundle.json")).take(64).contains("openknights_fixture_bundle_v2")) {
                // a recording (bundle v2) needs the player's APK and the release data
                if (apk == null || releaseData == null) continue
                val report = RecordingRunner(apk, releaseData).run(b, tmp.resolve("work"))
                println(Json.dumps(jobj("bundle" to report.str("bundle"), "compared" to report["compared_steps"], "waiting" to report.obj("waiting").long("steps"))))
                if (!report.bool("passed")) failed.add(report.str("bundle"))
                continue
            }
            val report = BundleRunner().run(b, tmp.resolve("work"))
            println(Json.dumps(report.obj("covered").let { c -> jobj("bundle" to report.str("bundle"), "login" to c.obj("login_hop").long("passed"),
                "auth" to c.obj("game_authentication").long("passed"), "codec" to c.obj("codec").long("passed"), "waiting" to report.obj("waiting").long("client_requests")) }))
            if (!report.bool("passed")) failed.add(report.str("bundle"))
        }
        assertTrue(bundles.isNotEmpty())
        assertTrue(failed.isEmpty()) { "bundles failing: $failed" }
    }
}

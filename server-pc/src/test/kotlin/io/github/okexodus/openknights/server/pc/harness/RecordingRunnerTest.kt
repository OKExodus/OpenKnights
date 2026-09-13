package io.github.okexodus.openknights.server.pc.harness

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.session.CharacterSelect
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.Fingerprint
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.LocalAuth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The recording runner's rules on a made-up recording (no game data): replies and HTTP bodies compared byte for byte,
 * an unported step waiting by port group, a reply difference failing the bundle, and the release-data check.
 */
class RecordingRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private val driver = JdbcSqlDriver()
    private val badToken = "x".repeat(43)

    private fun cstr(t: String) = t.toByteArray() + byteArrayOf(0)

    /** A root with the owner's registry (unborn), the release-data folder, and a starter that serves it. */
    private fun setup(): Triple<Path, Path, (Path, ServiceLog) -> Service> {
        val base = tmp.resolve("base-root")
        val root = DataRoot(base, driver).open()
        Service.ownerRegistry(root.unbornStaging(), driver)
        val releaseData = Files.createDirectories(tmp.resolve("release-data"))
        Files.writeString(releaseData.resolve("MANIFEST.json"), "{\"files\": {}}\n")
        val empty = GameTables(object : TableSource {
            override fun names() = emptyList<String>()
            override fun raw(name: String) = throw NoSuchElementException(name)
        })
        val starter = { rootPath: Path, log: ServiceLog ->
            val dataRoot = DataRoot(rootPath, driver)
            val generation = Files.list(rootPath.resolve("worlds")).use { s -> s.toList().first() }
            val registry = AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver)
            val auth = LocalAuth(registry).also { it.deviceOwner = Service.OWNER }
            Service(driver, log, DeviceClock(null), empty, null, auth, null,
                CharacterSelect(listOf(1, 2, 3), listOf(10001, 10002), "Hello", "+ Create a character"), dataRoot, generation)
        }
        return Triple(base, releaseData, starter)
    }

    private fun step(i: Int, kind: String, vararg fields: Pair<String, Any?>): JObj =
        jobj("i" to i, "kind" to kind, "at" to 1_800_000_000.0 + i, "offset" to 0, "entropy" to listOf(0, 0),
            "pushes" to JArr(), "db" to JObj(), "files" to JObj()).also { o -> fields.forEach { (k, v) -> o[k] = io.github.okexodus.openknights.exact.jvalue(v) } }

    private fun bundle(base: Path, releaseData: Path, loginReply: String): Path {
        val dir = tmp.resolve("bundle")
        val baseline = dir.resolve("baseline").resolve("root")
        Files.walk(base).use { s -> s.forEach { src ->
            val dst = baseline.resolve(base.relativize(src).toString())
            if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
        } }
        val databases = JObj()
        val worlds = baseline.resolve("worlds")
        Files.walk(worlds).use { s -> s.filter { it.fileName.toString().endsWith(".sqlite3") }.toList() }.forEach { db ->
            databases[baseline.relativize(db).joinToString("/")] = JObj().also { t ->
                Fingerprint.database(db, driver).obj("tables").forEach { (name, v) -> t[name] = JStr((v as JObj).str("sha256")) }
            }
        }
        val signIn = cstr("d") + cstr(badToken) + byteArrayOf(1, 0) + cstr("") + cstr("") + cstr("") + cstr("")
        val steps = jarr(
            step(0, "start"),
            step(1, "http", "path" to "/api/recharge", "body" to jobj("token" to badToken), "status" to 401,
                "response" to """{"error":"Credentials, session or character selection rejected"}"""),
            step(2, "http", "path" to "/api/login", "body" to jobj("username" to "owner", "password" to "p".repeat(12)), "status" to 401,
                "response" to """{"error":"Credentials, session or character selection rejected"}"""),
            step(3, "open", "conn" to 1, "service" to "login"),
            step(4, "frame", "conn" to 1, "op" to 7683, "payload" to signIn.toHexString(), "replies" to jarr(jarr(7680, loginReply)), "closed" to true),
            step(5, "open", "conn" to 2, "service" to "login"),
            step(6, "frame", "conn" to 2, "op" to 7713, "payload" to (cstr("1") + cstr("2")).toHexString(), "replies" to jarr(jarr(7680, "01")), "closed" to true),
            step(7, "stop"))
        val document = jobj("profile" to "openknights_fixture_bundle_v2", "name" to "made-up-recording", "kind" to "recording",
            "mode" to "release", "source" to jobj("kind" to "scenario", "id" to "made-up"),
            "reference" to jobj("release_data_manifest_sha256" to sha256Hex(Files.readAllBytes(releaseData.resolve("MANIFEST.json")))),
            "environment" to jobj("tape_seed" to "made-up", "device_game_port" to 19121),
            "baseline" to jobj("path" to "baseline/root", "databases" to databases),
            "connections" to jobj("1" to "login", "2" to "login"), "tape" to JArr(), "steps" to steps, "final" to null,
            "groups" to jobj("7683" to 1, "7713" to 1), "exclusions" to listOf(8, 14, 768))
        Files.writeString(dir.resolve("bundle.json"), Json.dumps(document))
        return dir
    }

    @Test
    fun `a recording replays byte for byte and an unported step waits by port group`() {
        val (base, releaseData, starter) = setup()
        val report = RecordingRunner(tmp.resolve("no.apk"), releaseData, driver, starter).run(bundle(base, releaseData, "01"), tmp.resolve("work"))
        assertTrue(report.bool("passed")) { Json.dumps(report) }
        assertEquals(0L, report.obj("step_checks").long("failed"))
        // the password sign-in is not ported yet: it waits (group 1); everything else is compared
        assertEquals(1L, report.obj("waiting").long("steps"))
        assertEquals(1L, report.obj("waiting").obj("by_port_group").long("group 1"))
        assertEquals(7L, report.long("compared_steps"))
    }

    @Test
    fun `a reply byte differing fails the recording`() {
        val (base, releaseData, starter) = setup()
        val report = RecordingRunner(tmp.resolve("no.apk"), releaseData, driver, starter).run(bundle(base, releaseData, "00"), tmp.resolve("work"))
        assertFalse(report.bool("passed"))
        assertEquals(1L, report.obj("step_checks").long("failed"))
    }

    @Test
    fun `a recording made with other release data is refused`() {
        val (base, releaseData, starter) = setup()
        val dir = bundle(base, releaseData, "01")
        Files.writeString(releaseData.resolve("MANIFEST.json"), "{\"files\": {\"x\": 1}}\n")
        val report = RecordingRunner(tmp.resolve("no.apk"), releaseData, driver, starter).run(dir, tmp.resolve("work"))
        assertFalse(report.bool("passed"))
        assertTrue(report.str("error").startsWith("the release data differs"))
    }
}

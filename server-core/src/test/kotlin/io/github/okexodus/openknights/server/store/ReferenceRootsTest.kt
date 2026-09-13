package io.github.okexodus.openknights.server.store

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.jobj
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local only (OPENKNIGHTS_DEV_DIR with `p3-store-inputs.json` from the maintainer's private tool): data roots written
 * by the reference server are opened here — every registry, world, bots database and save with its integrity checks —
 * and must give the same fingerprint; then roots written here (a logical copy of each, an unborn root and a root with
 * a newly born world) are recorded in `p3-store-results.json` for the reference to open in turn.
 */
class ReferenceRootsTest {
    private val driver = JdbcSqlDriver()

    private fun fingerprintDiff(a: JObj, b: JObj): List<String> {
        val out = ArrayList<String>()
        val da = a.obj("databases")
        val db = b.obj("databases")
        for (name in (da.keys + db.keys).sorted()) {
            val ta = (da[name] as? JObj)?.obj("tables")
            val tb = (db[name] as? JObj)?.obj("tables")
            if (ta == null || tb == null) { out.add("$name: only on one side"); continue }
            for (t in (ta.keys + tb.keys).sorted()) if (ta[t] != tb[t]) out.add("$name/$t")
        }
        if (a.obj("manifest") != b.obj("manifest")) out.add("manifest")
        return out
    }

    @Test
    fun `reference data roots open here and roots written here are recorded for the reference`() {
        val dev = System.getenv("OPENKNIGHTS_DEV_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val inputsFile = dev?.resolve("p3-store-inputs.json")
        assumeTrue(inputsFile != null && Files.isRegularFile(inputsFile), "OPENKNIGHTS_DEV_DIR not set: local-only test skipped")
        val inputs = Json.loads(Files.readAllBytes(inputsFile!!)).asObj
        val output = Path.of(inputs.str("output"))
        Files.createDirectories(output)
        val written = JArr()
        val report = ArrayList<String>()
        for (r in inputs.arr("roots")) {
            val entry = r.asObj
            val name = entry.str("name")
            val root = DataRoot(Path.of(entry.str("path")), driver).open()
            val mine = Fingerprint.compute(root, driver)
            val diff = fingerprintDiff(entry.obj("fingerprint"), mine)
            assertTrue(diff.isEmpty()) { "$name: fingerprint differs in $diff" }
            assertEquals(entry.str("fingerprint_sha256"), Fingerprint.sha256(mine))
            // Every save opens with its integrity checks; every character is a member of its world with its own wire id.
            val generation = root.active!!
            val registry = AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)
            val world = WorldDirectory(generation.resolve(DataRoot.WORLD), driver, strictPaths = true)
            val characters = registry.listCharacters()
            for (character in characters) {
                val current = registry.resolveStateStore(character.characterId, character.accountId).read()
                val member = world.member(character.characterId) ?: error("${character.characterId} is not in its world")
                val profile = current.characterProfile!!.obj("document")
                assertEquals(profile.long("wire_account_id"), member.long("wire_account_id"))
            }
            // The reference's device session works here; a session issued here is recorded for the reference.
            val auth = LocalAuth(registry).also { it.deviceOwner = "owner" }
            assertEquals("owner", auth.authenticate(entry.str("token")).username)
            val kotlinToken = auth.deviceLogin(actor = "p3-store-proof").token
            report.add("$name: ${characters.size} saves read, fingerprint ${Fingerprint.sha256(mine).take(12)} equal, reference token accepted")

            // A logical copy written by this port: every database of the generation re-created from its own schema.
            val copyRoot = DataRoot(output.resolve("$name-copy"), driver).open()
            val copyGeneration = copyRoot.worlds.resolve(generation.fileName.toString())
            val sources = Files.walk(generation).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sqlite3") }.toList() }
                .filter { !generation.relativize(it).toString().replace('\\', '/').let { p -> p.startsWith(".") || p.contains("/.") } }
            for (source in sources) {
                val target = copyGeneration.resolve(generation.relativize(source).toString())
                LogicalCopy.copyDatabase(source, target, driver)
                val schema = LogicalCopy.schemaDifferences(source, target, driver)
                assertTrue(schema.isEmpty()) { "$name ${source.fileName}: schema differs in $schema" }
            }
            copyRoot.commitActive(generation.fileName.toString(), "logical copy written by the port (P3 store proof)")
            val copyFingerprint = Fingerprint.compute(copyRoot, driver)
            // Compared with the source as it is now (the session issued above is part of both).
            val copyDiff = fingerprintDiff(Fingerprint.compute(root, driver), copyFingerprint).filter { it != "manifest" }
            assertTrue(copyDiff.isEmpty()) { "$name copy differs in $copyDiff" }
            written.add(jobj("name" to "$name-copy", "path" to copyRoot.root.toString(), "token" to kotlinToken,
                "fingerprint_sha256" to Fingerprint.sha256(copyFingerprint), "release_configure" to true))
        }

        // An unborn root and a root with a newly born world, both written only by this port.
        val unborn = DataRoot(output.resolve("kotlin-unborn"), driver).open()
        val staging = unborn.unbornStaging()
        val registry = AccountRegistry.initialize(staging.resolve(DataRoot.REGISTRY), driver)
        LocalAuth.initialize(registry, actor = "release-service")
        registry.createAccount("owner", java.util.UUID.randomUUID().toString() + "-unused", actor = "release-service")
        val unbornAuth = LocalAuth(AccountRegistry(staging.resolve(DataRoot.REGISTRY), driver, strictPaths = true)).also { it.deviceOwner = "owner" }
        written.add(jobj("name" to "kotlin-unborn", "path" to unborn.root.toString(), "token" to unbornAuth.deviceLogin().token,
            "fingerprint_sha256" to Fingerprint.sha256(Fingerprint.compute(unborn, driver)), "release_configure" to true))

        val bornRoot = DataRoot(output.resolve("kotlin-born"), driver).open()
        val generation = bornRoot.unbornStaging()
        val bornRegistry = AccountRegistry.initialize(generation.resolve(DataRoot.REGISTRY), driver)
        LocalAuth.initialize(bornRegistry, actor = "release-service")
        bornRegistry.createAccount("owner", java.util.UUID.randomUUID().toString() + "-unused", actor = "release-service")
        val seed = WorldDirectory.newSeed()
        WorldDirectory.initialize(generation.resolve(DataRoot.WORLD), driver, seed)
        BotsDatabase.initialize(generation.resolve(DataRoot.BOTS), driver, seed)
        bornRoot.commitActive(generation.fileName.toString(), "world born (P3 store proof, no character)")
        val bornAuth = LocalAuth(AccountRegistry(generation.resolve(DataRoot.REGISTRY), driver, strictPaths = true)).also { it.deviceOwner = "owner" }
        written.add(jobj("name" to "kotlin-born", "path" to bornRoot.root.toString(), "token" to bornAuth.deviceLogin().token,
            "fingerprint_sha256" to Fingerprint.sha256(Fingerprint.compute(bornRoot, driver)), "release_configure" to true))

        Files.writeString(dev.resolve("p3-store-results.json"), Json.dumps(jobj("profile" to "openknights_p3_store_results_v1",
            "kotlin_roots" to written, "report" to report), indent = 1))
        report.forEach { println(it) }
    }
}

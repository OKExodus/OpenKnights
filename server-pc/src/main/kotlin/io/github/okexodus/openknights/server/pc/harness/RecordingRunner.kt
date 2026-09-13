package io.github.okexodus.openknights.server.pc.harness

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JBool
import io.github.okexodus.openknights.exact.JFloat
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JNull
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.TapeEntropy
import io.github.okexodus.openknights.server.TapeMismatch
import io.github.okexodus.openknights.server.session.AuthGateway
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import io.github.okexodus.openknights.server.session.Session
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.Fingerprint
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.SqlDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The differential harness for recordings (profile `openknights_fixture_bundle_v2`, written by a maintainer's private
 * recorder): the reference ran in-process in release mode with a pinned environment — one clock
 * value per step and a tape of every random draw — and this runner replays the same steps against this server with the
 * same clock and the same draws.
 *
 * Per step it compares, byte for byte: the reply frames, the frames pushed to other connections, whether the
 * connection closed, the HTTP status and body, how many random draws the step took, and — after every step — the
 * logical hash of every table of every database that changed on either side (`Fingerprint`, the same definition as
 * the reference's), plus the root's `manifest.json`, `clock.json` and its `trash/` and `auto-backups/` listings. At the
 * end, when nothing waited, the whole data root's fingerprint.
 *
 * Rules (recorded in the P4 journal; no byte is ever relaxed):
 * - **Waiting, not failing.** A step this server does not implement yet (it logs `not_implemented`) waits, counted by
 *   the port group of its opcode. From then on its connection's and its character's later steps wait too (their state
 *   has diverged), and so
 *   does every database or root file the reference changed in that step: the world or the registry diverging makes
 *   every later game step (resp. every later step) wait. A step on a login connection that lists characters waits
 *   while anything has diverged. A waiting step also diverges `clock.json` (the device clock's high-water mark moved in
 *   the reference's memory during that step).
 * - **A failure diverges what it touched** (the character, the differing databases), so one defect is reported once
 *   and not as a cascade.
 * - **Exclusions.** Every frame is compared; a difference only in an excluded opcode (heartbeat S8, clock S14,
 *   broadcast S768 — the reference's own replay exclusions) is counted apart and does not fail the step.
 */
class RecordingRunner(
    private val apk: Path,
    private val releaseData: Path,
    private val driver: SqlDriver = JdbcSqlDriver(),
    /** How a `start` step makes the service (tests); by default release mode on the root with the APK and release data. */
    private val starter: ((root: Path, log: ServiceLog) -> Service)? = null,
) {
    private var tables: GameTables? = null

    private class Conn(val kind: String, val session: Session) {
        var open = true
    }

    private class Tally {
        var passed = 0
        var failed = 0
        val failures = JArr()
        fun pass() { passed++ }
        fun fail(detail: JObj) {
            failed++
            if (failures.size < 30) failures.add(detail)
        }
        fun json() = jobj("passed" to passed, "failed" to failed, "failures" to failures)
    }

    fun run(bundleDir: Path, workDir: Path): JObj {
        val bundle = Json.loads(Files.readAllBytes(bundleDir.resolve("bundle.json"))).asObj
        require(bundle.str("profile") == "openknights_fixture_bundle_v2") { "not a recording (bundle v2)" }
        val name = bundle.str("name")
        val manifestSha = sha256Hex(Files.readAllBytes(releaseData.resolve("MANIFEST.json")))
        val recordedManifest = bundle.obj("reference").str("release_data_manifest_sha256")
        if (manifestSha != recordedManifest) {
            return jobj("bundle" to name, "profile" to "v2", "passed" to false,
                "error" to "the release data differs from the recording's (MANIFEST.json $manifestSha vs $recordedManifest)")
        }
        val work = workDir.resolve(name)
        if (Files.exists(work)) deleteTree(work)
        val root = work.resolve("root")
        copyTree(bundleDir.resolve(bundle.obj("baseline").str("path")), root)

        val groups = bundle.obj("groups")
        val exclusions = bundle.arr("exclusions").map { it.asInt.toInt() }.toSet()
        val steps = bundle.arr("steps").map { it.asObj }
        val tape = TapeEntropy(bundle.arr("tape"))
        val gamePort = bundle.obj("environment").long("device_game_port").toInt()

        val replies = Tally()
        val databases = Tally()
        val files = Tally()
        val entropy = Tally()
        var excludedDifferences = 0
        val waitingByGroup = LinkedHashMap<String, Int>()
        val waitingByReason = LinkedHashMap<String, Int>()
        var compared = 0

        // expected state: the baseline + every recorded change; divergence as described above
        val expectedDb = LinkedHashMap<String, JObj?>()
        bundle.obj("baseline").obj("databases").forEach { (k, v) -> expectedDb[k] = v.asObj }
        val expectedFiles = LinkedHashMap<String, JValue>()
        val divergedDbs = HashSet<String>()
        val divergedFiles = HashSet<String>()
        val divergedCharacters = HashSet<String>()
        val divergedConns = HashSet<Int>()
        var worldDiverged = false
        var registryDiverged = false

        val rowDeltas = LinkedHashMap<String, MutableList<JObj>>()
        val pristine = bundleDir.resolve(bundle.obj("baseline").str("path"))
        val scanner = DbScanner(root, driver)
        val baselineMine = scanner.scan(force = true)
        for ((path, tablesNow) in baselineMine) {
            val expected = expectedDb[path]
            if (tablesNow != expected) databases.fail(jobj("step" to -1, "database" to path, "check" to "baseline read",
                "differing_tables" to differingTables(expected, tablesNow)))
            else databases.pass()
        }
        var fileState = rootFiles(root)
        val recordedBaselineFiles = bundle.obj("baseline")["files"] as? JObj
        fileState.forEach { (k, v) ->
            val recorded = recordedBaselineFiles?.get(k)
            if (recorded != null && recorded != v) files.fail(jobj("step" to -1, "file" to k, "check" to "baseline read",
                "recorded" to recorded.toString().take(300), "actual" to v.toString().take(300)))
            expectedFiles[k] = recorded ?: v
        }

        val log = ServiceLog(echo = false).also { it.keep = true }
        val conns = LinkedHashMap<Int, Conn>()
        var service: Service? = null
        val pushes = ArrayList<Triple<Int, Int, String>>()

        val savedSource = Now.source
        val savedOffset = Now.offsetSource
        val savedEntropy = Entropy.current
        var pinnedAt = 0.0
        var pinnedOffset = 0
        Now.source = { pinnedAt }
        Now.offsetSource = { pinnedOffset }
        Entropy.current = tape

        fun ownerOf(dbPath: String): String = when {
            dbPath.endsWith("/registry.sqlite3") -> "registry"
            dbPath.endsWith("/world.sqlite3") || dbPath.endsWith("/bots.sqlite3") -> "world"
            dbPath.contains("/characters/") -> "character:" + dbPath.substringAfter("/characters/").substringBefore('/')
            else -> "other"
        }

        fun diverge(dbPaths: Collection<String>) {
            for (p in dbPaths) {
                divergedDbs.add(p)
                when (val owner = ownerOf(p)) {
                    "registry" -> registryDiverged = true
                    "world" -> worldDiverged = true
                    else -> if (owner.startsWith("character:")) divergedCharacters.add(owner.removePrefix("character:"))
                }
            }
        }

        fun closeConn(conn: Conn) {
            conn.open = false
            service?.liveGameSessions?.remove(conn.session)
            conn.session.disconnected()
        }

        try {
            for (step in steps) {
                val index = step.long("i").toInt()
                val kind = step.str("kind")
                pinnedAt = (step["at"] as? JFloat)?.value ?: step.long("at").toDouble()
                pinnedOffset = step.long("offset").toInt()
                val entropyRange = step.arr("entropy").map { it.asInt.toInt() }
                tape.cursor = entropyRange[0]
                val logFrom = log.events.size
                pushes.clear()
                val connId = (step["conn"] as? JInt)?.toInt()
                val conn = connId?.let { conns[it] }
                val characterBefore = conn?.session?.characterId

                // --- run the step on this server ---
                var generated: List<Pair<Int, String>> = emptyList()
                var httpResponse: AuthGateway.Response? = null
                var error: String? = null
                var tapeError: String? = null
                try {
                    when (kind) {
                        "start" -> {
                            service = starter?.invoke(root, log) ?: Service.release(driver, root, apk, releaseData, log, tables)
                            tables = service!!.tables
                        }
                        "stop" -> {
                            conns.values.filter { it.open }.forEach { closeConn(it) }
                            service?.close()
                            service = null
                        }
                        "open" -> {
                            val session = Session(service!!, step.str("service"), gamePort)
                            val id = connId!!
                            conns[id] = Conn(step.str("service"), session)
                            if (session.kind == "game") {
                                service!!.liveGameSessions[session] = { frames -> frames.forEach { (op, data) -> pushes.add(Triple(id, op, data.toHexString())) } }
                            }
                        }
                        "close" -> closeConn(conn!!)
                        "frame" -> {
                            val out = conn!!.session.handle(step.long("op").toInt(), step.str("payload").hexBytes())
                            generated = out.map { it.first to it.second.toHexString() }
                            if (conn.session.closed && conn.open) closeConn(conn)
                        }
                        "http" -> httpResponse = AuthGateway.respond(service!!, step.str("path"), step["body"] ?: JNull)
                        "admin" -> log.log("not_implemented", "service" to "admin", "feature" to "save management: ${step.str("operation")}")
                        else -> error("unknown step kind $kind")
                    }
                } catch (e: TapeMismatch) {
                    tapeError = e.message
                } catch (e: Throwable) {
                    error = "${e.javaClass.simpleName}: ${e.message} @ " + e.stackTrace.take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
                }
                val newEvents = log.events.subList(logFrom, log.events.size)
                val unported = newEvents.filter { it.strOrNull("event") == "not_implemented" }
                val character = conn?.session?.characterId ?: characterBefore

                // --- state after the step: databases and root files on both sides ---
                val recordedDb = step.obj("db")
                (step["rows"] as? JObj)?.forEach { (db, tablesDelta) ->
                    tablesDelta.asObj.forEach { (table, delta) -> rowDeltas.getOrPut("$db|$table") { ArrayList() }.add(delta.asObj) }
                }
                recordedDb.forEach { (db, delta) -> if (delta == JNull) rowDeltas.keys.removeIf { it.startsWith("$db|") } }
                recordedDb.keys.forEach { scanner.refresh(it) }
                val mine = scanner.scan()
                recordedDb.forEach { (path, delta) ->
                    if (delta == JNull) expectedDb[path] = null
                    else {
                        val merged = LinkedHashMap(expectedDb[path]?.map ?: LinkedHashMap())
                        delta.asObj.forEach { (t, h) -> if (h == JNull) merged.remove(t) else merged[t] = h }
                        expectedDb[path] = JObj(merged)
                    }
                }
                val touchedDbs = (mine.keys + recordedDb.keys).toSortedSet()
                val recordedFiles = step.obj("files")
                recordedFiles.forEach { (k, v) -> expectedFiles[k] = v }
                val filesNow = rootFiles(root)
                val touchedFiles = (recordedFiles.keys + filesNow.keys.filter { filesNow[it] != fileState[it] }).toSortedSet()
                fileState = filesNow

                // --- wait or compare ---
                val group = when (kind) {
                    "frame" -> (groups[step.long("op").toString()] as? JInt)?.let { "group ${it.value}" } ?: "unrouted"
                    "http" -> if (step.str("path") == "/api/recharge") "group 4" else "group 1"
                    else -> "group 1"
                }
                val op = (step["op"] as? JInt)?.toInt()
                val listing = conn?.kind == "login" && op in setOf(7713, 7715)
                val reason = when {
                    registryDiverged && kind != "stop" -> "after the registry diverged"
                    connId != null && connId in divergedConns -> "connection diverged earlier"
                    character != null && character in divergedCharacters -> "character diverged earlier"
                    worldDiverged && (conn?.kind == "game" || kind == "http") -> "after the world diverged"
                    listing && (divergedCharacters.isNotEmpty() || worldDiverged) -> "character list after a divergence"
                    unported.isNotEmpty() -> "not ported yet: " + unported.first().strOrNull("feature").orEmpty().substringBefore(" (")
                    else -> null
                }
                if (reason != null) {
                    waitingByGroup[group] = (waitingByGroup[group] ?: 0) + 1
                    val label = if (reason.startsWith("not ported yet")) "not ported yet" else reason
                    waitingByReason[label] = (waitingByReason[label] ?: 0) + 1
                    diverge(touchedDbs)
                    divergedFiles.addAll(touchedFiles)
                    // the reference's device clock moved its in-memory high-water mark in this step; this server's did not
                    divergedFiles.add("clock.json")
                    if (character != null && unported.isNotEmpty()) divergedCharacters.add(character)
                    if (connId != null) divergedConns.add(connId)
                    continue
                }
                compared++
                var stepFailed = false
                fun fail(check: String, vararg detail: Pair<String, Any?>) {
                    stepFailed = true
                    replies.fail(jobj("step" to index, "kind" to kind, "op" to op, "check" to check).also { o -> detail.forEach { (k, v) -> o[k] = io.github.okexodus.openknights.exact.jvalue(v) } })
                }
                if (error != null) fail("this server raised", "error" to error)
                if (tapeError != null) {
                    entropy.fail(jobj("step" to index, "kind" to kind, "op" to op, "error" to tapeError)); stepFailed = true
                } else if (tape.cursor != entropyRange[1]) {
                    entropy.fail(jobj("step" to index, "kind" to kind, "op" to op, "draws" to (tape.cursor - entropyRange[0]),
                        "expected_draws" to (entropyRange[1] - entropyRange[0]))); stepFailed = true
                } else entropy.pass()
                when (kind) {
                    "frame" -> {
                        val recorded = step.arr("replies").map { it.asArr[0].asInt.toInt() to it.asArr[1].asStr }
                        if (recorded != generated) {
                            val strip = { l: List<Pair<Int, String>> -> l.filter { it.first !in exclusions } }
                            if (strip(recorded) == strip(generated)) excludedDifferences++
                            else {
                                val first = recorded.indices.firstOrNull { it >= generated.size || generated[it] != recorded[it] } ?: generated.size.coerceAtMost(recorded.size)
                                fail("reply frames", "recorded_opcodes" to recorded.map { it.first }, "opcodes" to generated.map { it.first },
                                    "first_difference" to first,
                                    "recorded_frame" to recorded.getOrNull(first)?.let { "${it.first}:${it.second.take(300)}" },
                                    "frame" to generated.getOrNull(first)?.let { "${it.first}:${it.second.take(300)}" })
                            }
                        }
                        val closed = (step["closed"] as? JBool)?.value ?: false
                        if (closed != conn!!.session.closed) fail("connection closed", "recorded" to closed, "closed" to conn.session.closed)
                    }
                    "http" -> {
                        val status = step.long("status").toInt()
                        val body = step.str("response")
                        if (httpResponse == null || httpResponse.status != status || httpResponse.body != body) {
                            fail("http response", "recorded" to "$status $body", "response" to httpResponse?.let { "${it.status} ${it.body}" })
                        }
                    }
                }
                val recordedPushes = step.arr("pushes").map { Triple(it.asArr[0].asInt.toInt(), it.asArr[1].asInt.toInt(), it.asArr[2].asStr) }
                if (recordedPushes != pushes) {
                    fail("pushed frames", "recorded" to recordedPushes.map { "${it.first}:${it.second}" }, "pushed" to pushes.map { "${it.first}:${it.second}" })
                }
                // databases (the step's own and every one this server changed)
                val differing = ArrayList<String>()
                for (path in touchedDbs) {
                    if (path in divergedDbs) continue
                    val expected = expectedDb[path]
                    val actual = scanner.current(path)
                    if (expected != actual) {
                        differing.add(path)
                        val tablesDiffering = differingTables(expected, actual)
                        databases.fail(jobj("step" to index, "kind" to kind, "op" to op, "database" to path,
                            "differing_tables" to tablesDiffering,
                            "first_rows" to firstRowDifferences(path, tablesDiffering, pristine, root, rowDeltas)))
                    } else databases.pass()
                }
                for (key in touchedFiles) {
                    if (key in divergedFiles) continue
                    if (expectedFiles[key] != fileState[key]) {
                        files.fail(jobj("step" to index, "file" to key, "recorded" to (expectedFiles[key] ?: JNull).toString().take(300),
                            "actual" to (fileState[key] ?: JNull).toString().take(300)))
                        divergedFiles.add(key)
                        stepFailed = true
                    } else files.pass()
                }
                if (differing.isNotEmpty()) { diverge(differing); stepFailed = true }
                if (stepFailed) {
                    if (character != null) divergedCharacters.add(character)
                    if (connId != null) divergedConns.add(connId)
                } else replies.pass()
            }
        } finally {
            Now.source = savedSource
            Now.offsetSource = savedOffset
            Entropy.current = savedEntropy
            try { service?.close() } catch (_: Throwable) {}
        }

        val nothingWaited = waitingByGroup.isEmpty()
        val finalCheck: JValue = if (!nothingWaited) JStr("skipped: steps waited") else {
            val recorded = bundle["final"]
            val mine = try { Fingerprint.compute(DataRoot(root, driver), driver) } catch (e: Exception) { null }
            JBool(recorded != null && mine != null && Json.canonical(recorded) == Json.canonical(mine))
        }
        val allPassed = replies.failed == 0 && databases.failed == 0 && files.failed == 0 && entropy.failed == 0 &&
            (finalCheck !is JBool || finalCheck.value)
        return jobj("bundle" to name, "profile" to "v2", "source" to bundle["source"], "steps" to steps.size,
            "compared_steps" to compared, "step_checks" to replies.json(), "databases" to databases.json(), "files" to files.json(),
            "entropy" to entropy.json(), "excluded_differences" to excludedDifferences,
            "waiting" to jobj("steps" to waitingByGroup.values.sum(),
                "by_port_group" to JObj(LinkedHashMap(waitingByGroup.toSortedMap().mapValues { JInt(it.value) })),
                "by_reason" to JObj(LinkedHashMap(waitingByReason.mapValues { JInt(it.value) }))),
            "final_fingerprint_equal" to finalCheck, "passed" to allPassed)
    }

    /** Rows of a table in rowid order, valued as the fingerprint writes them ({rowid: [value, ...]}), and its columns. */
    private fun tableRows(db: Path, table: String): Pair<JArr, LinkedHashMap<Long, JArr>>? {
        if (!Files.isRegularFile(db)) return null
        return driver.open(db, io.github.okexodus.openknights.server.store.SqlDriver.Mode.READ_ONLY).use { c ->
            if (!c.tableExists(table)) return@use null
            val columns = JArr(c.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", table).mapTo(ArrayList()) { JStr(it.string("name")) })
            val rows = LinkedHashMap<Long, JArr>()
            for (row in c.query("SELECT rowid AS \"_rowid_\", * FROM \"$table\" ORDER BY rowid")) {
                rows[row.long("_rowid_")] = JArr((1 until row.values.size).mapTo(ArrayList()) { i ->
                    when (val v = row.values[i]) {
                        null -> JNull
                        is Long -> JInt(v)
                        is String -> JStr(v)
                        is Double -> JFloat(v)
                        is ByteArray -> jobj("blob" to v.toHexString())
                        else -> JStr(v.toString())
                    }
                })
            }
            columns to rows
        }
    }

    /** For each differing table: the first row whose expected value (pristine baseline + recorded deltas) differs. */
    private fun firstRowDifferences(db: String, tables: JValue, pristine: Path, root: Path, deltas: Map<String, List<JObj>>): JValue {
        val names = (tables as? JArr)?.map { (it as JStr).value } ?: return JNull
        val out = JObj()
        for (table in names.take(4)) {
            val expected = LinkedHashMap(tableRows(pristine.resolve(db), table)?.second ?: LinkedHashMap())
            for (delta in deltas["$db|$table"] ?: emptyList()) {
                delta.arr("delete").forEach { expected.remove(it.asInt.toLong()) }
                delta.arr("upsert").forEach { r -> val a = r.asArr; expected[a[0].asInt.toLong()] = JArr(a.drop(1).toMutableList()) }
            }
            val actual = tableRows(root.resolve(db), table)?.second ?: LinkedHashMap()
            val rowid = (expected.keys + actual.keys).toSortedSet().firstOrNull { expected[it] != actual[it] }
            out[table] = if (rowid == null) JStr("rows equal (column layout or order differs)") else jobj("rowid" to rowid,
                "expected" to (expected[rowid]?.let { Json.dumps(it).take(1500) } ?: "absent"),
                "actual" to (actual[rowid]?.let { Json.dumps(it).take(1500) } ?: "absent"))
        }
        return out
    }

    private fun differingTables(expected: JObj?, actual: JObj?): JValue {
        if (expected == null || actual == null) return JStr(if (expected == null) "database not expected" else "database missing")
        val names = (expected.keys + actual.keys).toSortedSet()
        return JArr(names.filter { expected[it] != actual[it] }.mapTo(ArrayList()) { JStr(it) })
    }

    /**
     * The root's files as the recorder keeps them: `manifest.json` / `clock.json` parsed; `trash/`, `auto-backups/` and
     * `exports/` as {name: logical content} ([contentOf]).
     */
    private fun rootFiles(root: Path): Map<String, JValue> {
        val out = LinkedHashMap<String, JValue>()
        for (name in listOf("manifest.json", "clock.json")) {
            val p = root.resolve(name)
            out[name] = if (Files.isRegularFile(p)) Json.loads(Files.readAllBytes(p)) else JNull
        }
        for (name in listOf("trash", "auto-backups", "exports")) {
            val p = root.resolve(name)
            out[name] = if (Files.isDirectory(p)) JObj().also { o ->
                Files.list(p).use { s -> s.sorted().toList() }.forEach { o[it.fileName.toString()] = content(it) }
            } else JNull
        }
        return out
    }

    private val contentCache = HashMap<List<Any>, JValue>()

    private fun content(path: Path): JValue {
        val key = listOf(path.toString(), if (Files.isDirectory(path)) -1L else Files.size(path),
            Files.getLastModifiedTime(path).to(java.util.concurrent.TimeUnit.NANOSECONDS))
        if (Files.isDirectory(path)) return contentOf(path)      // a folder's members can change inside it
        return contentCache.getOrPut(key) { contentOf(path) }
    }

    private fun tablesOf(path: Path): JObj = JObj().also { o ->
        Fingerprint.database(path, driver).obj("tables").forEach { (t, v) -> o[t] = JStr(v.asObj.str("sha256")) }
    }

    /**
     * The logical content of a backup / trash entry (the recorder's `content_of`): raw zip and SQLite bytes are
     * implementation-specific, so a database is its table hashes and a `.okbackup` its manifest (without the raw sizes
     * and hashes of its databases) plus the logical content of every member; other JSON parsed, anything else SHA-256.
     */
    private fun contentOf(path: Path): JValue {
        val name = path.fileName.toString()
        if (Files.isDirectory(path)) {
            val folder = JObj()
            Files.walk(path).use { s -> s.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".lock") }.toList() }
                .forEach { folder[path.relativize(it).joinToString("/")] = contentOf(it) }
            return jobj("folder" to folder)
        }
        if (name.endsWith(".sqlite3")) return jobj("database" to tablesOf(path))
        if (name.endsWith(".okbackup")) {
            return try {
                java.util.zip.ZipFile(path.toFile()).use { zip ->
                    val manifest = Json.loads(zip.getInputStream(zip.getEntry("manifest.json") ?: throw java.util.zip.ZipException("no manifest")).readAllBytes()).asObj
                    val members = JObj()
                    val tmp = Files.createTempDirectory("openknights-okbackup-")
                    try {
                        for (entry in zip.entries().toList().sortedBy { it.name }) {
                            if (entry.name == "manifest.json") continue
                            val data = zip.getInputStream(entry).readAllBytes()
                            members[entry.name] = when {
                                entry.name.endsWith(".sqlite3") -> {
                                    val q = tmp.resolve("m${members.size}.sqlite3")
                                    Files.write(q, data)
                                    jobj("database" to tablesOf(q))
                                }
                                entry.name.endsWith(".json") -> jobj("json" to Json.loads(data))
                                else -> jobj("sha256" to sha256Hex(data))
                            }
                        }
                    } finally {
                        deleteTree(tmp)
                    }
                    (manifest["files"] as? JArr)?.forEach { f ->
                        val o = f.asObj
                        if (o.strOrNull("path")?.endsWith(".sqlite3") == true) { o.remove("bytes"); o.remove("sha256") }
                    }
                    jobj("okbackup" to jobj("manifest" to manifest, "members" to members))
                }
            } catch (e: Exception) {
                jobj("unreadable" to true)
            }
        }
        if (name.endsWith(".json")) {
            try { return jobj("json" to Json.loads(Files.readAllBytes(path))) } catch (_: Exception) {}
        }
        return jobj("sha256" to sha256Hex(Files.readAllBytes(path)))
    }

    /** Table hashes of every database under `worlds/`, re-read only when the file (or its journal) changed. */
    private class DbScanner(private val root: Path, private val driver: SqlDriver) {
        private val state = LinkedHashMap<String, Pair<List<Any>, JObj>>()

        fun current(path: String): JObj? = state[path]?.second

        /** Forget what is known of [path] so the next scan reads it again. */
        fun refresh(path: String) { state[path]?.let { state[path] = emptyList<Any>() to it.second } }

        /** The databases whose logical content changed since the last scan (a vanished one maps to null). */
        fun scan(force: Boolean = false): Map<String, JObj?> {
            val changed = LinkedHashMap<String, JObj?>()
            val seen = HashSet<String>()
            val worlds = root.resolve("worlds")
            val files = if (Files.isDirectory(worlds)) Files.walk(worlds).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sqlite3") }.toList()
            } else emptyList()
            for (file in files.sortedBy { root.relativize(it).joinToString("/") }) {
                val rel = root.relativize(file).joinToString("/")
                if (rel.startsWith(".") || rel.contains("/.")) continue
                seen.add(rel)
                val key = statKey(file)
                val old = state[rel]
                if (!force && old != null && old.first == key) continue
                val tables = JObj()
                Fingerprint.database(file, driver).obj("tables").forEach { (t, v) -> tables[t] = JStr(v.asObj.str("sha256")) }
                state[rel] = key to tables
                if (old == null || old.second != tables) changed[rel] = tables
            }
            for (rel in state.keys.toList()) if (rel !in seen) { state.remove(rel); changed[rel] = null }
            return changed
        }

        private fun statKey(file: Path): List<Any> = listOf("", "-journal", "-wal").mapNotNull { suffix ->
            val p = file.resolveSibling(file.fileName.toString() + suffix)
            if (Files.exists(p)) listOf(suffix, Files.size(p), Files.getLastModifiedTime(p).to(java.util.concurrent.TimeUnit.NANOSECONDS)) else null
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

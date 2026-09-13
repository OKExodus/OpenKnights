package io.github.okexodus.openknights.server.pc.harness

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JInt
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JStr
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.asArr
import io.github.okexodus.openknights.exact.asInt
import io.github.okexodus.openknights.exact.asObj
import io.github.okexodus.openknights.exact.asStr
import io.github.okexodus.openknights.exact.hexBytes
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.protocol.BattleReport
import io.github.okexodus.openknights.protocol.Frames
import io.github.okexodus.openknights.protocol.Inventory
import io.github.okexodus.openknights.protocol.PlayerSections
import io.github.okexodus.openknights.protocol.PlayerState
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.session.CharacterSelect
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.ServiceLog
import io.github.okexodus.openknights.server.session.Session
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.JdbcSqlDriver
import io.github.okexodus.openknights.server.store.LocalAuth
import io.github.okexodus.openknights.server.store.SqlDriver
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * The differential harness: replays a fixture bundle (a recorded session of the reference server and the state it
 * started from) against this server and compares what this server covers today.
 *
 * A bundle is a folder with `bundle.json` (profile `openknights_fixture_bundle_v1`) and `baseline/` (a release data
 * root, or a development state folder). `bundle.json` holds the service settings of the run, the sign-in sessions issued
 * during it, every login and game connection with its frames in capture order (`[c|s, opcode, timestamp, payload
 * hex]`), the committed save revisions, the characters created, the port group (1..9) of each client opcode and the
 * reply opcodes that are never compared (heartbeat, clock, broadcasts).
 *
 * Covered now, and compared byte for byte: every login hop whose inputs are the baseline's (before the run's first
 * committed revision or creation) — sign-in, the character list, the choice of a row — and, on the game hop, whether
 * C3 authenticates exactly when the reference let the client in. Every frame is also decoded and re-encoded where the
 * protocol module knows its kind. Everything after the entry is listed as waiting, by port group.
 */
class BundleRunner(private val driver: SqlDriver = JdbcSqlDriver()) {
    private class Pinned(var now: Long = 0)

    fun run(bundleDir: Path, workDir: Path): JObj {
        val bundle = Json.loads(Files.readAllBytes(bundleDir.resolve("bundle.json"))).asObj
        require(bundle.str("profile") == "openknights_fixture_bundle_v1") { "not a fixture bundle" }
        val work = workDir.resolve(bundle.str("name"))
        if (Files.exists(work)) deleteTree(work)
        copyTree(bundleDir.resolve("baseline"), work.resolve("baseline"))
        val baseline = bundle.obj("baseline")
        val settings = bundle.obj("service")
        val exclusions = bundle.arr("exclusions").map { it.asInt.toInt() }.toSet()
        val groups = bundle.obj("groups")
        val clock = Pinned()
        val offset = (bundle.obj("clock").arr("offsets").firstOrNull()?.asArr?.get(1) as? JInt)?.value?.toInt() ?: 0
        val log = ServiceLog(echo = false).also { it.keep = true }

        // --- the service as it was at the start of the run ---
        val root = work.resolve("baseline").resolve(baseline.str("path").removePrefix("baseline/"))
        val registryPath = root.resolve(baseline.str("registry"))
        val release = baseline.str("layout") == "release_data_root"
        val dataRoot = if (release) DataRoot(root, driver) else null
        val registry = AccountRegistry(registryPath, driver, strictPaths = release)
        insertSessions(registry, bundle.arr("sessions"))
        val auth = LocalAuth(registry) { clock.now }.also { it.deviceOwner = settings.str("device_owner") }
        val worldPath = (baseline["world"] as? JStr)?.value?.let { root.resolve(it) }
        val world = worldPath?.takeIf { Files.exists(it) }?.let { WorldDirectory(it, driver, strictPaths = release) }
        val rowIds = settings.arr("row_ids").map { it.asInt.toInt() }
        val select = CharacterSelect(settings.arr("offers").map { it.asInt.toLong() }, rowIds,
            (settings["announcement"] as? JStr)?.value ?: "", settings.str("create_row_label")) { clock.now }
        val empty = GameTables(object : TableSource {
            override fun names() = emptyList<String>()
            override fun raw(name: String) = throw NoSuchElementException(name)
        })
        val service = Service(driver, log, DeviceClock(null, { clock.now }, { offset }), empty, null, auth, world, select, dataRoot,
            registryPath.parent, (settings["captured_wire_account_id"] as? JInt)?.value?.toLong())
        service.creationEnabled = (settings["create_enabled"] as? io.github.okexodus.openknights.exact.JBool)?.value ?: true
        val gamePort = settings.long("device_game_port").toInt()

        // --- the run's first change of state: after it, the character list depends on the game systems (P4) ---
        val changes = bundle.arr("revisions").map { epoch(it.asObj.str("timestamp_utc")) } +
            bundle.arr("created").map { epoch(it.asObj.str("registered_at_utc")) }
        val firstChange = changes.minOrNull() ?: Double.MAX_VALUE

        val routedWires = HashSet<Long>()
        val login = Tally()
        val gameAuth = Tally()
        val codec = Tally()
        val waiting = LinkedHashMap<String, Int>()
        val waitingReasons = LinkedHashMap<String, Int>()
        fun wait(reason: String, opcode: Int) {
            val group = (groups[opcode.toString()] as? JInt)?.value?.toInt()?.let { "group $it" } ?: "unrouted"
            waiting[group] = (waiting[group] ?: 0) + 1
            waitingReasons[reason] = (waitingReasons[reason] ?: 0) + 1
        }

        for (c in bundle.arr("connections")) {
            val conn = c.asObj
            val kind = conn.str("service")
            val frames = conn.arr("frames").map { it.asArr }
            frames.forEach { codec(codec, kind, it) }
            val session = Session(service, kind, gamePort)
            if (frames.none { it[0].asStr == "s" }) {
                // No reply at all in the capture (e.g. the client knocked while the service was stopped): nothing to compare.
                frames.filter { it[0].asStr == "c" }.forEach { waitingReasons["unanswered in the capture"] = (waitingReasons["unanswered in the capture"] ?: 0) + 1 }
                continue
            }
            // Requests sent together before any reply form one group; its replies are the group's (pipelined requests).
            val groupsOfFrames = ArrayList<Pair<List<JArr>, List<Pair<Int, String>>>>()
            var index = 0
            while (index < frames.size) {
                val requests = ArrayList<JArr>()
                while (index < frames.size && frames[index][0].asStr == "c") requests.add(frames[index++])
                val recorded = ArrayList<Pair<Int, String>>()
                while (index < frames.size && frames[index][0].asStr == "s") {
                    val op = frames[index][1].asInt.toInt()
                    if (op !in exclusions) recorded.add(op to frames[index][3].asStr)
                    index++
                }
                if (requests.isNotEmpty()) groupsOfFrames.add(requests to recorded)
            }
            var entered = false
            for ((requests, recorded) in groupsOfFrames) {
                val generated = ArrayList<Pair<Int, String>>()
                val compared = ArrayList<Int>()
                for (frame in requests) {
                    val opcode = frame[1].asInt.toInt()
                    // The service's own time of the request when the bundle has it (the capture clock is the device's).
                    val stamp = if (frame.size > 4) frame[4] else frame[2]
                    val ts = (stamp as? io.github.okexodus.openknights.exact.JFloat)?.value ?: stamp.asInt.toDouble()
                    val payload = frame[3].asStr.hexBytes()
                    clock.now = ts.toLong()
                    if (opcode == 7) continue      // heartbeat: its replies are never compared
                    if (kind == "login") {
                        if (ts >= firstChange) { wait("login hop after the run's first change of state (character state from the game systems)", opcode); continue }
                        val replies = session.handle(opcode, payload).filter { it.first !in exclusions }.map { it.first to it.second.toHexString() }
                        generated.addAll(replies)
                        compared.add(opcode)
                        replies.firstOrNull { it.first == 7714 }?.second?.hexBytes()?.let { routed ->
                            if (routed.size > 1 && routed[0] == 0.toByte()) {
                                val r = io.github.okexodus.openknights.protocol.WireReader(routed)
                                r.u8(); r.cstringBytes(); r.u32()
                                routedWires.add(r.u32())
                            }
                        }
                    } else if (!entered && opcode == 3 && payload.size >= 4 &&
                        io.github.okexodus.openknights.protocol.WireReader(payload).u32().let { wire ->
                            wire !in routedWires && (service.world?.characters()?.none { it.long("wire_account_id") == wire } ?: true)
                        }) {
                        // A character created during the run, or a creation ticket of a hop that itself waits: P4 group 1.
                        entered = true
                        wait("game hop of a character created during the run", opcode)
                    } else if (!entered && opcode == 3) {
                        // C3: this server must let the client in exactly when the reference did (the entry itself is P4).
                        val before = log.events.size
                        session.handle(opcode, payload)
                        val feature = log.events.drop(before).lastOrNull { it.str("event") == "not_implemented" }?.strOrNull("feature") ?: ""
                        val mine = when {
                            feature.startsWith("entering the game") -> "entered"
                            feature.startsWith("character creation") -> "creation"
                            else -> "refused"
                        }
                        val first = recorded.firstOrNull()
                        val reference = when {
                            first == null -> "refused"
                            first.first == 18 && first.second.length == 10 -> "creation"     // S18 mode 1 (5 bytes): the create dialog
                            first.first == 14 || first.first == 18 -> "entered"
                            else -> "refused"
                        }
                        gameAuth.check(reference, mine, "game c3 at ${Instant.ofEpochMilli((ts * 1000).toLong())}")
                        entered = true
                        wait(if (reference == "creation") "character creation" else "entering the game", opcode)
                    } else {
                        wait("game request after the entry", opcode)
                    }
                }
                if (kind == "login" && compared.isNotEmpty()) {
                    val at = requests.first().let { if (it.size > 4) it[4] else it[2] }
                    // The client may close the connection before a pipelined reply reaches the capture: the extra frames
                    // generated here pass only when the service log confirms the reference sent exactly those opcodes.
                    val logged = requests.flatMap { r -> (if (r.size > 5) r[5].asArr.map { it.asInt.toInt() } else emptyList()) }
                        .filter { it !in exclusions }
                    val tail = generated.drop(recorded.size).map { it.first }
                    val confirmedTail = recorded.size < generated.size && generated.take(recorded.size) == recorded &&
                        logged.takeLast(tail.size) == tail && logged.size == generated.size
                    if (confirmedTail) {
                        login.tolerated++
                        login.passed++
                    } else login.check(recorded, generated, "login c${compared.joinToString("+c")} at $at")
                }
            }
        }
        val report = jobj("bundle" to bundle.str("name"), "kind" to bundle.str("kind"), "mode" to bundle.str("mode"),
            "covered" to jobj("login_hop" to login.json(), "game_authentication" to gameAuth.json(), "codec" to codec.json()),
            "waiting" to jobj("client_requests" to waiting.values.sum(), "by_port_group" to JObj(LinkedHashMap(waiting.mapValues { JInt(it.value) })),
                "by_reason" to JObj(LinkedHashMap(waitingReasons.mapValues { JInt(it.value) }))),
            "passed" to (login.failed == 0 && gameAuth.failed == 0 && codec.failed == 0))
        dataRoot?.lock?.release()
        return report
    }

    private fun epoch(isoUtc: String): Double = Instant.parse(isoUtc.replace("+00:00", "Z")).toEpochMilli() / 1000.0

    /** The sign-in sessions of the run (their HTTP sign-in is not in the capture), as the reference's registry held them. */
    private fun insertSessions(registry: AccountRegistry, sessions: JArr) {
        registry.connect().use { db ->
            db.immediate {
                for (s in sessions) {
                    val row = s.asObj
                    if (db.queryOne("SELECT 1 FROM local_sessions WHERE session_id=? OR token_sha256=?", row.str("session_id"), row.str("token_sha256")) != null) continue
                    db.execute("INSERT INTO local_sessions VALUES(?,?,?,?,?,?,?,?)", row.str("session_id"), row.str("token_sha256"),
                        row.str("account_id"), (row["character_id"] as? JStr)?.value, row.long("issued_epoch"), row.long("ttl_seconds"),
                        row.long("expires_epoch"), null)
                }
            }
        }
    }

    private fun codec(tally: Tally, service: String, frame: JArr) {
        val side = frame[0].asStr
        val opcode = frame[1].asInt.toInt()
        val payload = frame[3].asStr.hexBytes()
        val ok = try {
            Frames.encode(opcode, payload)
            when {
                side == "s" && opcode == 18 -> PlayerState.encode(PlayerState.parse(payload)).contentEquals(payload)
                side == "s" && opcode == 4 -> BattleReport.encode(BattleReport.parse(payload)).contentEquals(payload)
                side == "s" && opcode == 64 -> Inventory.encode(Inventory.decode(payload)).contentEquals(payload)
                side == "s" && opcode == 1188 -> PlayerSections.readActivityUpdate(payload).let {
                    PlayerSections.encodeActivityUpdate(it.long("activity_id"), it.obj("rows")).contentEquals(payload)
                }
                (side == "c" && opcode == 129) || (side == "s" && opcode in setOf(160, 162, 164)) ->
                    BattleReport.parseCampaignPacket(opcode, payload).let { true }
                else -> null
            }
        } catch (e: IllegalArgumentException) { false }
        if (ok == null) tally.skipped++ else tally.check(listOf(true), listOf(ok), "$service $side$opcode")
    }

    private class Tally {
        var passed = 0
        var failed = 0
        var skipped = 0
        /** Passed with a labeled tolerance: reply frames missing from the capture, their opcodes confirmed by the service log. */
        var tolerated = 0
        val failures = JArr()

        fun check(expected: Any, actual: Any, label: String, agree: Boolean = expected == actual) {
            if (agree) passed++ else {
                failed++
                if (failures.size < 20) failures.add(jobj("check" to label, "expected" to expected.toString().take(300), "actual" to actual.toString().take(300)))
            }
        }

        fun json(): JObj = jobj("passed" to passed, "failed" to failed, "failures" to failures).also {
            if (skipped > 0) it["other_frames"] = JInt(skipped)
            if (tolerated > 0) it["tolerated_capture_tail"] = JInt(tolerated)
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

/** `gradlew :server-pc:runBundles`: every bundle under OPENKNIGHTS_BUNDLES; the report goes to build/bundle-reports. */
fun main(args: Array<String>) {
    val bundles = Path.of(args.getOrNull(0) ?: System.getenv("OPENKNIGHTS_BUNDLES") ?: error("give the bundles folder (or set OPENKNIGHTS_BUNDLES)"))
    val output = Path.of(args.getOrNull(1) ?: "build/bundle-reports")
    Files.createDirectories(output)
    val work = Files.createTempDirectory("openknights-bundles-")
    val reports = JArr()
    val folders = Files.list(bundles).use { s -> s.filter { Files.isRegularFile(it.resolve("bundle.json")) }.sorted().toList() }
    for (folder in folders) {
        val report = try { BundleRunner().run(folder, work) } catch (e: Exception) {
            jobj("bundle" to folder.fileName.toString(), "passed" to false, "error" to "${e.javaClass.simpleName}: ${e.message}")
        }
        reports.add(report)
        println(Json.dumps(report))
    }
    val summary = jobj("profile" to "openknights_bundle_report_v1", "bundles" to reports,
        "all_passed" to reports.all { (it.asObj["passed"] as? io.github.okexodus.openknights.exact.JBool)?.value == true })
    Files.writeString(output.resolve("report.json"), Json.dumps(summary, indent = 1))
    println("report: ${output.resolve("report.json")}")
}

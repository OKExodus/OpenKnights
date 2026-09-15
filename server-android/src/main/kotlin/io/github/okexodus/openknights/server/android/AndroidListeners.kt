package io.github.okexodus.openknights.server.android

import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.Now
import io.github.okexodus.openknights.protocol.FrameDecoder
import io.github.okexodus.openknights.protocol.Frames
import io.github.okexodus.openknights.server.session.AuthGateway
import io.github.okexodus.openknights.server.session.Service
import io.github.okexodus.openknights.server.session.Session
import io.github.okexodus.openknights.server.store.AuthenticationRejected
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The on-device transport: the same three loopback listeners as the PC [io.github.okexodus.openknights.server.pc]
 * `Listeners` (login 17777 and game 19121 speak the game framing; the sign-in page and API on 17778 speak HTTP/1.1),
 * differing only in the thread model — Android has no Java-21 virtual threads, so each accept loop and each connection
 * runs on a platform thread. Every request is still handled on ONE dispatcher thread in arrival order (the saves rely
 * on it). The sign-in HTML page is supplied by the host (read from the app's assets).
 */
class AndroidListeners(
    private val service: Service,
    private val signinPage: ByteArray,
    private val bind: InetAddress = InetAddress.getLoopbackAddress(),
    private val loginPort: Int = 17777,
    private val gamePort: Int = 19121,
    private val authPort: Int = 17778,
    private val deviceGamePort: Int = gamePort,
) : AutoCloseable {
    private val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "openknights-dispatcher").apply { isDaemon = true } }
    private val sockets = ArrayList<ServerSocket>()
    @Volatile private var running = true
    private val log = service.log

    init {
        require(bind.isLoopbackAddress) { "The server binds to the loopback address only" }
    }

    var boundAuthPort = authPort; private set
    var boundLoginPort = loginPort; private set
    var boundGamePort = gamePort; private set

    fun start(): AndroidListeners {
        sockets.add(listen(authPort) { socket -> http(socket) }.also { boundAuthPort = it.localPort })
        sockets.add(listen(loginPort) { socket -> connection(socket, "login") }.also { boundLoginPort = it.localPort })
        sockets.add(listen(gamePort) { socket -> connection(socket, "game") }.also { boundGamePort = it.localPort })
        return this
    }

    private fun listen(port: Int, handler: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(port, 50, bind)
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                Thread({ socket.use { handler(it) } }, "conn-$port").apply { isDaemon = true }.start()
            }
        }, "listen-$port").apply { isDaemon = true }.start()
        return server
    }

    private fun <T> onDispatcher(block: () -> T): T = try {
        dispatcher.submit(Callable { Now.pinned(System.currentTimeMillis() / 1000.0) { block() } }).get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    private fun write(out: OutputStream, frames: List<Pair<Int, ByteArray>>) {
        val buffer = ByteArrayOutputStream()
        for ((opcode, payload) in frames) buffer.write(Frames.encode(opcode, payload))
        synchronized(out) { out.write(buffer.toByteArray()); out.flush() }
    }

    private fun connection(socket: Socket, kind: String) {
        val session = Session(service, kind, deviceGamePort)
        val decoder = FrameDecoder()
        val input: InputStream = socket.getInputStream()
        val output: OutputStream = socket.getOutputStream()
        log.log("connected", "service" to kind)
        if (kind == "game") onDispatcher { service.liveGameSessions[session] = { frames -> write(output, frames) } }
        try {
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (frame in decoder.feed(buffer.copyOf(read))) {
                    log.log("request", "service" to kind, "opcode" to frame.opcode, "payload_bytes" to frame.payload.size)
                    val replies = onDispatcher { session.handle(frame.opcode, frame.payload) }
                    if (replies.isNotEmpty()) {
                        write(output, replies)
                        log.log("response_batch", "service" to kind, "opcodes" to replies.map { it.first }, "bytes" to replies.sumOf { it.second.size + 4 })
                    }
                    if (session.closed) return
                }
            }
            decoder.finish()
        } catch (e: Exception) {
            if (e !is IOException || running) log.log("session_error", "service" to kind, "error" to (e.message ?: e.javaClass.simpleName))
        } finally {
            onDispatcher {
                service.liveGameSessions.remove(session)
                session.disconnected()
            }
            log.log("disconnected", "service" to kind)
        }
    }

    // --- the sign-in page and API (auth_gateway.py) ------------------------------------------------------------------

    private fun readHeader(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (out.size() > 8192) throw IllegalArgumentException("Header too large")
        }
        return out.toString(Charsets.US_ASCII)
    }

    private fun http(socket: Socket) {
        socket.soTimeout = 10_000
        var status = 400
        var contentType = "application/json"
        var content = """{"error":"Invalid request"}""".toByteArray()
        try {
            val input = socket.getInputStream()
            val lines = readHeader(input).split("\r\n")
            val (method, path, version) = lines[0].split(" ").let { if (it.size == 3) Triple(it[0], it[1], it[2]) else throw IllegalArgumentException("request line") }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                require(colon > 0) { "header" }
                val name = line.substring(0, colon).lowercase()
                require(name !in headers) { "Duplicate header" }
                headers[name] = line.substring(colon + 1).trim()
            }
            val origin = "http://127.0.0.1:$boundAuthPort"
            require(headers["host"] == "127.0.0.1:$boundAuthPort" && (headers["origin"] ?: origin) == origin &&
                (headers["sec-fetch-site"] ?: "same-origin") in setOf("same-origin", "none") &&
                "transfer-encoding" !in headers && version == "HTTP/1.1") { "Only the local origin is accepted" }
            if (method == "GET" && path == "/" && (headers["content-length"] ?: "0").toInt() == 0) {
                status = 200; contentType = "text/html; charset=utf-8"; content = signinPage
            } else if (method == "POST" && path in AuthGateway.PATHS) {
                require(headers["content-type"] == "application/json") { "JSON required" }
                val size = (headers["content-length"] ?: "-1").toInt()
                require(size in 1..8192) { "Body size invalid" }
                val data = input.readNBytes(size)
                require(data.size == size) { "Body truncated" }
                val body = Json.loads(data)
                val response = onDispatcher { AuthGateway.respond(service, path, body) }
                status = response.status
                content = response.body.toByteArray()
                log.log("local_auth_http", "action" to path.substringAfterLast('/'), "accepted" to (status == 200))
            } else {
                status = 404; content = """{"error":"Not found"}""".toByteArray()
            }
        } catch (e: AuthenticationRejected) {
            status = 401; content = """{"error":"Credentials, session or character selection rejected"}""".toByteArray()
            log.log("local_auth_http", "accepted" to false)
        } catch (e: IllegalArgumentException) {
            log.log("local_auth_http", "accepted" to false, "malformed" to true)
        } catch (e: IOException) {
            log.log("local_auth_http", "accepted" to false, "malformed" to true)
        } catch (e: Exception) {
            status = 500; content = """{"error":"Local authentication is unavailable"}""".toByteArray()
            log.log("local_auth_http", "accepted" to false, "internal_error" to true)
        }
        try {
            val label = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 409 to "Conflict",
                500 to "Internal Server Error").getValue(status)
            val policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
            val head = "HTTP/1.1 $status $label\r\nContent-Type: $contentType\r\nContent-Length: ${content.size}\r\nCache-Control: no-store\r\n" +
                "Content-Security-Policy: $policy\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII) + content); flush() }
        } catch (_: IOException) {}
    }

    override fun close() {
        running = false
        sockets.forEach { try { it.close() } catch (_: IOException) {} }
        dispatcher.shutdown()
    }
}

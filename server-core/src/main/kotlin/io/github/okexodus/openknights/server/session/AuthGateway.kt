package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.server.store.AuthenticationRejected

/**
 * The sign-in gateway's API (`auth_gateway.dispatch` and the `/api/recharge` route): what a POST answers, as the exact
 * status and body the reference writes. The HTTP framing around it lives with the listeners.
 */
object AuthGateway {
    /** A JSON member as the reference's `body[key]` would pass it on: text stays text, anything else is not text. */
    private fun JObj.valueOf(key: String): Any? = when (val v = this[key]) {
        is io.github.okexodus.openknights.exact.JStr -> v.value
        null, io.github.okexodus.openknights.exact.JNull -> null
        else -> v
    }

    val PATHS = setOf("/api/login", "/api/device", "/api/recharge")

    class Response(val status: Int, val body: String)

    /** A refusal the recharge route answers with 409 and its text. */
    class Conflict(message: String) : IllegalArgumentException(message)

    fun respond(service: Service, path: String, body: JValue): Response = try {
        Response(200, Json.dumps(dispatch(service, path, body)))
    } catch (e: AuthenticationRejected) {
        Response(401, """{"error":"Credentials, session or character selection rejected"}""")
    } catch (e: Conflict) {
        Response(409, Json.dumps(jobj("error" to (e.message ?: ""))))
    } catch (e: io.github.okexodus.openknights.server.game.Acquisition.Rejected) {
        // Fixed local texts of the recharge planner (unknown pack, monthly card, no game session).
        Response(409, Json.dumps(jobj("error" to (e.message ?: ""))))
    } catch (e: NoSuchElementException) {
        Response(404, """{"error":"Not found"}""")
    } catch (e: IllegalArgumentException) {
        Response(400, """{"error":"Invalid request"}""")
    } catch (e: io.github.okexodus.openknights.server.game.NotPorted) {
        throw e
    } catch (e: Exception) {
        // Do not expose exception details, request bodies, paths, or credentials.
        Response(500, """{"error":"Local authentication is unavailable"}""")
    }

    fun dispatch(service: Service, path: String, body: JValue): JObj {
        if (path !in PATHS) throw NoSuchElementException(path)
        val request = body as? JObj ?: throw IllegalArgumentException("JSON object required")
        return when (path) {
            "/api/device" -> {
                require(request.isEmpty()) { "Unsupported authentication request" }
                val issued = service.auth.deviceLogin()
                jobj("token" to issued.token, "ingame_select" to true, "expires_at_utc" to issued.session.expiresAtUtc, "device" to true)
            }
            "/api/login" -> {
                require(request.keys == setOf("username", "password")) { "Unsupported authentication request" }
                val issued = service.auth.login(request.valueOf("username"), request.valueOf("password"))
                jobj("token" to issued.token, "ingame_select" to true, "expires_at_utc" to issued.session.expiresAtUtc)
            }
            else -> {
                // The free top-up: granted on the login's newest live game session, its frames pushed there (`deliver_recharge`).
                val token = request.strOrNull("token") ?: throw IllegalArgumentException("Recharge requires the session token")
                service.auth.authenticate(token)
                val target = service.liveGameSessions.entries.lastOrNull { (s, _) -> s.token == token && s.characterId != null && s.queriesSent && !s.closed }
                    ?: throw io.github.okexodus.openknights.server.game.Acquisition.Rejected("No active game session for this login")
                val (packets, plan) = target.key.recharge(request)
                target.value(packets)
                service.log.log("response_batch", "service" to "game", "opcodes" to packets.map { it.first }, "pushed" to "recharge",
                    "bytes" to packets.sumOf { it.second.size + 4 })
                jobj("delivered" to true, "goods_id" to plan["goods_id"], "diamonds" to plan["diamonds"], "vip_level" to plan["vip_level_after"])
            }
        }
    }
}

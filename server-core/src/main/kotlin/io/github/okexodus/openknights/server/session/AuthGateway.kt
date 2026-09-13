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
    } catch (e: NoSuchElementException) {
        Response(404, """{"error":"Not found"}""")
    } catch (e: IllegalArgumentException) {
        Response(400, """{"error":"Invalid request"}""")
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
                service.log.log("not_implemented", "service" to "http", "feature" to "password sign-in (/api/login)")
                throw AuthenticationRejected("Local credentials rejected")
            }
            else -> {
                val token = request.strOrNull("token") ?: throw IllegalArgumentException("Recharge requires the session token")
                service.auth.authenticate(token)
                service.log.log("not_implemented", "service" to "http", "feature" to "free top-up (/api/recharge)")
                throw Conflict("No active game session for this login")
            }
        }
    }
}

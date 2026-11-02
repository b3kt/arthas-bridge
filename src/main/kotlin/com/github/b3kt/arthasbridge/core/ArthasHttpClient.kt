package com.github.b3kt.arthasbridge.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class ArthasException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thin wrapper over the Arthas HTTP API (`POST /api`).
 * See https://arthas.aliyun.com/en/doc/http-api.html
 */
class ArthasHttpClient(val host: String, val port: Int, username: String? = null, password: String? = null) {

    private val baseUri = URI("http://$host:$port/api")
    private val authHeader = if (!username.isNullOrBlank() && password != null) {
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    } else null
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    /** One-shot command with a temporary session; fine for short commands like `version` or `stop`. */
    fun exec(command: String, execTimeoutMs: Long = 10_000): JsonObject =
        call(request("exec") {
            addProperty("command", command)
            addProperty("execTimeout", execTimeoutMs)
        }, Duration.ofMillis(execTimeoutMs + 5_000))

    fun initSession(): Session {
        val response = call(request("init_session"))
        return Session(
            response.str("sessionId") ?: throw ArthasException("init_session returned no sessionId"),
            response.str("consumerId") ?: throw ArthasException("init_session returned no consumerId"),
        )
    }

    fun asyncExec(session: Session, command: String): JsonObject =
        call(request("async_exec") {
            addProperty("sessionId", session.sessionId)
            addProperty("command", command)
        })

    fun pullResults(session: Session): JsonArray {
        val response = call(request("pull_results") {
            addProperty("sessionId", session.sessionId)
            addProperty("consumerId", session.consumerId)
        })
        return response.getAsJsonObject("body")?.getAsJsonArray("results") ?: JsonArray()
    }

    fun interruptJob(session: Session) {
        runCatching { call(request("interrupt_job") { addProperty("sessionId", session.sessionId) }) }
    }

    fun closeSession(session: Session) {
        runCatching { call(request("close_session") { addProperty("sessionId", session.sessionId) }, Duration.ofSeconds(2)) }
    }

    private fun request(action: String, fill: JsonObject.() -> Unit = {}): JsonObject =
        JsonObject().apply { addProperty("action", action); fill() }

    private fun call(body: JsonObject, timeout: Duration = Duration.ofSeconds(30)): JsonObject {
        val builder = HttpRequest.newBuilder(baseUri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        authHeader?.let { builder.header("Authorization", it) }

        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ArthasException("Interrupted", e)
        } catch (e: Exception) {
            throw ArthasException("Cannot reach Arthas at $host:$port (${e.javaClass.simpleName}: ${e.message})", e)
        }
        when (response.statusCode()) {
            200 -> Unit
            401 -> throw ArthasException("Arthas rejected the credentials (HTTP 401). Check username/password in Settings.")
            else -> throw ArthasException("Arthas returned HTTP ${response.statusCode()}")
        }
        val json = try {
            JsonParser.parseString(response.body()).asJsonObject
        } catch (e: Exception) {
            throw ArthasException("Unexpected response from $host:$port, is this an Arthas HTTP port?", e)
        }
        when (json.str("state")) {
            "FAILED", "REFUSED" -> throw ArthasException(json.str("message") ?: "Arthas request failed")
        }
        return json
    }

    data class Session(val sessionId: String, val consumerId: String)
}

internal fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

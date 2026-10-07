package com.example.provider

import com.example.common.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

data class AuthorizationRequest(
    val clientId: String, val redirectUri: String, val scopes: List<String>,
    val codeChallenge: String, val state: String?,
)
class OAuthFailure(val error: String, val description: String, val redirect: String? = null) : RuntimeException(description)
fun interface AccessTokens { fun issue(accountId: String, clientId: String, scopes: List<String>): String }

class OAuthProvider(
    private val store: Store,
    private val policy: AccessPolicy,
    private val tokens: AccessTokens,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()

    suspend fun validate(request: AuthorizationRequest): JsonObject {
        val clientId = clientId(request.clientId)
        val client = store.list(Entity.OAuthClient, eq("uuidId", clientId)).singleOrNull()
            ?: throw OAuthFailure("invalid_request", "Unknown client")
        if (request.redirectUri !in client.strings("redirectUris"))
            throw OAuthFailure("invalid_request", "Unregistered redirect URL")
        fun fail(error: String, message: String): Nothing =
            throw OAuthFailure(error, message, redirect(request, "error", error))
        if (!client.flag("enabled")) fail("unauthorized_client", "Client is disabled")
        if (!request.codeChallenge.matches(Regex("[A-Za-z0-9_-]{43}")))
            fail("invalid_request", "A valid S256 PKCE challenge is required")
        if (request.state != null && request.state.length > 512) fail("invalid_request", "State is too long")
        if (request.scopes.isEmpty() || request.scopes.size > 16 ||
            request.scopes.distinct().size != request.scopes.size ||
            request.scopes.any { it !in client.strings("scopes") })
            fail("invalid_scope", "Requested scopes are not registered")
        return client
    }

    suspend fun authorize(principal: Principal, request: AuthorizationRequest, approved: Boolean): String {
        val client = validate(request)
        val clientId = client.text("uuidId")
        if (!approved || !policy.decide(principal.id, clientId, request.scopes).allowed)
            return redirect(request, "error", "access_denied")
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        store.insert(Entity.AuthorizationGrant, values(
            "accountId" to JsonPrimitive(principal.id), "clientId" to JsonPrimitive(clientId),
            "codeHash" to JsonPrimitive(hash(code)), "codeChallenge" to JsonPrimitive(request.codeChallenge),
            "redirectUri" to JsonPrimitive(request.redirectUri), "scopes" to jsonStrings(request.scopes.sorted()),
            "expiresAt" to JsonPrimitive(clock.instant().epochSecond + 60),
        ))
        return redirect(request, "code", code)
    }

    suspend fun exchange(code: String, client: String, redirectUri: String, verifier: String): String {
        fun invalid(): Nothing = throw OAuthFailure("invalid_grant", "Invalid or expired authorization grant")
        if (!code.matches(Regex("[A-Za-z0-9_-]{43}")) || !verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
            invalid()
        val clientId = try { clientId(client) } catch (_: OAuthFailure) { invalid() }
        val grant = store.list(Entity.AuthorizationGrant, eq("codeHash", hash(code))).singleOrNull() ?: invalid()
        if (grant.text("clientId") != clientId || grant.text("redirectUri") != redirectUri ||
            grant.text("expiresAt").toLong() <= clock.instant().epochSecond ||
            !MessageDigest.isEqual(hash(verifier).toByteArray(UTF_8), grant.text("codeChallenge").toByteArray(UTF_8)))
            invalid()
        val scopes = grant.strings("scopes")
        if (!policy.decide(grant.text("accountId"), clientId, scopes).allowed) invalid()
        // Sign before consuming: a signing failure must not burn the grant.
        val token = tokens.issue(grant.text("accountId"), client, scopes)
        val filter = buildJsonObject {
            put("codeHash", buildJsonObject { put("eq", hash(code)) })
            put("expiresAt", buildJsonObject { put("gt", clock.instant().epochSecond) })
        }
        // One database DELETE decides the winner across threads, processes, and instances.
        if (store.delete(Entity.AuthorizationGrant, filter) != 1) invalid()
        return token
    }

    companion object {
        fun hash(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))

        fun clientId(global: String): String {
            val decoded = try { String(Base64.getDecoder().decode(global), UTF_8) }
            catch (_: IllegalArgumentException) { throw OAuthFailure("invalid_request", "Invalid client ID") }
            if (!decoded.startsWith("OAuthClient:")) throw OAuthFailure("invalid_request", "Invalid client ID")
            val internal = decoded.removePrefix("OAuthClient:")
            val canonical = try { UUID.fromString(internal).toString() }
            catch (_: IllegalArgumentException) { throw OAuthFailure("invalid_request", "Invalid client ID") }
            if (internal != canonical || Base64.getEncoder().encodeToString(decoded.toByteArray(UTF_8)) != global)
                throw OAuthFailure("invalid_request", "Invalid client ID")
            return canonical
        }

        fun redirect(request: AuthorizationRequest, parameter: String, value: String): String {
            val separator = if ("?" in request.redirectUri) "&" else "?"
            fun encode(value: String) = URLEncoder.encode(value, UTF_8)
            return request.redirectUri + separator + encode(parameter) + "=" + encode(value) +
                (request.state?.let { "&state=" + encode(it) } ?: "")
        }
    }
}

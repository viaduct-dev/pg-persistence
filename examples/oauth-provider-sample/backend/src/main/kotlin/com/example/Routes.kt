package com.example

import com.example.common.*
import com.example.provider.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.staticFiles
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import java.io.File
import java.net.URLEncoder

data class Login(val username: String = "", val password: String = "")
data class GraphqlRequest(val query: String, val variables: Map<String, Any?> = emptyMap(), val operationName: String? = null)
data class Consent(
    val clientId: String, val redirectUri: String, val scopes: List<String>,
    val codeChallenge: String, val state: String? = null, val approved: Boolean,
)

private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Pragma, "no-cache")
}

private suspend fun ApplicationCall.auth(sample: Sample): Principal? {
    val header = request.headers[HttpHeaders.Authorization]
    val principal = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.let { sample.principal(it) }
    if (principal == null) respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
    return principal
}

private fun Parameters.singleValue(name: String, required: Boolean = true): String? {
    val values = getAll(name)
    if (values == null && !required) return null
    if (values?.size != 1 || values.single().isEmpty() || values.single().length > 2048)
        throw OAuthFailure("invalid_request", "Missing, duplicated or invalid parameter: $name")
    return values.single()
}

private suspend fun ApplicationCall.oauthFailure(failure: OAuthFailure, browser: Boolean) {
    noStore()
    val redirect = failure.redirect
    if (browser && redirect != null) respondRedirect(redirect)
    else respond(HttpStatusCode.BadRequest, mapOf("error" to failure.error, "error_description" to failure.description))
}

fun Application.sampleRoutes(sample: Sample, frontend: File = File("../dist")) {
    install(ContentNegotiation) { jackson() }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        post("/session") {
            call.noStore()
            val input = call.receive<Login>()
            val token = sample.login(input.username, input.password)
            if (token == null) call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
            else {
                val principal = requireNotNull(sample.principal(token))
                call.respond(mapOf("accessToken" to token, "admin" to principal.admin))
            }
        }
        post("/graphql") {
            call.noStore()
            val principal = call.auth(sample) ?: return@post
            val input = call.receive<GraphqlRequest>()
            val result = sample.viaduct.execute(
                ExecutionInput.create(operationText = input.query, variables = input.variables,
                    operationName = input.operationName, requestContext = SampleContext(principal)),
                if (principal.admin) SchemaId.Scoped("admin", setOf("default", "admin"))
                else SchemaId.Scoped("default", setOf("default")),
            )
            call.respond(result.toSpecification())
        }
        get("/.well-known/oauth-authorization-server") {
            call.respond(mapOf(
                "issuer" to sample.issuer, "authorization_endpoint" to "${sample.issuer}/oauth/authorize",
                "token_endpoint" to "${sample.issuer}/oauth/token", "jwks_uri" to "${sample.issuer}/oauth/jwks",
                "response_types_supported" to listOf("code"), "grant_types_supported" to listOf("authorization_code"),
                "code_challenge_methods_supported" to listOf("S256"), "token_endpoint_auth_methods_supported" to listOf("none"),
            ))
        }
        get("/oauth/jwks") { call.respond(sample.tokens.jwks()) }
        get("/demo-resource") {
            call.noStore()
            val token = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")?.let(sample.tokens::access)
            if (token == null) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
                return@get
            }
            if ("demo:read" !in token.getClaim("scope").asString().split(" ")) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "insufficient_scope"))
                return@get
            }
            val account = sample.store.list(Entity.Account, eq("uuidId", token.subject)).singleOrNull()
            if (account == null) call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
            else call.respond(mapOf("username" to account.text("username"), "scope" to token.getClaim("scope").asString()))
        }
        get("/oauth/authorize") {
            call.noStore()
            try {
                val parameters = call.request.queryParameters
                if (parameters.singleValue("response_type") != "code")
                    throw OAuthFailure("unsupported_response_type", "Only authorization code is supported")
                if (parameters.singleValue("code_challenge_method") != "S256")
                    throw OAuthFailure("invalid_request", "S256 PKCE is required")
                val request = AuthorizationRequest(
                    parameters.singleValue("client_id")!!, parameters.singleValue("redirect_uri")!!,
                    parameters.singleValue("scope")!!.split(" "), parameters.singleValue("code_challenge")!!,
                    parameters.singleValue("state", false),
                )
                sample.oauth.validate(request)
                // Rebuild from parsed, unique parameters; no unvalidated query data is forwarded.
                val fields = mapOf("client_id" to request.clientId, "redirect_uri" to request.redirectUri,
                    "scope" to request.scopes.joinToString(" "), "code_challenge" to request.codeChallenge,
                    "state" to request.state).filterValues { it != null }
                val query = fields.entries.joinToString("&") {
                    URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
                }
                call.respondRedirect("${sample.issuer}/authorize?$query")
            } catch (failure: OAuthFailure) { call.oauthFailure(failure, true) }
        }
        post("/oauth/authorize") {
            call.noStore()
            val principal = call.auth(sample) ?: return@post
            val input = call.receive<Consent>()
            try {
                val redirect = sample.oauth.authorize(principal,
                    AuthorizationRequest(input.clientId, input.redirectUri, input.scopes, input.codeChallenge, input.state),
                    input.approved)
                call.respond(mapOf("redirectUri" to redirect))
            } catch (failure: OAuthFailure) {
                // The consent UI navigates only to a registered redirect.
                if (failure.redirect != null) call.respond(mapOf("redirectUri" to failure.redirect))
                else call.oauthFailure(failure, false)
            }
        }
        post("/oauth/token") {
            call.noStore()
            try {
                val parameters = call.receiveParameters()
                if (parameters.singleValue("grant_type") != "authorization_code")
                    throw OAuthFailure("unsupported_grant_type", "Only authorization code is supported")
                if (parameters.contains("client_secret") || call.request.headers[HttpHeaders.Authorization] != null)
                    throw OAuthFailure("invalid_client", "Only public clients with PKCE are supported")
                val token = sample.oauth.exchange(parameters.singleValue("code")!!, parameters.singleValue("client_id")!!,
                    parameters.singleValue("redirect_uri")!!, parameters.singleValue("code_verifier")!!)
                call.respond(mapOf("access_token" to token, "token_type" to "Bearer", "expires_in" to 300))
            } catch (failure: OAuthFailure) { call.oauthFailure(failure, false) }
        }
        staticFiles("/assets", File(frontend, "assets"))
        for (path in listOf("/", "/authorize", "/demo-client")) {
            get(path) {
                call.response.header("Referrer-Policy", "no-referrer")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondFile(File(frontend, "index.html"))
            }
        }
    }
}

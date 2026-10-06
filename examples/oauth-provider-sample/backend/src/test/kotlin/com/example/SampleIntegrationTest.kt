package com.example

import com.example.common.*
import com.example.provider.*
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.postgresql.ds.PGSimpleDataSource
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampleIntegrationTest {
    private lateinit var source: PGSimpleDataSource
    private lateinit var administration: PGSimpleDataSource
    private var databaseName: String? = null
    private lateinit var sample: Sample
    private lateinit var admin: Principal
    private val issuer = "http://localhost:10000"
    private val password = "sample-test-password-123"

    @BeforeAll
    fun freshDatabase() = runBlocking<Unit> {
        val url = System.getenv("TEST_DATABASE_ADMIN_JDBC_URL")
            ?: error("Run scripts/test.sh so integration tests have a dedicated PostgreSQL instance")
        val adminSource = PGSimpleDataSource().apply {
            setURL(url); user = System.getenv("DATABASE_USER") ?: "postgres"
            this.password = requireNotNull(System.getenv("DATABASE_PASSWORD"))
        }
        val name = "oauth_test_" + UUID.randomUUID().toString().replace("-", "")
        administration = adminSource
        databaseName = name
        adminSource.connection.use { it.createStatement().use { statement -> statement.execute("CREATE DATABASE $name TEMPLATE template0") } }
        source = PGSimpleDataSource().apply {
            setURL(url.substringBeforeLast("/") + "/" + name); user = adminSource.user; this.password = adminSource.password
        }
        SchemaInstaller.install(source, File(System.getProperty("sample.schemaSql")))
        sample = Sample(source, issuer, Tokens(issuer, Tokens.developmentKey()))
        sample.bootstrap("admin", password)
        admin = requireNotNull(sample.principal(requireNotNull(sample.login("admin", password))))
    }

    @AfterAll
    fun removeFixtureDatabase() {
        val name = databaseName ?: return
        administration.connection.use { connection ->
            connection.createStatement().use { it.execute("DROP DATABASE $name") }
        }
    }

    @Test
    fun `fresh database contains only the six schema-defined application tables`() {
        val tables = mutableSetOf<String>()
        source.connection.use { connection ->
            connection.metaData.getTables(null, "public", null, arrayOf("TABLE")).use { result ->
                while (result.next()) tables += result.getString("TABLE_NAME")
            }
        }
        assertEquals(setOf("accounts", "groups", "memberships", "o_auth_clients", "access_rules", "authorization_grants"), tables)
    }

    @Test
    fun `schema installer refuses to modify an existing application database`() {
        assertThrows<IllegalArgumentException> { SchemaInstaller.install(source, File(System.getProperty("sample.schemaSql"))) }
    }

    @Test
    fun `database enforces unique usernames`() = runBlocking<Unit> {
        val username = username()
        account(username)
        assertThrows<UpstreamGraphqlException> { runBlocking { account(username) } }
    }

    @Test
    fun `database enforces unique memberships`() = runBlocking<Unit> {
        val fixture = allowed()
        assertThrows<UpstreamGraphqlException> { runBlocking {
            sample.store.insert(Entity.Membership, values("accountId" to JsonPrimitive(fixture.accountId),
                "groupId" to JsonPrimitive(fixture.groupId)))
        } }
    }

    @Test
    fun `database enforces unique access rules`() = runBlocking<Unit> {
        val fixture = allowed()
        assertThrows<UpstreamGraphqlException> { runBlocking {
            sample.store.insert(Entity.AccessRule, values("groupId" to JsonPrimitive(fixture.groupId),
                "clientId" to JsonPrimitive(OAuthProvider.clientId(fixture.clientId)), "scopes" to jsonStrings(listOf("demo:read"))))
        } }
    }

    @Test
    fun `both tenants execute in one live Viaduct query with nested memberships`() = runBlocking<Unit> {
        val fixture = allowed()
        val result = sample.viaduct.execute(ExecutionInput.create(
            operationText = "query(\$client:ID!){groups{id name members{id accountId}} accessDecision(clientId:\$client,scopes:[\"demo:read\"]){allowed scopes}}",
            variables = mapOf("client" to fixture.clientId), requestContext = SampleContext(Principal(fixture.accountId, true)),
        ), SchemaId.Scoped("admin", setOf("admin", "default"))).toSpecification()
        val data = result["data"] as? Map<*, *>
        assertTrue(result["errors"] == null && (data?.get("accessDecision") as? Map<*, *>)?.get("allowed") == true &&
            (data["groups"] as? List<*>)?.any { group ->
                ((group as Map<*, *>)["members"] as List<*>).any { member ->
                    (member as Map<*, *>)["accountId"] == fixture.globalAccountId
                }
            } == true)
    }

    @Test
    fun `ordinary accounts can query the non database tenant`() = runBlocking<Unit> {
        val fixture = allowed()
        val result = sample.viaduct.execute(ExecutionInput.create(
            operationText = "query(\$client:ID!){accessDecision(clientId:\$client,scopes:[\"demo:read\"]){allowed}}",
            variables = mapOf("client" to fixture.clientId), requestContext = SampleContext(Principal(fixture.accountId, false)),
        ), SchemaId.Scoped("default", setOf("default"))).toSpecification()
        val data = result["data"] as? Map<*, *>
        assertTrue(result["errors"] == null && (data?.get("accessDecision") as? Map<*, *>)?.get("allowed") == true)
    }

    @Test
    fun `native arrays and long redirects survive persistence`() = runBlocking<Unit> {
        val redirect = "https://client.example/" + "p".repeat(300)
        val scopes = listOf("demo:read", "demo:write")
        val record = sample.store.insert(Entity.OAuthClient, values(
            "name" to JsonPrimitive(username()), "redirectUris" to jsonStrings(listOf(redirect, "$issuer/demo-client")),
            "scopes" to jsonStrings(scopes), "enabled" to JsonPrimitive(true),
        ))
        assertTrue(record.strings("redirectUris") == listOf(redirect, "$issuer/demo-client") && record.strings("scopes") == scopes)
    }

    @Test
    fun `ordinary accounts cannot query management data`() = runBlocking<Unit> {
        val fixture = allowed()
        val result = sample.viaduct.execute(ExecutionInput.create(operationText = "{accounts{id username}}",
            requestContext = SampleContext(Principal(fixture.accountId, false))),
            SchemaId.Scoped("default", setOf("default"))).toSpecification()
        assertTrue((result["errors"] as? List<*>)?.isNotEmpty() == true)
    }

    @Test
    fun `management schema does not expose password hashes or grants`() = runBlocking<Unit> {
        val result = sample.viaduct.execute(ExecutionInput.create(operationText = "{accounts{id passwordHash}}",
            requestContext = SampleContext(admin)), SchemaId.Scoped("admin", setOf("admin", "default"))).toSpecification()
        assertTrue((result["errors"] as? List<*>)?.isNotEmpty() == true)
    }

    @Test
    fun `one live GraphQL mutation can create a user group and client`() = runBlocking<Unit> {
        val suffix = username()
        val result = sample.viaduct.execute(ExecutionInput.create(
            operationText = """mutation(${'$'}name:String!,${'$'}password:String!){
              createAccount(username:${'$'}name,password:${'$'}password){id username}
              createGroup(name:${'$'}name){id name}
              createClient(name:${'$'}name,redirectUris:["http://localhost:10000/demo-client"],scopes:["demo:read"]){id enabled}
            }""",
            variables = mapOf("name" to suffix, "password" to password), requestContext = SampleContext(admin),
        ), SchemaId.Scoped("admin", setOf("admin", "default"))).toSpecification()
        assertEquals(setOf("createAccount", "createGroup", "createClient"), (result["data"] as? Map<*, *>)?.filterValues { it != null }?.keys)
    }

    @Test
    fun `approved PKCE grant yields a signed access token`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        val token = sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier)
        val decoded = sample.tokens.access(token)
        assertTrue(decoded?.subject == fixture.accountId && decoded.getClaim("scope").asString() == "demo:read")
    }

    @Test
    fun `authorization code is single use`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier)
        assertThrows<OAuthFailure> { runBlocking { sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier) } }
    }

    @Test
    fun `concurrent redemption has exactly one winner without hanging`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        val outcomes = withTimeout(15_000) {
            coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        try { sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier); true }
                        catch (failure: OAuthFailure) { if (failure.error != "invalid_grant") throw failure; false }
                    }
                }.awaitAll()
            }
        }
        assertEquals(1, outcomes.count { it })
    }

    @Test
    fun `wrong verifier cannot redeem or consume a grant`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        val failure = try { sample.oauth.exchange(code, fixture.clientId, fixture.redirect, "x".repeat(43)); null }
            catch (failure: OAuthFailure) { failure.error }
        val valid = sample.tokens.access(sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier))
        assertTrue(failure == "invalid_grant" && valid != null)
    }

    @Test
    fun `redirect must match exactly when redeeming`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        assertThrows<OAuthFailure> { runBlocking { sample.oauth.exchange(code, fixture.clientId, fixture.redirect + "?changed", fixture.verifier) } }
    }

    @Test
    fun `grant is bound to its client`() = runBlocking<Unit> {
        val fixture = allowed()
        val other = allowed()
        val code = code(fixture)
        assertThrows<OAuthFailure> { runBlocking { sample.oauth.exchange(code, other.clientId, fixture.redirect, fixture.verifier) } }
    }

    @Test
    fun `expired grant cannot be redeemed`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        val future = OAuthProvider(sample.store, sample.policy, sample.tokens,
            Clock.fixed(Instant.now().plusSeconds(61), ZoneOffset.UTC))
        assertThrows<OAuthFailure> { runBlocking { future.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier) } }
    }

    @Test
    fun `membership removal before redemption denies token issuance`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        sample.store.delete(Entity.Membership, eq("uuidId", fixture.membershipId))
        assertThrows<OAuthFailure> { runBlocking { sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier) } }
    }

    @Test
    fun `disabled client cannot redeem an existing grant`() = runBlocking<Unit> {
        val fixture = allowed()
        val code = code(fixture)
        sample.store.update(Entity.OAuthClient, eq("uuidId", OAuthProvider.clientId(fixture.clientId)),
            values("enabled" to JsonPrimitive(false)))
        assertThrows<OAuthFailure> { runBlocking { sample.oauth.exchange(code, fixture.clientId, fixture.redirect, fixture.verifier) } }
    }

    @Test
    fun `account without a group rule receives access denied`() = runBlocking<Unit> {
        val fixture = allowed()
        val other = account(username())
        val redirect = sample.oauth.authorize(Principal(other.text("uuidId"), false), fixture.request, true)
        assertEquals("access_denied", parameter(redirect, "error"))
    }

    @Test
    fun `denied consent creates no authorization grant`() = runBlocking<Unit> {
        val fixture = allowed()
        val redirect = sample.oauth.authorize(Principal(fixture.accountId, false), fixture.request, false)
        assertEquals("access_denied", parameter(redirect, "error"))
    }

    @Test
    fun `unregistered redirect is rejected without redirecting`() = runBlocking<Unit> {
        val fixture = allowed()
        val failure = try { sample.oauth.validate(fixture.request.copy(redirectUri = "https://attacker.example")); null }
            catch (failure: OAuthFailure) { failure }
        assertTrue(failure?.error == "invalid_request" && failure.redirect == null)
    }

    @Test
    fun `login session and OAuth access tokens cannot be substituted`() = runBlocking<Unit> {
        val fixture = allowed()
        val access = sample.oauth.exchange(code(fixture), fixture.clientId, fixture.redirect, fixture.verifier)
        assertTrue(sample.tokens.sessionSubject(access) == null && sample.tokens.access(sample.tokens.session(fixture.accountId)) == null)
    }

    @Test
    fun `HTTP OAuth flow reaches a protected resource with cache disabled`() {
        testApplication {
            application { sampleRoutes(sample) }
            val fixture = allowed()
            val login = client.post("/session") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"${fixture.username}","password":"$password"}""")
            }
            val session = Json.parseToJsonElement(login.bodyAsText()).jsonObject.text("accessToken")
            val consent = client.post("/oauth/authorize") {
                bearerAuth(session); contentType(ContentType.Application.Json)
                setBody(values(
                    "clientId" to JsonPrimitive(fixture.clientId), "redirectUri" to JsonPrimitive(fixture.redirect),
                    "scopes" to jsonStrings(listOf("demo:read")), "codeChallenge" to JsonPrimitive(OAuthProvider.hash(fixture.verifier)),
                    "state" to JsonPrimitive("test-state"), "approved" to JsonPrimitive(true),
                ).toString())
            }
            val code = parameter(Json.parseToJsonElement(consent.bodyAsText()).jsonObject.text("redirectUri"), "code")!!
            val response = client.post("/oauth/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(listOf("grant_type" to "authorization_code", "client_id" to fixture.clientId,
                    "redirect_uri" to fixture.redirect, "code" to code, "code_verifier" to fixture.verifier).formUrlEncode())
            }
            val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject.text("access_token")
            val resource = client.get("/demo-resource") { bearerAuth(token) }
            assertTrue(response.status == HttpStatusCode.OK && response.headers[HttpHeaders.CacheControl] == "no-store" &&
                resource.status == HttpStatusCode.OK && Json.parseToJsonElement(resource.bodyAsText()).jsonObject.text("username") == fixture.username)
        }
    }

    @Test
    fun `HTTP rejects duplicate token parameters`() {
        testApplication {
            application { sampleRoutes(sample) }
            val response = client.post("/oauth/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=authorization_code&grant_type=authorization_code")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    private suspend fun account(username: String): JsonObject = sample.store.insert(Entity.Account, values(
        "username" to JsonPrimitive(username), "passwordHash" to JsonPrimitive(Passwords.hash(password)), "admin" to JsonPrimitive(false),
    ))
    private fun username(): String = "u" + UUID.randomUUID().toString().replace("-", "").take(20)
    private suspend fun allowed(): Fixture {
        val username = username()
        val account = account(username)
        val group = sample.store.insert(Entity.Group, values("name" to JsonPrimitive(username)))
        val client = sample.store.insert(Entity.OAuthClient, values(
            "name" to JsonPrimitive(username), "redirectUris" to jsonStrings(listOf("$issuer/demo-client")),
            "scopes" to jsonStrings(listOf("demo:read")), "enabled" to JsonPrimitive(true),
        ))
        val membership = sample.store.insert(Entity.Membership, values(
            "accountId" to JsonPrimitive(account.text("uuidId")), "groupId" to JsonPrimitive(group.text("uuidId")),
        ))
        sample.store.insert(Entity.AccessRule, values(
            "groupId" to JsonPrimitive(group.text("uuidId")), "clientId" to JsonPrimitive(client.text("uuidId")),
            "scopes" to jsonStrings(listOf("demo:read")),
        ))
        return Fixture(username, account.text("uuidId"), account.text("id"), group.text("uuidId"),
            membership.text("uuidId"), client.text("id"), "$issuer/demo-client", "v".repeat(43))
    }
    private suspend fun code(fixture: Fixture): String = parameter(
        sample.oauth.authorize(Principal(fixture.accountId, false), fixture.request, true), "code",
    ) ?: error("Authorization did not issue a code")
    private fun parameter(uri: String, name: String): String? = URI(uri).rawQuery.split("&")
        .map { it.split("=", limit = 2) }.firstOrNull { it[0] == name }?.get(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    private data class Fixture(val username: String, val accountId: String, val globalAccountId: String, val groupId: String,
        val membershipId: String, val clientId: String, val redirect: String, val verifier: String) {
        val request get() = AuthorizationRequest(clientId, redirect, listOf("demo:read"), OAuthProvider.hash(verifier), "test-state")
    }
}

package com.example

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.net.URI

fun main() = runBlocking<Unit> {
    val issuer = (System.getenv("ISSUER_URL") ?: "http://localhost:10000").trimEnd('/')
    val uri = URI(issuer)
    require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null &&
        uri.path.isNullOrEmpty() && uri.host != null &&
        (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1"))) {
        "ISSUER_URL must be an HTTPS origin (HTTP is permitted on localhost)"
    }
    val keys = System.getenv("SIGNING_PRIVATE_KEY_DER")?.let {
        Tokens.load(it, requireNotNull(System.getenv("SIGNING_PUBLIC_KEY_DER")))
    } ?: run {
        require(System.getenv("DEMO_MODE") == "true") { "Configure signing keys or explicitly enable DEMO_MODE" }
        Tokens.developmentKey()
    }
    val dataSource = dataSourceFromEnvironment()
    val sample = Sample(dataSource, issuer, Tokens(issuer, keys))
    val username = System.getenv("ADMIN_USERNAME") ?: "admin"
    sample.bootstrap(username, requireNotNull(System.getenv("ADMIN_PASSWORD")) { "ADMIN_PASSWORD is required" })
    embeddedServer(CIO, port = System.getenv("PORT")?.toInt() ?: 10000) { sampleRoutes(sample) }.start(wait = true)
}

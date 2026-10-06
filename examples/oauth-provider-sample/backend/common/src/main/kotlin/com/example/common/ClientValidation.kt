package com.example.common

import java.net.URI

object ClientValidation {
    fun scopes(scopes: List<String>): List<String> {
        require(scopes.isNotEmpty() && scopes.size <= 16) { "Specify 1 to 16 scopes" }
        require(scopes.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,63}")) && it != "openid" }) {
            "Invalid OAuth scope; this sample does not implement OpenID Connect"
        }
        require(scopes.distinct().size == scopes.size) { "Scopes must be distinct" }
        return scopes.sorted()
    }

    fun redirect(uri: String): String {
        require(uri.length <= 2048) { "Redirect URL is too long" }
        val parsed = URI(uri)
        require(parsed.isAbsolute && parsed.host != null && parsed.rawUserInfo == null && parsed.rawFragment == null) {
            "Specify an absolute redirect URL without credentials or fragments"
        }
        require(parsed.scheme == "https" || parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1", "[::1]")) {
            "Redirect URLs must use HTTPS, except for local development"
        }
        return uri
    }
}

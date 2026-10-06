package com.example.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun hash(password: String): String {
        require(password.length in 12..256) { "Password must have 12 to 256 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256:$ITERATIONS:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, encoded: String): Boolean {
        if (password.length !in 1..256) return false
        val parts = encoded.split(":")
        require(parts.size == 4 && parts[0] == "pbkdf2-sha256") { "Unsupported password hash" }
        val iterations = parts[1].toInt()
        require(iterations == ITERATIONS) { "Unsupported password work factor" }
        return MessageDigest.isEqual(decoder.decode(parts[3]), derive(password, decoder.decode(parts[2]), iterations))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val specification = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
    }
}

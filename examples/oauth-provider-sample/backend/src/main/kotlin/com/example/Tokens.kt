package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.example.provider.AccessTokens
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.Date

class Tokens(private val issuer: String, keyPair: KeyPair, private val clock: Clock = Clock.systemUTC()) : AccessTokens {
    private val publicKey = keyPair.public as RSAPublicKey
    private val algorithm = Algorithm.RSA256(publicKey, keyPair.private as RSAPrivateKey)
    init {
        require(publicKey.modulus == (keyPair.private as RSAPrivateKey).modulus) { "Signing keys must form a key pair" }
    }
    private val verifier = JWT.require(algorithm).withIssuer("$issuer/session").withAudience("sample-management").build()
    private val accessVerifier = JWT.require(algorithm).withIssuer(issuer).withAudience("$issuer/demo-resource").build()
    private val keyId = com.example.provider.OAuthProvider.hash(Base64.getEncoder().encodeToString(publicKey.encoded))

    fun session(accountId: String): String = JWT.create().withKeyId(keyId)
        .withIssuer("$issuer/session").withAudience("sample-management").withSubject(accountId)
        .withIssuedAt(Date.from(clock.instant())).withExpiresAt(Date.from(clock.instant().plusSeconds(900)))
        .sign(algorithm)

    fun sessionSubject(token: String): String? = try { verifier.verify(token).subject }
        catch (_: JWTVerificationException) { null }

    override fun issue(accountId: String, clientId: String, scopes: List<String>): String =
        JWT.create().withHeader(mapOf("typ" to "at+jwt")).withKeyId(keyId)
            .withIssuer(issuer).withAudience("$issuer/demo-resource").withSubject(accountId)
            .withClaim("scope", scopes.joinToString(" ")).withClaim("client_id", clientId)
            .withIssuedAt(Date.from(clock.instant())).withExpiresAt(Date.from(clock.instant().plusSeconds(300)))
            .sign(algorithm)

    fun access(token: String): DecodedJWT? = try {
        accessVerifier.verify(token).takeIf { it.getHeaderClaim("typ").asString() == "at+jwt" }
    } catch (_: JWTVerificationException) { null }

    fun jwks(): Map<String, Any> {
        fun unsigned(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes)
        return mapOf("keys" to listOf(mapOf(
            "kty" to "RSA", "use" to "sig", "alg" to "RS256", "kid" to keyId,
            "n" to unsigned(publicKey.modulus.toByteArray()), "e" to unsigned(publicKey.publicExponent.toByteArray()),
        )))
    }

    companion object {
        fun developmentKey(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun load(privateDer: String, publicDer: String): KeyPair {
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicDer))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateDer))),
            )
        }
    }
}

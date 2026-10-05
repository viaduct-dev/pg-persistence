package dev.viaduct.persistence.jdbc

/** Finite JDBC execution bounds. Connection-pool acquisition is configured on the application's DataSource. */
data class JdbcTimeouts
    @JvmOverloads
    constructor(
        val querySeconds: Int = 30,
        val networkMillis: Int = 30_000,
    ) {
        init {
            require(querySeconds > 0) { "querySeconds must be positive" }
            require(networkMillis > 0) { "networkMillis must be positive" }
        }
    }

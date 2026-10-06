package com.example

import org.postgresql.ds.PGSimpleDataSource

fun dataSourceFromEnvironment(): PGSimpleDataSource = PGSimpleDataSource().apply {
    setURL(requireNotNull(System.getenv("DATABASE_JDBC_URL")) { "DATABASE_JDBC_URL is required" })
    user = System.getenv("DATABASE_USER") ?: "postgres"
    password = requireNotNull(System.getenv("DATABASE_PASSWORD")) { "DATABASE_PASSWORD is required" }
    connectTimeout = 5
    loginTimeout = 5
    socketTimeout = 30
}

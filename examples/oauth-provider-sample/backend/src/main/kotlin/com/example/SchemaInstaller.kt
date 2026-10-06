package com.example

import java.io.File
import javax.sql.DataSource

object SchemaInstaller {
    fun install(dataSource: DataSource, script: File) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.metaData.getTables(null, "public", null, arrayOf("TABLE")).use { tables ->
                require(!tables.next()) { "Schema installation requires an empty application database" }
            }
            // pg_graphql is platform infrastructure. Every application definition comes from script.
            connection.createStatement().use { statement ->
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_graphql")
                statement.execute(script.readText())
            }
            connection.commit()
        }
    }
}

fun main(args: Array<String>) {
    val dataSource = dataSourceFromEnvironment()
    SchemaInstaller.install(dataSource, File(args.single()))
    println("Installed application schema from pg-persistence output.")
}

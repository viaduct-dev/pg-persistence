package dev.viaduct.persistence.liquibase

import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.resource.ClassLoaderResourceAccessor

/** Owns a Liquibase database and the classloader resources used to create it. */
internal class LiquibaseDatabaseSession private constructor(
    val database: Database,
    private val resourceAccessor: ClassLoaderResourceAccessor,
) : AutoCloseable {
    override fun close() {
        try {
            database.close()
        } finally {
            resourceAccessor.close()
        }
    }

    companion object {
        fun open(
            url: String,
            username: String? = null,
            password: String? = null,
        ): LiquibaseDatabaseSession {
            val resourceAccessor =
                ClassLoaderResourceAccessor(
                    ViaductHibernateDatabase::class.java.classLoader,
                )
            var opened = false
            return try {
                LiquibaseDatabaseSession(
                    database =
                        DatabaseFactory.getInstance().openDatabase(
                            url,
                            username,
                            password,
                            null,
                            resourceAccessor,
                        ),
                    resourceAccessor = resourceAccessor,
                ).also { opened = true }
            } finally {
                if (!opened) resourceAccessor.close()
            }
        }
    }
}

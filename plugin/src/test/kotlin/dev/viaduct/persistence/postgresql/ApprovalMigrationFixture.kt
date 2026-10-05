package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.ConservativeLiquibaseDiffTask
import dev.viaduct.persistence.gradle.HibernateSchemaDiffTask
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import kotlinx.coroutines.runBlocking
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Applies the real review-only diff and overlays, exclusively inside a disposable database. */
internal class ApprovalMigrationFixture private constructor(
    private val database: Connection,
    private val settings: Map<String, String>,
) {
    val suffix =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(8)

    fun version(
        sdl: String,
        includeDestructiveReview: Boolean = false,
        test: suspend (ApprovalRequestFixture, () -> Unit) -> Unit,
    ) {
        withGeneratedModel(sdl, settings) { model, schema, generated, handle ->
            val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
            val applyMigration = {
                val sql =
                    reviewedDiff(schema, generated, includeDestructiveReview) + "\n" +
                        PostgresqlOverlay.renderMigration(effective) + "\n" + PgGraphqlOverlay.render(effective)
                database.autoCommit = false
                try {
                    JdbcOperations.execute(database, sql)
                    database.commit()
                } catch (failure: Exception) {
                    database.rollback()
                    throw failure
                } finally {
                    database.autoCommit = true
                }
            }
            ApprovalRequestFixture.withGrts(schema, generated) { loader ->
                database.graphqlClient().use { http ->
                    runBlocking { test(ApprovalRequestFixture(suffix, loader, http, sdl), applyMigration) }
                }
            }
        }
    }

    private fun reviewedDiff(
        schema: File,
        generated: File,
        includeDestructiveReview: Boolean,
    ): String {
        val project = ProjectBuilder.builder().withProjectDir(generated).build()
        val raw = generated.resolve("raw.postgresql.sql")
        project.tasks.register("diff", HibernateSchemaDiffTask::class.java).get().apply {
            centralSchemaDirectory.set(schema.parentFile)
            mappingFile.set(generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
            modelClasspath.from(System.getProperty("java.class.path").split(File.pathSeparator).map(::File))
            implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
            physicalNamingStrategyClassName.set(ViaductPhysicalNamingStrategy::class.java.name)
            targetUrl.set(settings.getValue("hibernate.connection.url"))
            targetUsername.set(settings.getValue("hibernate.connection.username"))
            targetPassword.set(settings.getValue("hibernate.connection.password"))
            diffFile.set(raw)
            diff()
        }
        val migration = generated.resolve("migration.sql")
        val destructive = generated.resolve("destructive.sql")
        project.tasks.register("conservative", ConservativeLiquibaseDiffTask::class.java).get().apply {
            rawDiffFile.set(raw)
            migrationFile.set(migration)
            destructiveReviewFile.set(destructive)
            filter()
        }
        return migration.readText() + if (includeDestructiveReview) "\n" + destructive.readText() else ""
    }

    companion object {
        fun withMigrationDatabase(test: (ApprovalMigrationFixture) -> Unit) {
            val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
            require(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:"))
            val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
            val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
            val name = "pg_migration_" + UUID.randomUUID().toString().replace("-", "")
            val targetUrl = url.substringBeforeLast('/') + "/" + name
            DriverManager.getConnection(url, user, password).use { admin ->
                JdbcOperations.execute(admin, "CREATE DATABASE $name")
                try {
                    withInitializedDatabase(targetUrl, user, password, test)
                } finally {
                    JdbcOperations.execute(admin, "DROP DATABASE $name")
                }
            }
        }

        private fun withInitializedDatabase(
            url: String,
            user: String,
            password: String,
            test: (ApprovalMigrationFixture) -> Unit,
        ) {
            DriverManager.getConnection(url, user, password).use { database ->
                JdbcOperations.execute(database, "CREATE EXTENSION pg_graphql CASCADE")
                val settings =
                    mapOf(
                        "hibernate.connection.url" to url,
                        "hibernate.connection.username" to user,
                        "hibernate.connection.password" to password,
                        "hibernate.connection.driver_class" to "org.postgresql.Driver",
                        "hibernate.boot.allow_jdbc_metadata_access" to "true",
                    )
                test(ApprovalMigrationFixture(database, settings))
            }
        }
    }
}

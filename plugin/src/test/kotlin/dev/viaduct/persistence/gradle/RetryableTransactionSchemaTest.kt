package dev.viaduct.persistence.gradle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.pggraphql.overlay.RetryableTransactionOverlay
import org.gradle.testfixtures.ProjectBuilder
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertFailsWith

class RetryableTransactionSchemaTest {
    @Test
    fun `schema diff creates the record in its private schema`() =
        withModel { directory, schema, policy, mapping ->
            val task =
                ProjectBuilder
                    .builder()
                    .withProjectDir(directory)
                    .build()
                    .tasks
                    .create("diff", HibernateSchemaDiffTask::class.java)
            task.centralSchemaDirectory.set(schema)
            task.persistenceConfigFile.from(policy)
            task.mappingFile.set(mapping)
            task.modelClasspath.from(classpath())
            task.implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
            task.physicalNamingStrategyClassName.set(ViaductPhysicalNamingStrategy::class.java.name)
            task.targetUrl.set(
                System.getenv("PG_RETRY_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55322/kan22_retry_tests",
            )
            task.targetUsername.set("postgres")
            task.targetPassword.set(System.getenv("PG_RETRY_PASSWORD") ?: "postgres")
            val diff = directory.resolve("diff.postgresql.sql")
            task.diffFile.set(diff)
            task.diff()
            val sql = diff.readText()
            assertThat(sql.lowercase()).contains("persistence_private.transaction_records")
            verifyMigration(task.targetUrl.get(), task.targetPassword.get(), sql)
        }

    @Test
    fun `retry table is present in the actual Hibernate snapshot`() =
        withModel { directory, schema, policy, mapping ->
            val snapshot = snapshot(directory, schema, policy, mapping)
            assertThat(snapshot).contains("transaction_records")
            assertThat(snapshot).contains("persistence_private")
        }

    @Test
    fun `wrapper uses table and column names resolved by Hibernate`() =
        withModel { _, schema, policy, mapping ->
            val model = PersistenceSchemaModelLoader.build(schema, policy)
            val configuration =
                HibernateMetadataConfigurationFactory.create(
                    HibernateMetadataConfigurationInput(
                        mapping,
                        classpath(),
                        model,
                        physicalNamingStrategyClassName = RetryNamingStrategy::class.java.name,
                    ),
                )
            HibernateMetadataBootstrap.build(configuration).use { handle ->
                val overlay = RetryableTransactionOverlay.render(handle.metadata)
                assertThat(overlay).contains("\"audit_transaction_records\"")
                assertThat(overlay).contains("\"audit_request\"")
                assertThat(overlay.contains("CREATE TABLE")).isFalse()
            }
        }

    @Test
    fun `retry support is disabled by default and rejects misspelled boolean config`() {
        assertThat(PersistenceConfig.load(null).retryableTransactions).isFalse()
        val file = Files.createTempFile("retry-policy", ".yaml").toFile()
        try {
            file.writeText("retryableTransactions: yesplease")
            assertFailsWith<IllegalArgumentException> { PersistenceConfig.load(file) }
            file.writeText("retryableTransactions: true")
            assertThat(PersistenceConfig.load(file).retryableTransactions).isTrue()
        } finally {
            check(file.delete())
        }
    }

    private fun verifyMigration(
        url: String,
        password: String,
        sql: String,
    ) {
        require(url.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan22_retry_tests")))
        DriverManager.getConnection(url, "postgres", password).use { database ->
            database.autoCommit = false
            try {
                JdbcOperations.execute(database, "CREATE SCHEMA IF NOT EXISTS persistence_private")
                JdbcOperations.execute(database, sql)
                val types =
                    JdbcOperations.query(
                        database,
                        """SELECT udt_name FROM information_schema.columns
                       WHERE table_schema = 'persistence_private' AND table_name = 'transaction_records'
                       ORDER BY column_name""",
                        { rows -> buildList { while (rows.next()) add(rows.getString(1)) } },
                    )
                assertThat(types).isEqualTo(listOf("timestamptz", "text", "jsonb", "jsonb"))
            } finally {
                database.rollback()
            }
        }
    }

    private fun snapshot(
        directory: File,
        schema: File,
        policy: File,
        mapping: File,
    ): String {
        val task =
            ProjectBuilder
                .builder()
                .withProjectDir(directory)
                .build()
                .tasks
                .create("snapshot", HibernateSchemaSnapshotTask::class.java)
        task.centralSchemaDirectory.set(schema)
        task.persistenceConfigFile.from(policy)
        task.mappingFile.set(mapping)
        task.modelClasspath.from(classpath())
        task.implicitNamingStrategyClassName.set(ViaductImplicitNamingStrategy::class.java.name)
        task.physicalNamingStrategyClassName.set(ViaductPhysicalNamingStrategy::class.java.name)
        val snapshot = directory.resolve("snapshot.json")
        task.snapshotFile.set(snapshot)
        task.snapshot()
        return snapshot.readText()
    }

    private fun withModel(test: (File, File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("retry-schema-").toFile()
        try {
            val schema = directory.resolve("schema").apply { check(mkdirs()) }
            schema.resolve("Model.graphqls").writeText(
                "interface Node { id: ID! } type Group implements Node { id: ID!, name: String }",
            )
            val policy = directory.resolve("persistence.yaml").apply { writeText("retryableTransactions: true") }
            val generated = directory.resolve("generated")
            HibernateSchemaModelWriter().write(PersistenceSchemaModelLoader.build(schema, policy), generated)
            test(directory, schema, policy, generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun classpath() = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
}

class RetryNamingStrategy : ViaductPhysicalNamingStrategy() {
    override fun toPhysicalTableName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier = Identifier.toIdentifier("audit_" + super.toPhysicalTableName(logicalName, jdbcEnvironment).text)

    override fun toPhysicalColumnName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier = Identifier.toIdentifier("audit_" + super.toPhysicalColumnName(logicalName, jdbcEnvironment).text)
}

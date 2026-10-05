package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.HibernateMetadataHandle
import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.hibernate.PersistenceModelYaml
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.assertEquals

/** The SDL must use unique type names; only its generated tables are created and removed. */
internal fun withGeneratedDatabase(
    database: Connection,
    sdl: String,
    settings: Map<String, String>,
    namingStrategy: String = ViaductPhysicalNamingStrategy::class.java.name,
    test: (PersistenceModel, File, File) -> Unit,
) {
    withGeneratedModel(sdl, settings, namingStrategy) { model, schemaFile, generated, handle ->
        handle.withTables(database, model) { test(model, schemaFile, generated) }
    }
}

internal fun withGeneratedModel(
    sdl: String,
    settings: Map<String, String>,
    namingStrategy: String = ViaductPhysicalNamingStrategy::class.java.name,
    test: (PersistenceModel, File, File, HibernateMetadataHandle) -> Unit,
) {
    val directory = Files.createTempDirectory("pg-persistence-integration-").toFile()
    try {
        val schema = directory.resolve("schema").apply { check(mkdirs()) }
        val schemaFile = schema.resolve("Model.graphqls").apply { writeText(sdl) }
        val authored = PersistenceSchemaModelLoader.build(schema, null)
        val model = PersistenceModelYaml.fromYaml(PersistenceModelYaml.toYaml(authored))
        assertEquals(authored, model)
        val generated = directory.resolve("generated")
        HibernateSchemaModelWriter().write(model, generated)
        val configuration =
            HibernateMetadataConfigurationFactory.create(
                HibernateMetadataConfigurationInput(
                    mappingFile = generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"),
                    classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
                    semanticModel = model,
                    physicalNamingStrategyClassName = namingStrategy,
                    hibernateSettings = settings,
                ),
            )
        HibernateMetadataBootstrap.build(configuration).use { handle ->
            test(model, schemaFile, generated, handle)
        }
    } finally {
        directory.deleteRecursively()
    }
}

private fun HibernateMetadataHandle.withTables(
    database: Connection,
    model: PersistenceModel,
    test: () -> Unit,
) {
    val effective = EffectiveHibernateModelBuilder.build(metadata, model)
    metadata.buildSessionFactory().use { factory ->
        try {
            factory.schemaManager.exportMappedObjects(false)
            JdbcOperations.execute(database, PostgresqlOverlay.renderMigration(effective))
            JdbcOperations.execute(database, PgGraphqlOverlay.render(effective))
            test()
        } finally {
            factory.schemaManager.dropMappedObjects(false)
        }
    }
}

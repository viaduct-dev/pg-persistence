package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import dev.viaduct.persistence.pggraphql.overlay.RetryableTransactionOverlay
import dev.viaduct.persistence.postgresql.PostgresqlOverlay
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class BuildEffectiveHibernateModelTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @get:Classpath
    abstract val modelClasspath: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val implicitNamingStrategyClassName: Property<String>

    @get:Input
    @get:Optional
    abstract val physicalNamingStrategyClassName: Property<String>

    @get:Input
    abstract val metadataCustomizerClassNames: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        metadataCustomizerClassNames.convention(emptyList())
    }

    @TaskAction
    fun buildEffectiveModel() {
        val semanticModel =
            PersistenceSchemaModelLoader.build(
                centralSchemaDirectory = centralSchemaDirectory.get().asFile,
                persistenceConfigFile = persistenceConfigFile.files.singleOrNull(),
            )
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val configuration = createMetadataConfiguration(semanticModel)
        HibernateMetadataBootstrap.build(configuration).use { handle ->
            writeOutputs(semanticModel, handle, output)
        }
    }

    private fun createMetadataConfiguration(semanticModel: PersistenceModel) =
        HibernateMetadataConfigurationFactory.create(
            HibernateMetadataConfigurationInput(
                mappingFile = mappingFile.get().asFile,
                classpath = modelClasspath.files.toList(),
                semanticModel = semanticModel,
                implicitNamingStrategyClassName =
                    implicitNamingStrategyClassName.orNull
                        ?: ViaductImplicitNamingStrategy::class.java.name,
                physicalNamingStrategyClassName =
                    physicalNamingStrategyClassName.orNull
                        ?: ViaductPhysicalNamingStrategy::class.java.name,
                metadataCustomizerClassNames = metadataCustomizerClassNames.get(),
            ),
        )

    private fun writeOutputs(
        semanticModel: PersistenceModel,
        handle: dev.viaduct.persistence.hibernate.HibernateMetadataHandle,
        output: java.io.File,
    ) {
        val effectiveModel =
            EffectiveHibernateModelBuilder.build(
                metadata = handle.metadata,
                semanticModel = semanticModel,
            )
        PostgresqlOverlay.write(effectiveModel, output)
        PgGraphqlOverlay.write(effectiveModel, output)
        val transactions = RetryableTransactionOverlay.render(handle.metadata)
        val prerequisites = RetryableTransactionOverlay.prerequisites(handle.metadata)
        output.resolve("META-INF/postgresql-prerequisites.sql").appendText(prerequisites)
        output.resolve("META-INF/pg-graphql-transactions.sql").writeText(transactions)
        output.resolve("META-INF/pg-graphql-metadata.sql").writeText(
            PostgresqlOverlay.renderRepeatable(effectiveModel) +
                PgGraphqlOverlay.render(effectiveModel) + transactions,
        )
        output.resolve("META-INF/pg-graphql.sql").writeText(
            PostgresqlOverlay.renderPrerequisites(effectiveModel) +
                prerequisites +
                PostgresqlOverlay.renderMigration(effectiveModel) +
                PostgresqlOverlay.renderRepeatable(effectiveModel) +
                PgGraphqlOverlay.render(effectiveModel) + transactions,
        )
        logger.lifecycle(
            "Built effective Hibernate model for " +
                effectiveModel.entities.joinToString { it.graphqlName },
        )
    }
}

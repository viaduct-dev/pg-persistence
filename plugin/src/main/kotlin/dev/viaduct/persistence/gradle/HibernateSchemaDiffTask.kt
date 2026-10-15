package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HibernateMetadataBootstrap
import dev.viaduct.persistence.hibernate.HibernateMetadataConfiguration
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.io.ensureParentDirectory
import dev.viaduct.persistence.liquibase.LiquibaseDatabaseSession
import dev.viaduct.persistence.liquibase.ViaductHibernateDatabase
import liquibase.command.CommandScope
import liquibase.command.core.DiffChangelogCommandStep
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep
import liquibase.command.core.helpers.PreCompareCommandStep
import liquibase.command.core.helpers.ReferenceDbUrlConnectionCommandStep
import liquibase.database.jvm.JdbcConnection
import liquibase.diff.output.ObjectChangeFilter
import liquibase.diff.output.StandardObjectChangeFilter
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.OutputStream

/** Writes a Liquibase SQL diff limited to the configured consumer's mapped tables. */
abstract class HibernateSchemaDiffTask : PersistenceSchemaTask() {
    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @get:Classpath
    abstract val modelClasspath: ConfigurableFileCollection

    @get:Input
    abstract val implicitNamingStrategyClassName: Property<String>

    @get:Input
    abstract val physicalNamingStrategyClassName: Property<String>

    @get:Input
    abstract val metadataCustomizerClassNames: ListProperty<String>

    @get:Input
    abstract val targetUrl: Property<String>

    @get:Input
    abstract val targetUsername: Property<String>

    @get:Internal
    abstract val targetPassword: Property<String>

    @get:OutputFile
    abstract val diffFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
        metadataCustomizerClassNames.convention(emptyList())
    }

    @TaskAction
    fun diff() {
        val destination = diffFile.get().asFile
        destination.ensureParentDirectory()
        check(!destination.exists() || destination.delete()) {
            "Could not replace schema diff ${destination.absolutePath}"
        }
        val configuration = createMetadataConfiguration()
        executeDiff(destination, configuration, tableFilter(configuration))
    }

    private fun createMetadataConfiguration(): HibernateMetadataConfiguration {
        val semanticModel =
            PersistenceSchemaModelLoader.build(
                centralSchemaDirectory.get().asFile,
                persistenceConfigFile.files.singleOrNull(),
                modulePlacementArtifacts = modulePlacementArtifacts.files,
                localModuleIdentity = localModuleIdentity.orNull,
            )
        return HibernateMetadataConfigurationFactory.create(
            HibernateMetadataConfigurationInput(
                mappingFile = mappingFile.get().asFile,
                classpath = modelClasspath.files.toList(),
                semanticModel = semanticModel,
                implicitNamingStrategyClassName = implicitNamingStrategyClassName.get(),
                physicalNamingStrategyClassName = physicalNamingStrategyClassName.get(),
                metadataCustomizerClassNames = metadataCustomizerClassNames.get(),
            ),
        )
    }

    private fun tableFilter(configuration: HibernateMetadataConfiguration): ObjectChangeFilter =
        HibernateMetadataBootstrap
            .build(configuration)
            .use { handle ->
                val tables = handle.metadata.collectTableMappings().filter { it.isPhysicalTable }
                if (configuration.semanticModel?.retryableTransactions == true ||
                    configuration.semanticModel?.entities?.any { it.databaseSchema != null } == true
                ) {
                    QualifiedTableChangeFilter(tables.map { (it.schema ?: "public") to it.name }.toSet())
                } else {
                    // Preserve Liquibase's legacy Hibernate single-schema normalization.
                    StandardObjectChangeFilter(
                        StandardObjectChangeFilter.FilterType.INCLUDE,
                        "table:(" +
                            tables
                                .map { Regex.escape(it.name) }
                                .distinct()
                                .sorted()
                                .joinToString("|") + ")",
                    )
                }
            }

    private fun executeDiff(
        destination: java.io.File,
        configuration: HibernateMetadataConfiguration,
        tableFilter: ObjectChangeFilter,
    ) {
        ViaductHibernateDatabase.reference(configuration).use { metadataReference ->
            LiquibaseDatabaseSession
                .open(metadataReference.url)
                .use { reference ->
                    LiquibaseDatabaseSession
                        .open(targetUrl.get(), targetUsername.get(), targetPassword.get())
                        .use { target ->
                            verifyPlacement(configuration, target)
                            CommandScope("diffChangelog")
                                .includeMappedSchemas(configuration, comparison = true)
                                .addArgumentValue(
                                    ReferenceDbUrlConnectionCommandStep.REFERENCE_DATABASE_ARG,
                                    reference.database,
                                ).addArgumentValue(
                                    DbUrlConnectionArgumentsCommandStep.DATABASE_ARG,
                                    target.database,
                                ).addArgumentValue(
                                    PreCompareCommandStep.OBJECT_CHANGE_FILTER_ARG,
                                    tableFilter,
                                ).addArgumentValue(
                                    DiffChangelogCommandStep.CHANGELOG_FILE_ARG,
                                    destination.absolutePath,
                                ).setOutput(OutputStream.nullOutputStream())
                                .execute()
                        }
                }
        }
    }

    private fun verifyPlacement(
        configuration: HibernateMetadataConfiguration,
        target: LiquibaseDatabaseSession,
    ) {
        HibernateMetadataBootstrap.build(configuration).use { handle ->
            SchemaPlacementMigrationGuard.verify(
                handle.metadata,
                (target.database.connection as JdbcConnection).wrappedConnection,
            )
        }
    }
}

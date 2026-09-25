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
import liquibase.command.core.helpers.DiffOutputControlCommandStep
import liquibase.command.core.helpers.ReferenceDbUrlConnectionCommandStep
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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.OutputStream

/** Writes an unfiltered Liquibase SQL diff against the configured consumer database. */
abstract class HibernateSchemaDiffTask : DefaultTask() {
    @get:InputDirectory
    abstract val centralSchemaDirectory: DirectoryProperty

    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @get:Classpath
    abstract val modelClasspath: ConfigurableFileCollection

    @get:InputFiles
    abstract val persistenceConfigFile: ConfigurableFileCollection

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
        executeDiff(destination, configuration, tablePattern(configuration))
    }

    private fun createMetadataConfiguration(): HibernateMetadataConfiguration {
        val semanticModel =
            PersistenceSchemaModelLoader.build(
                centralSchemaDirectory.get().asFile,
                persistenceConfigFile.files.singleOrNull(),
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

    private fun tablePattern(configuration: HibernateMetadataConfiguration): String =
        HibernateMetadataBootstrap
            .build(configuration)
            .use { handle ->
                handle.metadata
                    .collectTableMappings()
                    .filter { it.isPhysicalTable }
                    .map { it.name }
                    .distinct()
                    .sorted()
                    .joinToString("|")
            }

    private fun executeDiff(
        destination: java.io.File,
        configuration: HibernateMetadataConfiguration,
        tablePattern: String,
    ) {
        ViaductHibernateDatabase.reference(configuration).use { metadataReference ->
            LiquibaseDatabaseSession
                .open(metadataReference.url)
                .use { reference ->
                    LiquibaseDatabaseSession
                        .open(targetUrl.get(), targetUsername.get(), targetPassword.get())
                        .use { target ->
                            CommandScope("diffChangelog")
                                .includeMappedSchemas(configuration, comparison = true)
                                .addArgumentValue(
                                    ReferenceDbUrlConnectionCommandStep.REFERENCE_DATABASE_ARG,
                                    reference.database,
                                ).addArgumentValue(
                                    DbUrlConnectionArgumentsCommandStep.DATABASE_ARG,
                                    target.database,
                                ).addArgumentValue(
                                    DiffOutputControlCommandStep.INCLUDE_OBJECTS,
                                    "table:($tablePattern)",
                                ).addArgumentValue(
                                    DiffChangelogCommandStep.CHANGELOG_FILE_ARG,
                                    destination.absolutePath,
                                ).setOutput(OutputStream.nullOutputStream())
                                .execute()
                        }
                }
        }
    }
}

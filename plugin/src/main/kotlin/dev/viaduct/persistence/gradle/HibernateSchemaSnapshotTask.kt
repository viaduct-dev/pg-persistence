package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationFactory
import dev.viaduct.persistence.hibernate.HibernateMetadataConfigurationInput
import dev.viaduct.persistence.io.ensureParentDirectory
import dev.viaduct.persistence.liquibase.LiquibaseDatabaseSession
import dev.viaduct.persistence.liquibase.ViaductHibernateDatabase
import liquibase.command.CommandScope
import liquibase.command.core.SnapshotCommandStep
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep
import liquibase.database.Database
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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.FileOutputStream

/** Writes a review-only JSON snapshot of the generated Hibernate model. */
abstract class HibernateSchemaSnapshotTask : DefaultTask() {
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

    @get:OutputFile
    abstract val snapshotFile: RegularFileProperty

    init {
        metadataCustomizerClassNames.convention(emptyList())
    }

    @TaskAction
    fun snapshot() {
        val destination = snapshotFile.get().asFile
        destination.ensureParentDirectory()
        val semanticModel =
            PersistenceSchemaModelLoader.build(
                centralSchemaDirectory.get().asFile,
                persistenceConfigFile.files.singleOrNull(),
            )
        val configuration =
            HibernateMetadataConfigurationFactory.create(
                HibernateMetadataConfigurationInput(
                    mappingFile = mappingFile.get().asFile,
                    classpath = modelClasspath.files.toList(),
                    semanticModel = semanticModel,
                    implicitNamingStrategyClassName = implicitNamingStrategyClassName.get(),
                    physicalNamingStrategyClassName = physicalNamingStrategyClassName.get(),
                    metadataCustomizerClassNames = metadataCustomizerClassNames.get(),
                ),
            )
        ViaductHibernateDatabase.reference(configuration).use { reference ->
            LiquibaseDatabaseSession
                .open(reference.url)
                .use { session ->
                    val output = FileOutputStream(destination)
                    try {
                        CommandScope("snapshot")
                            .includeMappedSchemas(configuration)
                            .provideDependency(Database::class.java, session.database)
                            .addArgumentValue(
                                DbUrlConnectionArgumentsCommandStep.DATABASE_ARG,
                                session.database,
                            ).addArgumentValue(SnapshotCommandStep.SNAPSHOT_FORMAT_ARG, "json")
                            .setOutput(output)
                            .execute()
                    } finally {
                        output.close()
                    }
                }
        }
    }
}

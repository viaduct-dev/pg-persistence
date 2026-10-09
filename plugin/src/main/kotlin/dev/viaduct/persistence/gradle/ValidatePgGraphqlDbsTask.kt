package dev.viaduct.persistence.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class ValidatePgGraphqlDbsTask : PersistenceSchemaTask() {
    @get:InputFiles
    abstract val schemaContributionFiles: ConfigurableFileCollection

    @TaskAction
    fun validate() {
        PersistenceSchemaModelLoader.build(
            centralSchemaDirectory = centralSchemaDirectory.get().asFile,
            persistenceConfigFile = persistenceConfigFile.files.singleOrNull(),
            validateSelectiveResolvers = true,
            schemaContributions = schemaContributionFiles.files,
            validatePgGraphqlFields = validatePgGraphqlFields.get(),
        )
    }
}

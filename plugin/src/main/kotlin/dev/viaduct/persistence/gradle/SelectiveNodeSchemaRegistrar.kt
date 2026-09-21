package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import viaduct.gradle.ViaductSchemaContributions

/** Contributes persistence defaults before Viaduct assembles the application schema. */
internal object SelectiveNodeSchemaRegistrar {
    fun register(
        project: Project,
        extension: ViaductPgPersistenceExtension,
    ) {
        val moduleSchemaPartition = project.tasks.named("prepareViaductSchemaPartition")
        val generate =
            project.tasks.register(
                "generateViaductPgPersistenceSchemaContributions",
                GenerateSelectiveNodeSchemaContributionsTask::class.java,
            ) {
                it.group = "viaduct"
                it.description = "Contribute selective node resolvers for this persistence module."
                it.schemaFiles.from(moduleSchemaPartition)
                it.persistenceConfigFile.from(extension.persistenceConfigFile)
                it.outputDirectory.set(
                    project.layout.buildDirectory.dir(
                        "generated/viaduct-persistence-schema-contributions",
                    ),
                )
            }
        val contributionFiles = project.objects.fileCollection().from(generate)
        project.extensions
            .getByType(ViaductSchemaContributions::class.java)
            .register("dev.viaduct.pg-persistence", contributionFiles)
    }
}

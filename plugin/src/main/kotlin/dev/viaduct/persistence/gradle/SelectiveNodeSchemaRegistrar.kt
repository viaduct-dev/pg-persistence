@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import viaduct.gradle.task.AssembleSchemaPartitionTask

/** Supplies persistence defaults before Viaduct assembles, compiles, and packages the schema. */
internal object SelectiveNodeSchemaRegistrar {
    fun register(
        project: Project,
        extension: ViaductPgPersistenceExtension,
    ) {
        val generate =
            project.tasks.register("prepareViaductPgPersistenceSchema", PreparePersistenceSchemaTask::class.java) {
                it.group = "viaduct"
                it.description = "Enable selective node resolvers in this persistence module's generated schema."
                it.sourceDirectory.set(project.layout.projectDirectory.dir("src/main/viaduct/schema"))
                it.persistenceConfigFile.from(extension.persistenceConfigFile)
                it.outputDirectory.set(project.layout.buildDirectory.dir("generated/viaduct-persistence-schema"))
            }
        project.tasks.named("prepareViaductSchemaPartition", AssembleSchemaPartitionTask::class.java).configure {
            it.graphqlSrcDir.set(generate.flatMap { task -> task.outputDirectory })
            it.schemaFiles.setFrom(generate.flatMap { task -> task.outputDirectory }.map { dir -> dir.asFileTree })
        }
    }
}

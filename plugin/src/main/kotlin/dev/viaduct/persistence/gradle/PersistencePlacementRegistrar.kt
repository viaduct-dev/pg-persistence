package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import viaduct.gradle.ViaductMetamoduleExtension
import java.security.MessageDigest

/** Reuses Viaduct's schema artifact graph; no cross-project task lookup or runtime classpath scan. */
internal object PersistencePlacementRegistrar {
    const val FILE_NAME = "pg-persistence-placement.json"

    fun registerModule(
        project: Project,
        extension: ViaductPgPersistenceExtension,
    ) {
        val central = project.extensions.getByType(ViaductMetamoduleExtension::class.java).centralSchemaConfiguration
        extension.centralSchemaDirectory.convention(project.layout.dir(central.elements.map { it.single().asFile }))
        val metadata =
            project.tasks.register(
                "generateViaductPersistencePlacement",
                GeneratePersistencePlacementTask::class.java,
            ) {
                it.moduleIdentity.set(
                    project.provider {
                        project.extensions
                            .getByType(ViaductMetamoduleExtension::class.java)
                            .layout.fullTenantPackage
                    },
                )
                it.schemaFiles.from(
                    project.fileTree("src/main/viaduct/schema") { tree -> tree.include("**/*.graphqls") },
                )
                it.persistenceConfigFile.from(project.persistencePolicyFile())
                it.outputFile.set(
                    project.layout.buildDirectory.file("generated/viaduct-persistence-placement/$FILE_NAME"),
                )
            }
        project.configurations.named("viaductSchemaPartition").configure {
            it.outgoing.artifact(metadata.flatMap { task -> task.outputFile })
        }
        project.tasks.withType(PersistenceSchemaTask::class.java).configureEach {
            it.dependsOn(central)
            it.modulePlacementArtifacts.from(metadata.flatMap { task -> task.outputFile })
            it.localModuleIdentity.set(metadata.flatMap { task -> task.moduleIdentity })
        }
    }

    fun registerApplication(project: Project) {
        project.tasks.withType(PersistenceSchemaTask::class.java).configureEach {
            it.modulePlacementArtifacts.from(project.configurations.named("viaductAllSchemaPartitionsIn"))
        }
        // Viaduct intentionally copies only SDL. Keep persistence metadata alongside its output,
        // where module consumers can read it without changing public types or directives.
        project.tasks.named("assembleViaductCentralSchema").configure { task ->
            val localPlacements = project.tasks.withType(GeneratePersistencePlacementTask::class.java)
            task.dependsOn(localPlacements)
            task.inputs.files(project.provider { localPlacements.map { it.outputFile.get().asFile } })
            task.inputs.property(
                "persistenceModuleComponents",
                project.provider {
                    project.configurations
                        .getByName("viaductAllSchemaPartitionsIn")
                        .incoming.artifacts.artifacts
                        .map { "${it.id.componentIdentifier.displayName}:${it.file.absolutePath}" }
                        .sorted()
                },
            )
            task.doLast {
                val destination =
                    task.outputs.files.singleFile
                        .resolve(".persistence")
                check(!destination.exists() || destination.deleteRecursively()) {
                    "Could not replace persistence placement metadata in $destination"
                }
                val localArtifacts = localPlacements.flatMap { it.schemaFiles.files + it.outputFile.get().asFile }
                val artifacts = project.configurations.getByName("viaductAllSchemaPartitionsIn").files + localArtifacts
                val components =
                    project.configurations
                        .getByName("viaductAllSchemaPartitionsIn")
                        .incoming.artifacts.artifacts
                        .groupBy { it.id.componentIdentifier }
                        .values
                        .map { component -> component.map { it.file } }
                PersistenceModuleManifest.write(destination, components, localArtifacts)
                PersistencePlacementArtifacts.files(artifacts).forEach { file ->
                    val contents = file.readBytes()
                    val key =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(contents)
                            .joinToString("") { "%02x".format(it) }
                    val directory = destination.resolve(key)
                    check(directory.isDirectory || directory.mkdirs())
                    file.copyTo(directory.resolve(FILE_NAME), overwrite = true)
                }
            }
        }
    }
}

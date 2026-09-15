package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** Registers schema validation, dynamic Hibernate mapping generation, and resource wiring. */
internal class PersistenceGenerationRegistrar(
    private val project: Project,
    private val extension: ViaductPgPersistenceExtension,
    private val layout: PersistenceBuildLayout,
) {
    fun register() {
        val validate =
            project.tasks.register(
                "validateViaductPgPersistenceSchema",
                ValidatePgGraphqlDbsTask::class.java,
            ) {
                it.group = "verification"
                it.centralSchemaDirectory.set(extension.centralSchemaDirectory)
                it.persistenceConfigFile.from(extension.persistenceConfigFile)
                dependOnCentralSchemaAssemblyIfPresent(it)
            }
        val generate =
            project.tasks.register(
                "generateViaductPgPersistenceModel",
                GenerateHibernateSchemaModelTask::class.java,
            ) {
                it.group = "build"
                it.description =
                    "Generate dynamic Hibernate mappings from the assembled Viaduct schema."
                dependOnCentralSchemaAssemblyIfPresent(it)
                it.centralSchemaDirectory.set(extension.centralSchemaDirectory)
                it.outputDirectory.set(layout.generatedRoot)
                it.replacementHbmXml.set(extension.replacementHbmXml)
                it.persistenceConfigFile.from(extension.persistenceConfigFile)
            }
        wireGeneratedSources(validate, generate)
    }

    /**
     * `assembleViaductCentralSchema` is registered by `com.airbnb.viaduct.application-gradle-plugin`
     * and only exists on a Viaduct *application* project — a Viaduct *module* project (e.g. a
     * dedicated persistence tenant with no sibling application in the same Gradle project) never
     * has it. Depend on it when present (today's monolithic single-project consumers, unchanged);
     * skip the dependency otherwise and rely on [ViaductPgPersistenceExtensionDefaults]'s
     * `centralSchemaDirectory` convention falling back to the module's own local schema directory.
     */
    private fun dependOnCentralSchemaAssemblyIfPresent(task: org.gradle.api.Task) {
        if (project.tasks.names.contains("assembleViaductCentralSchema")) {
            task.dependsOn("assembleViaductCentralSchema")
        }
    }

    private fun wireGeneratedSources(
        validate: TaskProvider<ValidatePgGraphqlDbsTask>,
        generate: TaskProvider<GenerateHibernateSchemaModelTask>,
    ) {
        val generatedResources =
            project
                .files(
                    layout.generatedRoot.map { it.dir("resources") },
                ).builtBy(generate)
        layout.mainSourceSet.resources.srcDir(generatedResources)
        project.tasks.named("compileKotlin").configure {
            it.dependsOn(validate, generate)
        }
        project.tasks
            .matching {
                it.name == "kspKotlin" ||
                    (it.name.startsWith("kapt") && it.name.endsWith("Kotlin"))
            }.configureEach {
                it.dependsOn(generate)
            }
        project.tasks.named("processResources").configure {
            it.dependsOn(generate)
        }
    }
}

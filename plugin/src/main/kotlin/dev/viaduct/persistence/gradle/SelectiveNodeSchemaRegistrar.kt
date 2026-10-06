package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import viaduct.gradle.ViaductSchemaContributions

internal object SelectiveNodeSchemaRegistrar {
    fun register(
        project: Project,
        extension: ViaductPgPersistenceExtension,
    ) {
        val parser =
            project.configurations.create("viaductPgPersistenceResolverParser") {
                it.isCanBeConsumed = false
                it.isCanBeResolved = true
            }
        project.dependencies.add(parser.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
        val generate =
            project.tasks.register(
                "generateViaductPgPersistenceSchemaContributions",
                GenerateSelectiveNodeSchemaContributionsTask::class.java,
            ) {
                it.group = "viaduct"
                it.parserClasspath.from(parser)
                it.description = "Infer selective node resolver metadata from Kotlin resolver declarations."
                it.schemaFiles.from(
                    project.fileTree("src/main/viaduct/schema") { tree -> tree.include("**/*.graphqls") },
                )
                it.outputDirectory.set(
                    project.layout.buildDirectory.dir(
                        "generated/viaduct-persistence-schema-contributions",
                    ),
                )
            }
        wireResolverSources(project, generate)
        project.extensions.getByType(ViaductSchemaContributions::class.java).register(
            "dev.viaduct.pg-persistence",
            project.objects.fileCollection().from(generate),
        )
        registerLocalValidation(project, extension, generate)
    }

    private fun wireResolverSources(
        project: Project,
        generate: TaskProvider<GenerateSelectiveNodeSchemaContributionsTask>,
    ) {
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
            generate.configure { task ->
                // Use directories, not the source-set FileCollection: generated resolver bases
                // depend on central assembly, which consumes this task's output.
                task.resolverSources.from(
                    project.provider {
                        val buildDirectory =
                            project.layout.buildDirectory
                                .get()
                                .asFile
                                .toPath()
                        kotlin.sourceSets
                            .getByName("main")
                            .kotlin.srcDirs
                            .filterNot { it.toPath().startsWith(buildDirectory) }
                            .map { directory ->
                                project.fileTree(directory) { it.include("**/*.kt") }
                            }
                    },
                )
            }
        }
    }

    private fun registerLocalValidation(
        project: Project,
        extension: ViaductPgPersistenceExtension,
        generate: TaskProvider<GenerateSelectiveNodeSchemaContributionsTask>,
    ) {
        project.tasks.withType(ValidatePgGraphqlDbsTask::class.java).configureEach { task ->
            // Module-only persistence validation reads local SDL rather than the central schema.
            // Supply its contribution too; application validation already sees the merged SDL.
            task.schemaContributionFiles.from(
                project.provider {
                    if (extension.centralSchemaDirectory.get().asFile == project.file("src/main/viaduct/schema")) {
                        generate
                            .get()
                            .outputDirectory
                            .get()
                            .asFileTree.files
                            .filter { it.extension == "graphqls" }
                    } else {
                        emptyList<java.io.File>()
                    }
                },
            )
            task.dependsOn(generate)
        }
    }
}

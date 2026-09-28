package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

private typealias EffectiveModelTask = TaskProvider<BuildEffectiveHibernateModelTask>
private typealias SchemaDiffTask = TaskProvider<HibernateSchemaDiffTask>

/** Registers the raw and conservative review-only Liquibase diff tasks. */
internal class HibernateDiffTaskRegistrar(
    private val project: Project,
    private val extension: ViaductPgPersistenceExtension,
    private val layout: PersistenceBuildLayout,
) {
    fun register(effective: TaskProvider<BuildEffectiveHibernateModelTask>) {
        val rawDiff = registerRaw(effective)
        project.tasks.register("hibernateSchemaDiff", ConservativeLiquibaseDiffTask::class.java) {
            it.group = "verification"
            it.description =
                "Write conservative and destructive-review Liquibase schema diffs."
            it.dependsOn(rawDiff)
            it.rawDiffFile.set(
                project.layout.buildDirectory.file(
                    "schema-diff/hibernate-raw-review.postgresql.sql",
                ),
            )
            it.migrationFile.set(
                project.layout.buildDirectory.file(
                    "schema-diff/hibernate-review.postgresql.sql",
                ),
            )
            it.destructiveReviewFile.set(
                project.layout.buildDirectory.file(
                    "schema-diff/hibernate-destructive-review.postgresql.sql",
                ),
            )
        }
    }

    private fun registerRaw(effective: EffectiveModelTask): SchemaDiffTask {
        val task =
            project.tasks.register(
                "hibernateSchemaDiffRaw",
                HibernateSchemaDiffTask::class.java,
            ) { task ->
                task.group = "verification"
                task.description =
                    "Write an unfiltered review-only Liquibase diff against a consumer database."
                task.dependsOn(effective)
                val diffFile =
                    project.layout.buildDirectory
                        .file("schema-diff/hibernate-raw-review.postgresql.sql")
                task.centralSchemaDirectory.set(extension.centralSchemaDirectory)
                task.mappingFile.set(
                    layout.generatedRoot.map { root ->
                        root.file("resources/META-INF/viaduct-persistence.hbm.xml")
                    },
                )
                task.modelClasspath.from(layout.mainSourceSet.runtimeClasspath)
                task.persistenceConfigFile.from(project.persistencePolicyFile())
                task.implicitNamingStrategyClassName.set(extension.implicitNamingStrategyClassName)
                task.physicalNamingStrategyClassName.set(extension.physicalNamingStrategyClassName)
                task.metadataCustomizerClassNames.set(extension.metadataCustomizerClassNames)
                task.targetUrl.set(extension.schemaDiffUrl)
                task.targetUsername.set(extension.schemaDiffUser)
                task.targetPassword.set(extension.schemaDiffPassword)
                task.diffFile.set(diffFile)
            }
        return task
    }
}

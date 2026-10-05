package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/** Registers the review-only Liquibase snapshot task. */
internal class HibernateSnapshotTaskRegistrar(
    private val project: Project,
    private val extension: ViaductPgPersistenceExtension,
    private val layout: PersistenceBuildLayout,
) {
    fun register(effective: TaskProvider<BuildEffectiveHibernateModelTask>) {
        project.tasks.register("hibernateSchemaSnapshot", HibernateSchemaSnapshotTask::class.java) { task ->
            task.group = "verification"
            task.description =
                "Write a review-only Liquibase snapshot of the generated Hibernate model."
            task.dependsOn(effective)
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
            task.snapshotFile.set(
                project.layout.buildDirectory.file("schema-diff/hibernate-snapshot.json"),
            )
        }
    }
}

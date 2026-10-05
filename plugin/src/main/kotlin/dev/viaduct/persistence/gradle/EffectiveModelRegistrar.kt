package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

/** Registers effective Hibernate metadata generation and packages its artifacts in the JAR. */
internal class EffectiveModelRegistrar(
    private val project: Project,
    private val extension: ViaductPgPersistenceExtension,
    private val layout: PersistenceBuildLayout,
) {
    fun register(): TaskProvider<BuildEffectiveHibernateModelTask> {
        val effective =
            project.tasks.register(
                "buildViaductEffectiveModel",
                BuildEffectiveHibernateModelTask::class.java,
            ) {
                it.group = "build"
                it.description =
                    "Build effective Hibernate metadata and database overlay artifacts."
                it.dependsOn("classes")
                it.centralSchemaDirectory.set(extension.centralSchemaDirectory)
                it.persistenceConfigFile.from(project.persistencePolicyFile())
                it.mappingFile.set(
                    layout.generatedRoot.map { root ->
                        root.file("resources/META-INF/viaduct-persistence.hbm.xml")
                    },
                )
                it.modelClasspath.from(layout.mainSourceSet.runtimeClasspath)
                it.implicitNamingStrategyClassName.set(extension.implicitNamingStrategyClassName)
                it.physicalNamingStrategyClassName.set(extension.physicalNamingStrategyClassName)
                it.metadataCustomizerClassNames.set(extension.metadataCustomizerClassNames)
                it.outputDirectory.set(layout.effectiveRoot)
            }
        project.tasks.named("jar", Jar::class.java).configure {
            it.dependsOn(effective)
            val copiedTask = it.from(layout.effectiveRoot) { spec -> spec.into("") }
            it.logger.debug("Attached effective metadata to ${copiedTask.name}")
        }
        return effective
    }
}

package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.io.ensureDirectory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/** Keeps Kotlin compiler internals out of the consumer's Gradle plugin classpath. */
internal abstract class GenerateSelectiveNodeSchemaContributionsWork :
    WorkAction<GenerateSelectiveNodeSchemaContributionsWork.Parameters> {
    interface Parameters : WorkParameters {
        val schemas: MapProperty<String, String>
        val sources: MapProperty<String, String>
        val outputDirectory: DirectoryProperty
    }

    override fun execute() {
        val contribution =
            SelectiveNodeSchema.contributions(
                parameters.schemas.get(),
                NodeResolverSourceDiscovery.discover(parameters.sources.get()),
            )
        val output = parameters.outputDirectory.get().asFile
        val file = output.resolve("pg-persistence.graphqls")
        output.ensureDirectory()
        if (contribution.isEmpty()) {
            check(!file.exists() || file.delete()) { "Could not remove stale node resolver metadata: $file" }
        } else {
            file.writeText(contribution)
        }
    }
}

package dev.viaduct.persistence.gradle

import java.io.File

internal object PersistencePlacementArtifacts {
    fun files(artifacts: Collection<File>): List<File> =
        artifacts
            .flatMap { artifact ->
                if (artifact.isDirectory) artifact.walkTopDown().filter { it.isFile }.toList() else listOf(artifact)
            }.filter { it.name == PersistencePlacementRegistrar.FILE_NAME }
            .distinctBy { it.canonicalPath }
            .sorted()
}

package dev.viaduct.persistence.gradle

import java.io.File

internal object PersistencePlacementArtifacts {
    fun files(
        artifacts: Collection<File>,
        name: String = PersistencePlacementRegistrar.FILE_NAME,
    ): List<File> =
        artifacts
            .flatMap { artifact ->
                if (artifact.isDirectory) artifact.walkTopDown().filter { it.isFile }.toList() else listOf(artifact)
            }.filter { it.name == name }
            .distinctBy { it.canonicalPath }
            .sorted()
}

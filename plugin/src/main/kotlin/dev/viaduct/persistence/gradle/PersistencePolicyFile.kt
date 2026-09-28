package dev.viaduct.persistence.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

internal const val PERSISTENCE_POLICY_PATH = "src/main/viaduct/pg-persistence.yaml"
private const val LEGACY_PERSISTENCE_POLICY_PATH = "src/main/viaduct/persistence.yaml"

/** Uses the legacy file when present so the loader can emit its actionable migration error. */
internal fun Project.persistencePolicyFile(): Provider<File> =
    provider {
        val legacy = file(LEGACY_PERSISTENCE_POLICY_PATH)
        if (legacy.exists()) legacy else file(PERSISTENCE_POLICY_PATH)
    }

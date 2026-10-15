package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModelPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Central generation must see the same semantic policies used by the declaring modules. */
internal object PersistenceModulePolicies {
    fun merge(
        root: PersistenceModelPolicy,
        artifacts: Collection<File>,
    ): PersistenceModelPolicy {
        val modules = sortedMapOf<String, PersistenceModelPolicy>()
        PersistencePlacementArtifacts.files(artifacts).forEach { file ->
            val document = Json.parseToJsonElement(file.readText()).jsonObject
            val module = document.getValue("module").jsonPrimitive.content
            val policy = PersistenceConfig.parse(document["policyYaml"]?.jsonPrimitive?.content.orEmpty(), module)
            if ("policyYaml" in document) {
                val schema = document.getValue("databaseSchema").jsonPrimitive.content
                require(schema == (policy.databaseSchema ?: "public")) {
                    "$file: placement disagrees with databaseSchema in module policy"
                }
            }
            val previous = modules.putIfAbsent(module, policy)
            require(previous == null || previous == policy) { "Conflicting persistence policies for module $module" }
        }
        return modules.values.fold(root) { policy, module ->
            policy.copy(
                deniedTypeNames = policy.deniedTypeNames + module.deniedTypeNames,
                semanticNotNullTypeNames = policy.semanticNotNullTypeNames + module.semanticNotNullTypeNames,
                semanticNotNullFieldCoordinates =
                    policy.semanticNotNullFieldCoordinates + module.semanticNotNullFieldCoordinates,
                unidirectionalTargetForeignKeyFields =
                    policy.unidirectionalTargetForeignKeyFields + module.unidirectionalTargetForeignKeyFields,
                inverseFieldOverrides = combine(policy.inverseFieldOverrides, module.inverseFieldOverrides),
                uniqueKeysByType = combine(policy.uniqueKeysByType, module.uniqueKeysByType),
                indexesByType = combine(policy.indexesByType, module.indexesByType),
                retryableTransactions = policy.retryableTransactions || module.retryableTransactions,
            )
        }
    }

    private fun <T> combine(
        left: Map<String, T>,
        right: Map<String, T>,
    ): Map<String, T> {
        (left.keys intersect right.keys).forEach { key ->
            require(left[key] == right[key]) { "Conflicting persistence policies at $key" }
        }
        return left + right
    }
}

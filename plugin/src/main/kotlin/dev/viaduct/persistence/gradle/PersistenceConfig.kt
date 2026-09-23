package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModelPolicy
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/** Schema-adjacent persistence policy loaded from YAML. */
internal object PersistenceConfig {
    fun load(file: File?): PersistenceModelPolicy {
        if (file == null || !file.exists() || file.readText().isBlank()) return PersistenceModelPolicy()
        val path = file.path
        val loaderOptions = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val document =
            try {
                Yaml(loaderOptions).load<Any?>(file.readText())
            } catch (exception: YAMLException) {
                throw IllegalArgumentException("$path: invalid YAML: ${exception.message}", exception)
            }
        val root = map(document, path, "document")
        root.requireOnly(
            path,
            "document",
            setOf("denyList", "semanticNotNull", "relationships", "retryableTransactions"),
        )
        val retryableTransactions = root["retryableTransactions"] ?: false
        require(retryableTransactions is Boolean) { "$path: retryableTransactions must be a boolean" }

        val denyList = optionalMap(root["denyList"], path, "denyList")
        denyList.requireOnly(path, "denyList", setOf("types"))
        val semanticNotNull = optionalMap(root["semanticNotNull"], path, "semanticNotNull")
        semanticNotNull.requireOnly(path, "semanticNotNull", setOf("types", "fields"))
        val relationships = optionalMap(root["relationships"], path, "relationships")
        relationships.requireOnly(
            path,
            "relationships",
            setOf("unidirectionalTargetForeignKeyFields", "inverseFieldOverrides"),
        )

        return PersistenceModelPolicy(
            retryableTransactions = retryableTransactions,
            deniedTypeNames = stringSet(denyList["types"], path, "denyList.types"),
            semanticNotNullTypeNames =
                stringSet(semanticNotNull["types"], path, "semanticNotNull.types"),
            semanticNotNullFieldCoordinates =
                stringSet(semanticNotNull["fields"], path, "semanticNotNull.fields"),
            unidirectionalTargetForeignKeyFields =
                stringSet(
                    relationships["unidirectionalTargetForeignKeyFields"],
                    path,
                    "relationships.unidirectionalTargetForeignKeyFields",
                ),
            inverseFieldOverrides =
                stringMap(
                    relationships["inverseFieldOverrides"],
                    path,
                    "relationships.inverseFieldOverrides",
                ),
        )
    }

    private fun map(
        value: Any?,
        path: String,
        key: String,
    ): Map<String, Any?> {
        require(value is Map<*, *>) { "$path: $key must be a YAML mapping" }
        return value.entries.associate { (entryKey, entryValue) ->
            require(entryKey is String) { "$path: $key contains a non-string key" }
            entryKey to entryValue
        }
    }

    private fun optionalMap(
        value: Any?,
        path: String,
        key: String,
    ): Map<String, Any?> = if (value == null) emptyMap() else map(value, path, key)

    private fun Map<String, Any?>.requireOnly(
        path: String,
        key: String,
        allowed: Set<String>,
    ) {
        val unknown = keys - allowed
        require(unknown.isEmpty()) { "$path: $key contains unknown key(s): ${unknown.sorted().joinToString()}" }
    }

    private fun stringSet(
        value: Any?,
        path: String,
        key: String,
    ): Set<String> {
        if (value == null) return emptySet()
        require(value is List<*>) { "$path: $key must be a YAML list of strings" }
        val values =
            value.map {
                require(it is String) { "$path: $key must contain only strings" }
                require(it.isNotBlank()) { "$path: $key must not contain blank coordinates" }
                it
            }
        val duplicates =
            values
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicates.isEmpty()) {
            "$path: $key contains duplicate value(s): ${duplicates.sorted().joinToString()}"
        }
        return values.toCollection(linkedSetOf())
    }

    private fun stringMap(
        value: Any?,
        path: String,
        key: String,
    ): Map<String, String> {
        if (value == null) return emptyMap()
        val values = map(value, path, key)
        return values.mapValues { (entryKey, entryValue) ->
            require(entryValue is String && entryValue.isNotBlank()) {
                "$path: $key.$entryKey must be a non-blank string"
            }
            entryValue
        }
    }
}

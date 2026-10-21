package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceModelPolicy
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/** Schema-adjacent persistence policy loaded from YAML. */
@Suppress("TooManyFunctions") // File loading and embedded module policies share one strict YAML parser.
internal object PersistenceConfig {
    fun load(file: File?): PersistenceModelPolicy {
        if (file == null || !file.exists() || file.readText().isBlank()) return PersistenceModelPolicy()
        val path = file.path
        require(file.name != "persistence.yaml") {
            "$path: persistence policy moved to src/main/viaduct/pg-persistence.yaml; rename the file " +
                "and migrate from top-level policy indexes to the type-first 'types' mapping"
        }
        return parse(file.readText(), path)
    }

    fun parse(
        text: String,
        path: String,
    ): PersistenceModelPolicy {
        if (text.isBlank()) return PersistenceModelPolicy()
        val loaderOptions = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val document =
            try {
                Yaml(loaderOptions).load<Any?>(text)
            } catch (exception: YAMLException) {
                throw IllegalArgumentException("$path: invalid YAML: ${exception.message}", exception)
            }
        val root = map(document, path, "document")
        val legacyKeys = root.keys intersect setOf("denyList", "semanticNotNull", "relationships")
        require(legacyKeys.isEmpty()) {
            "$path: legacy feature-first persistence policy key(s) ${legacyKeys.sorted().joinToString()} " +
                "must be migrated under the type-first 'types' mapping"
        }
        root.requireOnly(path, "document", setOf("types", "retryableTransactions", "databaseSchema"))
        val databaseSchema = root["databaseSchema"]
        if ("databaseSchema" in root) {
            require(
                databaseSchema is String &&
                    databaseSchema.matches(Regex("[a-z_][a-z0-9_]{0,62}")) &&
                    !databaseSchema.startsWith("pg_"),
            ) { "$path: databaseSchema must be a lowercase PostgreSQL identifier of at most 63 bytes, excluding pg_*" }
        }
        val retryableTransactions = root["retryableTransactions"] ?: false
        require(retryableTransactions is Boolean) { "$path: retryableTransactions must be a boolean" }

        val policies = parseTypes(root["types"], path)

        return PersistenceModelPolicy(
            databaseSchema = databaseSchema as? String,
            retryableTransactions = retryableTransactions,
            deniedTypeNames = policies.deniedTypes,
            semanticNotNullTypeNames = policies.semanticNotNullTypes,
            semanticNotNullFieldCoordinates = policies.semanticNotNullFields,
            unidirectionalTargetForeignKeyFields = policies.targetForeignKeyFields,
            inverseFieldOverrides = policies.inverseFields,
            uniqueKeysByType = policies.uniqueKeysByType,
            indexesByType = policies.indexesByType,
        )
    }

    private fun parseTypes(
        value: Any?,
        path: String,
    ): ParsedPolicies =
        ParsedPolicies().also { parsed ->
            optionalMap(value, path, "types").forEach { (typeName, rawPolicy) ->
                parseType(typeName, rawPolicy, path, parsed)
            }
        }

    private fun parseType(
        typeName: String,
        rawPolicy: Any?,
        path: String,
        parsed: ParsedPolicies,
    ) {
        require(typeName.isNotBlank()) { "$path: types must not contain a blank type name" }
        val key = "types.$typeName"
        val policy = map(rawPolicy, path, key)
        policy.requireOnly(path, key, setOf("excluded", "semanticNotNull", "fields", "unique", "indexes"))
        val excluded = boolean(policy["excluded"], path, "$key.excluded")
        val semanticNotNull = boolean(policy["semanticNotNull"], path, "$key.semanticNotNull")
        if (excluded) parsed.deniedTypes += typeName
        if (semanticNotNull) parsed.semanticNotNullTypes += typeName
        val unique = if ("unique" in policy) fieldKeys(policy["unique"], path, "$key.unique") else emptyList()
        if (unique.isNotEmpty()) parsed.uniqueKeysByType[typeName] = unique
        val indexes = if ("indexes" in policy) fieldKeys(policy["indexes"], path, "$key.indexes") else emptyList()
        if (indexes.isNotEmpty()) parsed.indexesByType[typeName] = indexes
        val fields = optionalMap(policy["fields"], path, "$key.fields")
        fields.forEach { (fieldName, rawFieldPolicy) ->
            parseField(typeName, fieldName, rawFieldPolicy, path, parsed)
        }
        val effective =
            listOf(semanticNotNull, fields.isNotEmpty(), unique.isNotEmpty(), indexes.isNotEmpty()).any { it }
        require(excluded || effective) {
            "$path: $key has no effective persistence policy"
        }
        require(!excluded || !effective) {
            "$path: $key is excluded and cannot define additional persistence policies"
        }
    }

    private fun fieldKeys(
        value: Any?,
        path: String,
        key: String,
    ): List<List<String>> {
        require(value is List<*> && value.isNotEmpty()) { "$path: $key must be a non-empty list of field lists" }
        return value.map { fields ->
            require(fields is List<*> && fields.isNotEmpty()) { "$path: $key must contain non-empty field lists" }
            fields.map { field ->
                require(field is String && field.isNotBlank()) { "$path: $key fields must be non-blank strings" }
                field
            }
        }
    }

    private fun parseField(
        typeName: String,
        fieldName: String,
        rawPolicy: Any?,
        path: String,
        parsed: ParsedPolicies,
    ) {
        val typeKey = "types.$typeName"
        require(fieldName.isNotBlank()) { "$path: $typeKey.fields must not contain a blank field name" }
        val fieldKey = "$typeKey.fields.$fieldName"
        val coordinate = "$typeName.$fieldName"
        val policy = map(rawPolicy, path, fieldKey)
        policy.requireOnly(path, fieldKey, setOf("semanticNotNull", "relationship"))
        val semanticNotNull = boolean(policy["semanticNotNull"], path, "$fieldKey.semanticNotNull")
        if (semanticNotNull) parsed.semanticNotNullFields += coordinate
        val relationship = optionalMap(policy["relationship"], path, "$fieldKey.relationship")
        parseRelationship(coordinate, fieldKey, relationship, path, parsed)
        require(semanticNotNull || relationship.isNotEmpty()) { "$path: $fieldKey has no effective persistence policy" }
    }

    private fun parseRelationship(
        coordinate: String,
        fieldKey: String,
        relationship: Map<String, Any?>,
        path: String,
        parsed: ParsedPolicies,
    ) {
        relationship.requireOnly(path, "$fieldKey.relationship", setOf("storage", "inverseField"))
        if ("storage" in relationship) {
            val storage = relationship["storage"]
            require(storage == "targetForeignKey") {
                "$path: $fieldKey.relationship.storage must be 'targetForeignKey'"
            }
            parsed.targetForeignKeyFields += coordinate
        }
        if ("inverseField" in relationship) {
            val inverseField = relationship["inverseField"]
            require(inverseField is String && inverseField.isNotBlank()) {
                "$path: $fieldKey.relationship.inverseField must be a non-blank string"
            }
            parsed.inverseFields[coordinate] = inverseField
        }
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

    private fun boolean(
        value: Any?,
        path: String,
        key: String,
    ): Boolean {
        if (value == null) return false
        require(value is Boolean) { "$path: $key must be a boolean" }
        return value
    }

    private class ParsedPolicies {
        val deniedTypes = linkedSetOf<String>()
        val semanticNotNullTypes = linkedSetOf<String>()
        val semanticNotNullFields = linkedSetOf<String>()
        val targetForeignKeyFields = linkedSetOf<String>()
        val inverseFields = linkedMapOf<String, String>()
        val uniqueKeysByType = linkedMapOf<String, List<List<String>>>()
        val indexesByType = linkedMapOf<String, List<List<String>>>()
    }
}

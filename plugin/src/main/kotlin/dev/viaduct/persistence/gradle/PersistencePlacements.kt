package dev.viaduct.persistence.gradle

import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Applies original type ownership; references and extensions never acquire the caller's schema. */
internal object PersistencePlacements {
    fun ownedTypes(
        artifacts: Collection<File>,
        module: String?,
    ): Set<String>? = module?.let { identity -> read(artifacts).filterValues { it.module == identity }.keys }

    fun apply(
        model: PersistenceModel,
        schemaFiles: List<File>,
        artifacts: Collection<File>,
        localSchema: String?,
        localModule: String?,
    ): PersistenceModel {
        val definitions =
            schemaFiles
                .flatMap { file ->
                    Parser()
                        .parseDocument(file.readText())
                        .definitions
                        .filterIsInstance<ObjectTypeDefinition>()
                        .filterNot { it is ObjectTypeExtensionDefinition }
                        .map { it.name }
                }.toSet()
        val placements = read(artifacts)
        require(localSchema == null || placements.isEmpty() || localModule != null) {
            "databaseSchema belongs to a Viaduct module, not the assembled application schema"
        }
        val local = Placement(localModule, localSchema)
        val entities = model.entities.associateBy { it.graphqlName }
        val resolved = mutableMapOf<String, Placement>()

        fun placement(
            name: String,
            visiting: Set<String> = emptySet(),
        ): Placement {
            resolved[name]?.let { return it }
            require(name !in visiting) { "Cyclic generated persistence ownership at $name" }
            val result =
                placements[name] ?: if (name in definitions) {
                    if (placements.isEmpty()) local else Placement(null, null)
                } else {
                    val owner =
                        entities[name]
                            ?.attributes
                            ?.filterIsInstance<PersistenceToOneAttribute>()
                            ?.singleOrNull { it.name == "owner" }
                    owner?.let { placement(it.targetTypeName, visiting + name) } ?: Placement(null, null)
                }
            resolved[name] = result
            return result
        }
        return PersistenceModel(
            model.entities.map { entity ->
                val assigned = placement(entity.graphqlName)
                PersistenceEntity(
                    entity.graphqlName,
                    entity.generatedGlobalId,
                    entity.attributes,
                    entity.uniqueKeys,
                    entity.indexes,
                    assigned.schema,
                    assigned.module,
                )
            },
            model.enums,
            model.semanticNotNullCoordinates,
            model.abstractTypes,
            model.retryableTransactions,
        )
    }

    private fun read(artifacts: Collection<File>): Map<String, Placement> {
        val result = sortedMapOf<String, Placement>()
        val modules = mutableMapOf<String, String>()
        PersistencePlacementArtifacts
            .files(artifacts)
            .forEach { file ->
                val document = Json.parseToJsonElement(file.readText()).jsonObject
                require(document.getValue("version").jsonPrimitive.content == "1") {
                    "$file: unsupported placement version"
                }
                val module = document.getValue("module").jsonPrimitive.content
                val schema = document.getValue("databaseSchema").jsonPrimitive.content
                require(module.isNotBlank()) { "$file: blank module identity" }
                require(schema.matches(Regex("[a-z_][a-z0-9_]{0,62}")) && !schema.startsWith("pg_")) {
                    "$file: invalid databaseSchema"
                }
                val previous = modules.putIfAbsent(module, schema)
                require(previous == null || previous == schema) { "Conflicting database placement for module $module" }
                val placement = Placement(module, schema.takeUnless { it == "public" })
                document.getValue("types").jsonArray.forEach { type ->
                    val name = type.jsonPrimitive.content
                    val owner = result.putIfAbsent(name, placement)
                    require(owner == null || owner == placement) {
                        "Ambiguous ownership for $name: $owner and $placement"
                    }
                }
            }
        return result
    }

    private data class Placement(
        val module: String?,
        val schema: String?,
    )
}

package dev.viaduct.persistence.gradle

import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Assembly records intentional external ownership separately from required persistence artifacts. */
internal object PersistenceModuleManifest {
    const val FILE_NAME = "pg-persistence-modules.json"

    fun write(
        directory: File,
        components: Collection<Collection<File>>,
        localModuleArtifacts: Collection<File> = emptyList(),
    ) {
        val external = sortedSetOf<String>()
        val persistent = sortedSetOf<String>()
        // Viaduct may publish copies of the local SDL in its schema partition directory.
        // Match original definitions rather than paths; incomplete placement still fails later.
        val localTypes = localModuleArtifacts.flatMap(::schemaFiles).flatMap(::originalTypes).toSet()
        (components + listOf(localModuleArtifacts)).forEach { artifacts ->
            val placements = PersistencePlacementArtifacts.files(artifacts)
            if (placements.isEmpty()) {
                external.addAll(
                    artifacts.flatMap(::schemaFiles).flatMap(::originalTypes).filterNot { it in localTypes },
                )
            } else {
                placements.forEach { file ->
                    persistent.add(
                        Json
                            .parseToJsonElement(file.readText())
                            .jsonObject
                            .getValue("module")
                            .jsonPrimitive.content,
                    )
                }
            }
        }
        check(directory.isDirectory || directory.mkdirs())
        directory.resolve(FILE_NAME).writeText(
            buildJsonObject {
                put("version", 1)
                put("persistentModules", JsonArray(persistent.map(::JsonPrimitive)))
                put("externalTypes", JsonArray(external.map(::JsonPrimitive)))
            }.toString(),
        )
    }

    fun externalTypes(artifacts: Collection<File>): Set<String> {
        val manifests = PersistencePlacementArtifacts.files(artifacts, FILE_NAME)
        val placements =
            PersistencePlacementArtifacts.files(artifacts).map {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
        val modules = placements.map { it.getValue("module").jsonPrimitive.content }.toSet()
        val placedTypes =
            placements
                .flatMap { document ->
                    document.getValue("types").jsonArray.map { it.jsonPrimitive.content }
                }.toSet()
        val external =
            manifests.map { file ->
                val document = Json.parseToJsonElement(file.readText()).jsonObject
                require(document.getValue("version").jsonPrimitive.content == "1") {
                    "$file: unsupported module manifest version"
                }
                val required =
                    document
                        .getValue("persistentModules")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                require(modules.containsAll(required)) {
                    "Missing persistence placement artifacts for modules: " +
                        (required - modules).sorted().joinToString()
                }
                document
                    .getValue("externalTypes")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            }
        require(external.distinct().size <= 1) { "Conflicting persistence module manifests" }
        val result = external.firstOrNull().orEmpty()
        require((result intersect placedTypes).isEmpty()) {
            "Conflicting external and persistent type ownership"
        }
        return result
    }

    private fun originalTypes(file: File): List<String> =
        Parser()
            .parseDocument(file.readText())
            .definitions
            .filterIsInstance<ObjectTypeDefinition>()
            .filterNot { it is ObjectTypeExtensionDefinition }
            .map { it.name }

    private fun schemaFiles(artifact: File): List<File> =
        if (artifact.isDirectory) {
            artifact.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList()
        } else {
            listOf(artifact).filter { it.extension == "graphqls" }
        }
}

package dev.viaduct.persistence.runtime.reflection

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Deserialization goes through the same snapshotting and validation as normal construction. */
internal object AbstractTypeMappingsSerializer : KSerializer<AbstractTypeMappings> {
    override val descriptor = MappingData.serializer().descriptor

    override fun deserialize(decoder: Decoder): AbstractTypeMappings =
        decoder.decodeSerializableValue(MappingData.serializer()).let {
            AbstractTypeMappings(it.version, it.possibleTypes, it.relationships)
        }

    override fun serialize(
        encoder: Encoder,
        value: AbstractTypeMappings,
    ) {
        encoder.encodeSerializableValue(
            MappingData.serializer(),
            MappingData(value.version, value.possibleTypes, value.relationships),
        )
    }
}

internal object AbstractRelationshipSerializer : KSerializer<AbstractRelationship> {
    override val descriptor = RelationshipData.serializer().descriptor

    override fun deserialize(decoder: Decoder): AbstractRelationship =
        decoder.decodeSerializableValue(RelationshipData.serializer()).let {
            AbstractRelationship(
                it.ownerType,
                it.fieldName,
                it.abstractType,
                it.targets,
                it.nullable,
                it.collection,
                it.connectionType,
                it.edgeType,
            )
        }

    override fun serialize(
        encoder: Encoder,
        value: AbstractRelationship,
    ) {
        encoder.encodeSerializableValue(
            RelationshipData.serializer(),
            RelationshipData(
                value.ownerType,
                value.fieldName,
                value.abstractType,
                value.targets,
                value.nullable,
                value.collection,
                value.connectionType,
                value.edgeType,
            ),
        )
    }
}

@Serializable
@SerialName("dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings")
private data class MappingData(
    val version: Int = 1,
    val possibleTypes: Map<String, Set<String>> = emptyMap(),
    val relationships: List<AbstractRelationship> = emptyList(),
)

@Serializable
@SerialName("dev.viaduct.persistence.runtime.reflection.AbstractRelationship")
private data class RelationshipData(
    val ownerType: String,
    val fieldName: String,
    val abstractType: String,
    val targets: Set<String>,
    val nullable: Boolean,
    val collection: Boolean = false,
    val connectionType: String? = null,
    val edgeType: String? = null,
)

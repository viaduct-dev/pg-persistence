@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.CompositeField

/** Sets a stored union/interface reference, clearing foreign keys for the other possible types. */
fun PgGraphqlObject.withReference(
    field: CompositeField<*, *>,
    target: GlobalID<*>?,
): PgGraphqlObject {
    val relationship = abstractRelationship(field)
    require(!relationship.collection) {
        "${relationship.ownerType}.${relationship.fieldName} is a collection; use pgGraphqlAssociation()"
    }
    return withReference(relationship, target)
}

internal fun PgGraphqlObject.withReference(
    relationship: AbstractRelationship,
    target: GlobalID<*>?,
): PgGraphqlObject {
    require(target != null || relationship.nullable) {
        "${relationship.ownerType}.${relationship.fieldName} cannot be null"
    }
    require(target == null || target.type.name in relationship.targets && !target.type.kcls.java.isInterface) {
        "${relationship.ownerType}.${relationship.fieldName} requires one of ${relationship.targets}, " +
            "got ${target?.type?.name}"
    }
    val values = encoded().toMutableMap()
    relationship.targets.forEach { concrete ->
        values[relationship.targetIdField(concrete)] =
            if (target?.type?.name == concrete) JsonPrimitive(target.internalID) else JsonNull
    }
    return PgGraphqlObject.from(JsonObject(values))
}

/** Selects the generated association table without requiring an application GRT for that table. */
fun CompositeField<*, *>.pgGraphqlAssociation(): PgGraphqlAssociation =
    PgGraphqlAssociation(
        abstractRelationship(this).also {
            require(it.collection) { "${it.fieldName} is not a collection" }
        },
    )

class PgGraphqlAssociation internal constructor(
    private val relationship: AbstractRelationship,
) {
    val entity: PgGraphqlEntity get() = PgGraphqlEntity(relationship.rowType)

    fun insertObject(
        owner: GlobalID<*>,
        target: GlobalID<*>,
        values: PgGraphqlObject = PgGraphqlObject.of(),
    ): PgGraphqlObject {
        require(owner.type.name == relationship.ownerType) { "Association owner must be ${relationship.ownerType}" }
        val input = values.withReference(relationship, target).encoded().toMutableMap()
        input["ownerId"] = JsonPrimitive(owner.internalID)
        return PgGraphqlObject.from(JsonObject(input))
    }

    fun withTarget(
        values: PgGraphqlObject,
        target: GlobalID<*>,
    ): PgGraphqlObject = values.withReference(relationship, target)
}

private fun abstractRelationship(field: CompositeField<*, *>): AbstractRelationship =
    requireNotNull(
        AbstractTypeMappings
            .load(field.containingType.kcls.java.classLoader)
            .relationship(field.containingType.name, field.name),
    ) {
        "No stored abstract relationship ${field.containingType.name}.${field.name}; generate the pg-persistence model"
    }

@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject

/** Resolves payload type and cardinality before a mutation can write any data. */
internal class MutationPayloadPlan<P : CompositeOutput> private constructor(
    private val type: Type<P>,
    private val entityField: CompositeField<*, *>?,
    private val batch: Boolean,
) {
    /** Creates the builder before the write; the returned function only assembles the received result. */
    fun <T : NodeObject> prepare(
        ctx: MutationFieldExecutionContext<*, *, *, P>,
        entity: Type<T>,
    ): (JsonObject) -> P {
        val builder = GeneratedBuilder.fromExecutionContext(GeneratedTypeReflection().builderClass(type), ctx)
        return { mutation -> build(builder, ctx, entity, mutation) }
    }

    /** Builds the payload from returned node IDs, with an empty userErrors list when present. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : NodeObject> build(
        builder: GeneratedBuilder,
        ctx: MutationFieldExecutionContext<*, *, *, P>,
        entity: Type<T>,
        mutation: JsonObject,
    ): P {
        entityField?.let { field ->
            val references =
                mutation.getValue("records").jsonArray.map { record ->
                    ctx.ref(
                        ctx.globalIDFor(
                            entity,
                            record.jsonObject
                                .getValue("uuidId")
                                .jsonPrimitive.content,
                        ),
                    )
                }
            builder.set(field, if (batch) references else references.single())
        }
        GeneratedFieldReflection().anyField(type, "userErrors")?.let { builder.set(it, emptyList<Any>()) }
        return builder.build() as P
    }

    companion object {
        fun <P : CompositeOutput> create(
            type: Type<P>,
            entity: Type<*>,
            batch: Boolean,
            allowNoEntityField: Boolean,
        ): MutationPayloadPlan<P> {
            val fields =
                GeneratedFieldReflection()
                    .allFields(type)
                    .filterIsInstance<CompositeField<*, *>>()
                    .filter { it.type.name == entity.name }
            require(fields.isNotEmpty() || allowNoEntityField) {
                "Mutation payload ${type.name} has no ${entity.name} field"
            }
            require(fields.size <= 1) {
                "Mutation payload ${type.name} has multiple ${entity.name} fields"
            }
            val field = fields.singleOrNull()
            field?.let {
                val setter =
                    GeneratedTypeReflection().builderClass(type).methods.single {
                        it.name == field.name && it.parameterCount == 1
                    }
                require(Collection::class.java.isAssignableFrom(setter.parameterTypes.single()) == batch) {
                    "Mutation field ${type.name}.${field.name} must be ${if (batch) "list-valued" else "singular"}"
                }
            }
            return MutationPayloadPlan(type, field, batch)
        }
    }
}

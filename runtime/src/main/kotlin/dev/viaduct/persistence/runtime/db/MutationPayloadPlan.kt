@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
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
    private val type: Type<out P>,
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
        // Shared validation for singular/batch insert, update, and delete.
        @Suppress("LongParameterList")
        fun <P : CompositeOutput> create(
            declared: Type<P>,
            entity: Type<*>,
            explicit: Type<out P>?,
            fieldName: String?,
            batch: Boolean,
            allowNoEntityField: Boolean,
            mappings: AbstractTypeMappings = AbstractTypeMappings.load(declared.kcls.java.classLoader),
        ): MutationPayloadPlan<P> {
            fun fields(type: Type<*>) =
                GeneratedFieldReflection()
                    .allFields(type)
                    .filterIsInstance<CompositeField<*, *>>()
                    .filter {
                        accepts(mappings, it.type, entity)
                    }

            val type =
                selectPayloadType(declared, explicit, mappings) { candidate ->
                    fields(candidate).any {
                        (fieldName == null || it.name == fieldName) && isBatchField(candidate, it) == batch
                    }
                }
            require(!type.kcls.java.isInterface) { "payloadType must identify a concrete object, not ${type.name}" }
            val field = selectEntityField(type, entity, fields(type), fieldName)
            require(field != null || allowNoEntityField) { "Mutation payload ${type.name} has no ${entity.name} field" }
            field?.let {
                require(isBatchField(type, it) == batch) {
                    "Mutation field ${type.name}.${it.name} must be ${if (batch) "list-valued" else "singular"}"
                }
            }
            return MutationPayloadPlan(type, field, batch)
        }

        private fun <P : CompositeOutput> selectPayloadType(
            declared: Type<P>,
            explicit: Type<out P>?,
            mappings: AbstractTypeMappings,
            hasMatchingField: (Type<*>) -> Boolean,
        ): Type<out P> {
            require(explicit == null || accepts(mappings, declared, explicit)) {
                "Payload ${explicit?.name} is not a member/implementation of ${declared.name}"
            }
            if (explicit != null || !declared.kcls.java.isInterface) return explicit ?: declared
            val candidates =
                mappings.possibleTypes[declared.name]
                    .orEmpty()
                    .map { GeneratedTypeReflection().reflectedType(declared, it) }
                    .filter(hasMatchingField)
            require(candidates.size == 1) {
                "Mutation payload ${declared.name} has ${candidates.size} compatible concrete types: " +
                    "${candidates.joinToString { it.name }}. Pass payloadType explicitly."
            }
            @Suppress("UNCHECKED_CAST")
            return candidates.single() as Type<out P>
        }

        private fun selectEntityField(
            type: Type<*>,
            entity: Type<*>,
            candidates: List<CompositeField<*, *>>,
            fieldName: String?,
        ): CompositeField<*, *>? {
            if (fieldName != null) {
                return requireNotNull(candidates.singleOrNull { it.name == fieldName }) {
                    "entityField '$fieldName' is not a compatible ${entity.name} field on ${type.name}"
                }
            }
            require(candidates.size <= 1) {
                "Mutation payload ${type.name} has multiple compatible fields: " +
                    "${candidates.joinToString { it.name }}. Pass entityField explicitly."
            }
            return candidates.singleOrNull()
        }

        private fun isBatchField(
            type: Type<*>,
            field: CompositeField<*, *>,
        ): Boolean {
            val setter =
                GeneratedTypeReflection().builderClass(type).methods.single {
                    it.name == field.name && it.parameterCount == 1
                }
            return Collection::class.java.isAssignableFrom(setter.parameterTypes.single())
        }

        private fun accepts(
            mappings: AbstractTypeMappings,
            declared: Type<*>,
            concrete: Type<*>,
        ): Boolean {
            val assignable = declared.kcls.java.isAssignableFrom(concrete.kcls.java)
            return mappings.accepts(declared.name, concrete.name) || assignable
        }
    }
}

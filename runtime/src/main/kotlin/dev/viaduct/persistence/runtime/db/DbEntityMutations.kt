@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.JsonObject
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject

/** CRUD operations for one persisted Viaduct node type. */
class DbEntityMutations<T : NodeObject>
    @PublishedApi
    internal constructor(
        private val client: DbClient,
        private val entityType: Type<T>,
    ) {
        /** Inserts one row and returns the current mutation resolver's payload. */
        suspend fun <P : CompositeOutput> insert(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            input: PgGraphqlObject,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = false)
            return buildPayload(client.insertRaw(ctx, input, entityType.name))
        }

        /** Inserts several rows in one pg_graphql mutation and returns the current resolver's payload. */
        suspend fun <P : CompositeOutput> insertBatch(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            inputs: Iterable<PgGraphqlObject>,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = true)
            return buildPayload(client.insertRaw(ctx, inputs, entityType.name))
        }

        /** Updates one row and returns the current mutation resolver's payload. */
        suspend fun <P : CompositeOutput> update(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            mutation: PgGraphqlUpdate,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            require(mutation.atMost == 1) { "Singular update requires atMost = 1" }
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = false)
            return buildPayload(client.updateRaw(ctx, mutation, entityType.name))
        }

        /** Updates several independently identified rows and returns a list-valued resolver payload. */
        suspend fun <P : CompositeOutput> updateBatch(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            mutations: Iterable<PgGraphqlUpdate>,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = true)
            return buildPayload(client.updateRaw(ctx, mutations, entityType.name))
        }

        /** Deletes one row and returns the current mutation resolver's payload. */
        suspend fun <P : CompositeOutput> delete(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            mutation: PgGraphqlDelete,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = false, allowNoEntityField = true)
            return buildPayload(client.deleteRaw(ctx, mutation, entityType.name))
        }

        /** Deletes several independently identified rows and returns the current resolver's payload. */
        suspend fun <P : CompositeOutput> deleteBatch(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            mutations: Iterable<PgGraphqlDelete>,
            payloadType: Type<out P>? = null,
            entityField: String? = null,
        ): P {
            val buildPayload = preparePayload(ctx, payloadType, entityField, batch = true, allowNoEntityField = true)
            return buildPayload(client.deleteRaw(ctx, mutations, entityType.name))
        }

        private fun <P : CompositeOutput> preparePayload(
            ctx: MutationFieldExecutionContext<*, *, *, P>,
            payloadType: Type<out P>?,
            entityField: String?,
            batch: Boolean,
            allowNoEntityField: Boolean = false,
        ): (JsonObject) -> P =
            MutationPayloadPlan
                .create(
                    mutationPayloadType<P>(ctx.javaClass),
                    entityType,
                    payloadType,
                    entityField,
                    batch,
                    allowNoEntityField,
                ).prepare(ctx, entityType)
    }

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <T : NodeObject> reflectedType(nodeClass: Class<T>): Type<T> {
    require(!nodeClass.isInterface) { "Mutation entity must be a concrete Node type, not ${nodeClass.simpleName}" }
    val reflectionClass = Class.forName("${nodeClass.name}\$Reflection", true, nodeClass.classLoader)
    return reflectionClass.getField("INSTANCE").get(null) as Type<T>
}

@Suppress("UNCHECKED_CAST")
private fun <P : CompositeOutput> mutationPayloadType(contextClass: Class<*>): Type<P> {
    val mutationContext =
        contextClass.genericInterfaces
            .filterIsInstance<java.lang.reflect.ParameterizedType>()
            .singleOrNull {
                (it.rawType as? Class<*>) == MutationFieldExecutionContext::class.java
            } ?: error("${contextClass.name} does not declare a typed MutationFieldExecutionContext")
    val payloadClass =
        mutationContext.actualTypeArguments[MUTATION_PAYLOAD_TYPE_ARGUMENT] as? Class<*>
            ?: error("${contextClass.name} does not declare a concrete mutation payload type")
    val reflectionClass = Class.forName("${payloadClass.name}\$Reflection", true, payloadClass.classLoader)
    return reflectionClass.getField("INSTANCE").get(null) as Type<P>
}

private const val MUTATION_PAYLOAD_TYPE_ARGUMENT = 3

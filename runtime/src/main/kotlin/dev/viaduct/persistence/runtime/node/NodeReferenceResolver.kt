@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.node

import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

internal class NodeReferenceResolver {
    fun resolve(
        context: ResolverExecutionContext<out Query>,
        declared: Type<*>,
        value: JsonObject,
    ): NodeObject {
        val concrete =
            GeneratedTypeReflection().concreteType(declared, (value["__typename"] as? JsonPrimitive)?.content)
        val id =
            requireNotNull((value["uuidId"] as? JsonPrimitive)?.content) {
                "Missing uuidId for ${concrete.name} reference"
            }
        return resolve(context, concrete, id)
    }

    @Suppress("UNCHECKED_CAST")
    fun resolve(
        context: ResolverExecutionContext<out Query>,
        type: Type<*>,
        internalId: String,
    ): NodeObject =
        context.ref(
            context.globalIDFor(type as Type<NodeObject>, internalId),
        )
}

@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.runtime.db

import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.KeyMapping
import viaduct.api.mapping.GRTDomain
import viaduct.api.mapping.JsonDomain
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput

/**
 * Converts a pg_graphql JSON response into the generated Viaduct value for a typed selection set.
 *
 * This is the conversion used by [DbClient.fetchResult]. It remains public so a caller with JSON
 * obtained some other way — a cache, a different transport, or a test fixture — can convert it
 * without going through [DbClient.fetchJson].
 */
@Suppress("UNCHECKED_CAST")
fun <T : CompositeOutput> JsonObject.toGRT(
    ctx: ExecutionContext,
    selections: SelectionSet<T>,
): T {
    val jsonString = Json.encodeToString(JsonObject.serializer(), this)
    val concreteSelections = concreteSelections(selections)
    // JSON uses response aliases; resolver-returned GRTs must use schema field names.
    return JsonDomain
        .forSelectionSet(ctx, concreteSelections)
        .mapperTo(GRTDomain.forSelectionSet(ctx, concreteSelections, KeyMapping.FieldNameToSelection))(jsonString) as T
}

/** JsonDomain requires a concrete object type even when the resolver's declared result is abstract. */
@Suppress("UNCHECKED_CAST")
internal fun <T : CompositeOutput> JsonObject.concreteSelections(selections: SelectionSet<T>): SelectionSet<T> {
    if (!selections.type.kcls.java.isInterface) return selections
    val concrete = GeneratedTypeReflection().concreteType(selections.type, get("__typename")?.jsonPrimitive?.content)

    val concreteType = concrete as Type<T>
    return selections.selectionSetFor(concreteType)
}

@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.runtime.db
import dev.viaduct.persistence.runtime.select.forConcreteType
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject

/** Generic node queries can give a concrete node resolver selections rooted at Node. */
internal fun <T> SelectiveNodeExecutionContext<T>.ownedNodeSelections(): SelectionSet<T>
    where T : CompositeOutput, T : NodeObject =
    ownedSelections().forNodeType(id.type)

internal fun <T> SelectiveNodeExecutionContext<T>.requestedNodeSelections(): SelectionSet<T>
    where T : CompositeOutput, T : NodeObject =
    selections().forNodeType(id.type)

private fun <T : CompositeOutput> SelectionSet<T>.forNodeType(nodeType: Type<T>): SelectionSet<T> =
    if (type.kcls == nodeType.kcls && (this !is InternalSelectionSet || engineSelectionSet.type == nodeType.name)) {
        this
    } else {
        forConcreteType(nodeType)
    }

@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.runtime.grt

import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.OffsetCursor

/** Internal construction bridge. All public paging uses Viaduct's generated connection context. */
@viaduct.apiannotations.InternalApi
class GrtConnection {
    private val reflection = GeneratedTypeReflection()
    private val projection = GrtProjection()

    @Suppress("UNCHECKED_CAST")
    fun <R : Connection<*, *>> build(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        selections: SelectionSet<R>,
        count: () -> Int,
        slice: (Int, Int) -> List<StoredObject>,
    ): R {
        context.arguments.validate()
        val shape = requireNotNull(reflection.connection(selections.type, selections))
        require(shape.edge.customFields.isEmpty()) {
            "Supply a plain node connection; custom edge fields need their own resolvers"
        }
        val bounds =
            if (context.arguments.requiresTotalCountForOffsetLimit()) {
                context.arguments.toOffsetLimit(totalCount = count())
            } else {
                context.arguments.toOffsetLimit()
            }
        val rows = slice(bounds.offset, Math.addExact(bounds.limit, 1))
        val nodes = rows.take(bounds.limit).map { projection.reference(context, shape.nodeField.type, it) }
        val edges =
            nodes.mapIndexed { index, node ->
                val edge =
                    GeneratedBuilder
                        .fromExecutionContext(reflection.builderClass(shape.edgeType), context)
                        .set(shape.nodeField.name, node)
                shape.cursorField?.let {
                    edge.set(
                        it.name,
                        OffsetCursor.fromOffset(Math.addExact(bounds.offset, index)).value,
                    )
                }
                edge.build()
            }
        val builder =
            GeneratedBuilder
                .fromExecutionContext(reflection.builderClass(selections.type), context)
                .fromEdges(edges, rows.size > bounds.limit, bounds.offset > 0)
        shape.nodesField?.let { builder.set(it.name, nodes) }
        return builder.build() as R
    }
}

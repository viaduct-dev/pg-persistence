@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import org.hibernate.Hibernate
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Generated builder adapter; paging arguments, bounds, cursors, and PageInfo are Viaduct's APIs. */
class GrtConnectionBinding<R, N>(
    val type: Type<R>,
    private val node: GrtBinding<N>,
    private val build: (ExecutionContext, List<N>, Int, Boolean, Boolean) -> R,
) where R : Connection<*, *>, N : NodeObject {
    internal fun build(
        context: ResolverExecutionContext<out Query>,
        entities: List<*>,
        offset: Int,
        limit: Int,
    ): R {
        val nodes =
            entities.take(limit).map {
                // An identifier projection avoids hydrating every scalar/association just to build
                // a Viaduct reference. Entity queries remain supported for native query composition.
                val id =
                    if (it is java.util.UUID) {
                        it
                    } else {
                        val entity = node.entityClass.cast(Hibernate.unproxy(requireNotNull(it)))
                        entity.checkContext(context)
                        checkNotNull(entity.internalId)
                    }
                context.ref(context.globalIDFor(node.type, id.toString()))
            }
        return build(context, nodes, offset, entities.size > limit, offset > 0)
    }
}

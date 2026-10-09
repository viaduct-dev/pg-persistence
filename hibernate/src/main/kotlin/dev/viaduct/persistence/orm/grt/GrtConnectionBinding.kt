@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import org.hibernate.Hibernate
import org.hibernate.Session
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.Edge
import viaduct.api.types.OffsetCursor
import viaduct.api.types.OffsetLimit
import viaduct.api.types.Query

/** Typed edge projection and construction; cursor/bounds/pageInfo are Viaduct's APIs. */
class GrtConnectionBinding<R, E>(
    val type: Type<R>,
    private val edge: (ResolverExecutionContext<out Query>, Session, Any, OffsetCursor, SelectionSet<R>) -> E,
    private val build: (ExecutionContext, List<E>, Boolean, Boolean) -> R,
) where R : Connection<*, *>, E : Edge<*> {
    internal fun build(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        entities: List<*>,
        bounds: OffsetLimit,
        selections: SelectionSet<R>,
    ): R {
        val edges =
            entities.take(bounds.limit).mapIndexed { index, row ->
                val value = Hibernate.unproxy(requireNotNull(row))
                if (value is GrtEntity<*>) value.checkContext(context)
                edge(context, session, value, OffsetCursor.fromOffset(Math.addExact(bounds.offset, index)), selections)
            }
        return build(context, edges, entities.size > bounds.limit, bounds.offset > 0)
    }
}

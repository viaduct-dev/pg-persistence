@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import jakarta.persistence.Tuple
import jakarta.persistence.criteria.Root
import org.hibernate.Hibernate
import org.hibernate.Session
import org.hibernate.query.SelectionQuery
import org.hibernate.query.criteria.JpaCriteriaQuery
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.Edge
import viaduct.api.types.Object
import viaduct.api.types.OffsetCursor
import viaduct.api.types.OffsetLimit
import viaduct.api.types.Query

/** Typed edge projection and construction; cursor/bounds/pageInfo are Viaduct's APIs. */
class GrtConnectionBinding<R, E>(
    val type: Type<R>,
    private val edge: (ResolverExecutionContext<out Query>, Session, Any, OffsetCursor, SelectionSet<R>) -> E,
    private val build: (ExecutionContext, List<E>, Boolean, Boolean) -> R,
    private val read: (ResolverExecutionContext<out Query>, Session, String, SelectionSet<R>) -> GrtConnectionRead<E> =
        { _, _, _, _ -> error("No generated connection read projection") },
) where R : Connection<*, *>, E : Edge<*> {
    internal fun <T : Object> fetch(
        context: ConnectionFieldExecutionContext<*, *, *, R>,
        session: Session,
        rowBinding: GrtBinding<T>,
        selections: SelectionSet<R>,
        query: (Session, JpaCriteriaQuery<Tuple>, Root<out GrtEntity<T>>) -> SelectionQuery<Tuple>,
    ): R {
        val projection = read(context, session, rowBinding.entityName, selections)
        val selection =
            projection.reader.query(session, rowBinding.entityClass, projection.fields) { criteria, root ->
                query(session, criteria, root).also {
                    require(criteria.offset == null && criteria.fetch == null) { "Connection queries must be unpaged" }
                }
            }
        val bounds = connectionBounds(context, selection)
        val rows = selection.setFirstResult(bounds.offset).setMaxResults(Math.addExact(bounds.limit, 1)).resultList
        return buildRead(context, rows, bounds, projection)
    }

    private fun buildRead(
        context: ExecutionContext,
        rows: List<GrtReadRow>,
        bounds: OffsetLimit,
        read: GrtConnectionRead<E>,
    ): R {
        val edges =
            rows.take(bounds.limit).mapIndexed { index, row ->
                read.edge(row, OffsetCursor.fromOffset(Math.addExact(bounds.offset, index)))
            }
        return build(context, edges, rows.size > bounds.limit, bounds.offset > 0)
    }

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

/** Native tuple coordinates and a generated typed edge builder, confined to one read/session. */
class GrtConnectionRead<out E : Edge<*>>(
    internal val reader: GrtReadProjection<*>,
    fields: Set<String>,
    internal val edge: (GrtReadRow, OffsetCursor) -> E,
) {
    internal val fields: Set<String> = java.util.Set.copyOf(fields)
}

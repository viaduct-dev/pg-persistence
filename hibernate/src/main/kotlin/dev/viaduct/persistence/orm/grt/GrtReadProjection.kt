@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.orm.grt

import jakarta.persistence.Tuple
import org.hibernate.Session
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.select.SelectionSet
import viaduct.api.types.Object
import viaduct.api.types.Query
import java.util.UUID

/**
 * Read-only projections are deliberately separate from managed entities. A partially hydrated
 * entity could later flush omitted columns as null. Hibernate selects native property values here;
 * the generated callback puts those values directly into a typed GRT builder.
 */
class GrtReadProjection<T : Object>(
    private val entityName: String,
    private val identityProperty: String,
    columns: Map<String, List<String>>,
    private val build: (ResolverExecutionContext<out Query>, Session, GrtReadRow, Set<String>, SelectionSet<T>) -> T,
) {
    private val columns = columns.mapValues { java.util.List.copyOf(it.value) }.toMap()

    fun rows(
        session: Session,
        ids: List<UUID>,
        fields: Set<String>,
    ): Map<UUID, GrtReadRow> {
        if (ids.isEmpty()) return emptyMap()
        val properties = (listOf(identityProperty) + fields.flatMap { columns[it].orEmpty() }).distinct()
        // Hibernate resolves association.identifier paths to their FK columns; targets stay unloaded.
        val selected = properties.joinToString { "e.$it" }
        val indexes = properties.withIndex().associate { it.value to it.index }
        return session
            .createSelectionQuery(
                "select $selected from $entityName e where e.$identityProperty in :ids",
                Tuple::class.java,
            ).setParameter("ids", ids.distinct())
            .resultList
            .associate { tuple ->
                // Share column coordinates across the query and read tuple values directly; avoid
                // copying each row into another array, list, and property/value map.
                val row =
                    GrtReadRow(tuple.get(0, UUID::class.java)) { property -> tuple.get(indexes.getValue(property)) }
                row.identity to row
            }
    }

    fun value(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        row: GrtReadRow,
        fields: Set<String>,
        selections: SelectionSet<T>,
    ): T = build(context, session, row, fields, selections)

    /** Ordinary objects have no node resolver: their finite child selections drive their own query. */
    fun fetch(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: UUID,
        binding: GrtBinding<T>,
        selections: SelectionSet<T>,
    ): T = fetchMany(context, session, listOf(id), binding, selections).getValue(id)

    /** A selected ordinary-object list shares one column projection rather than one load per child. */
    fun fetchMany(
        context: ResolverExecutionContext<out Query>,
        session: Session,
        ids: List<UUID>,
        binding: GrtBinding<T>,
        selections: SelectionSet<T>,
    ): Map<UUID, T> {
        val fields =
            binding.fields
                .filter { selections.contains(it) }
                .map { it.name }
                .toSet()
        val rows = rows(session, ids, fields)
        return ids.distinct().associateWith { id ->
            val row = requireNotNull(rows[id]) { "$entityName '$id' was not found" }
            value(context, session, row, fields, selections)
        }
    }
}

/** Only native Hibernate tuple values, including typed arrays; no JSON encoding or entity hydration. */
class GrtReadRow internal constructor(
    val identity: UUID,
    private val read: (String) -> Any?,
) {
    @Suppress("UNCHECKED_CAST") // Generated callbacks pair each Hibernate property with its native type.
    fun <R> value(property: String): R = read(property) as R
}

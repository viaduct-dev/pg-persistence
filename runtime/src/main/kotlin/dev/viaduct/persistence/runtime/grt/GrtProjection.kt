@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.grt

import dev.viaduct.persistence.runtime.connection.PagingAccess
import dev.viaduct.persistence.runtime.db.SemanticNotNullCoordinates
import dev.viaduct.persistence.runtime.db.ownedNodeSelections
import dev.viaduct.persistence.runtime.db.requestedNodeSelections
import dev.viaduct.persistence.runtime.node.GlobalIdReferencePlanner
import dev.viaduct.persistence.runtime.node.NodeReferencePlanner
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import dev.viaduct.persistence.runtime.reflection.GeneratedBuilder
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import dev.viaduct.persistence.runtime.select.forConcreteType
import viaduct.api.context.ExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query

/** Builds detached GRTs directly from managed values, while the backend session is still open. */
@viaduct.apiannotations.InternalApi
class GrtProjection {
    private val reflection = GeneratedTypeReflection()

    fun <T> node(
        context: SelectiveNodeExecutionContext<T>,
        record: StoredObject,
    ): T
        where T : CompositeOutput, T : NodeObject =
        build(context, record, context.ownedNodeSelections(), context.requestedNodeSelections())

    @Suppress("UNCHECKED_CAST")
    fun <T : CompositeOutput> build(
        context: ExecutionContext,
        record: StoredObject,
        selections: SelectionSet<T>,
        references: SelectionSet<T>? = null,
    ): T {
        PagingAccess.validateSelections(selections, reflection)
        val type = reflection.concreteType(selections.type, record.type) as Type<T>
        val concreteSelections = selections.forConcreteType(type)
        val builder = GeneratedBuilder.fromExecutionContext(reflection.builderClass(type), context)
        val referenceNames = referenceFields(concreteSelections, references)
        val ids =
            (
                GlobalIdReferencePlanner.plan(concreteSelections, type) +
                    references?.let { GlobalIdReferencePlanner.plan(it.forConcreteType(type), type) }.orEmpty()
            ).associateBy { it.fieldName }
        val nonNullFields = SemanticNotNullCoordinates.load(type.kcls.java.classLoader)
        reflection.fieldReflection.allFields(type).forEach { untyped ->
            val field = untyped as Field<T>
            if (field.name == "__typename") return@forEach
            if (!concreteSelections.contains(field) && field.name !in referenceNames) return@forEach
            val value =
                when {
                    field.name == "id" -> context.globalIDFor(type as Type<NodeObject>, record.id)
                    field.name in ids ->
                        record.value(field.name)?.let {
                            context.globalIDFor(
                                ids.getValue(field.name).nodeType as Type<NodeObject>,
                                if (it is StoredObject) it.id else it.toString(),
                            )
                        }
                    field is CompositeField<*, *> ->
                        composite(
                            context,
                            record,
                            concreteSelections,
                            field,
                            references != null,
                        )
                    else -> record.value(field.name)
                }
            check(value != null || "${type.name}.${field.name}" !in nonNullFields) {
                "Semantic non-null field ${type.name}.${field.name} was null"
            }
            builder.setNative(field.name, value)
        }
        return builder.build() as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : CompositeOutput> referenceFields(
        selections: SelectionSet<T>,
        references: SelectionSet<T>?,
    ): Set<String> {
        if (references == null || !NodeObject::class.java.isAssignableFrom(selections.type.kcls.java)) return emptySet()
        return NodeReferencePlanner(reflection)
            .plan(
                selections as SelectionSet<NodeObject>,
                references.forConcreteType(selections.type) as SelectionSet<NodeObject>,
            ).map { it.fieldName }
            .toSet()
    }

    private fun composite(
        context: ExecutionContext,
        record: StoredObject,
        selections: SelectionSet<*>,
        field: CompositeField<*, *>,
        references: Boolean,
    ): Any? {
        // Viaduct also represents enums as CompositeFields; they do not have subselections.
        if (field.type.kcls.java.isEnum) return record.value(field.name)
        val abstract =
            AbstractTypeMappings
                .load(field.containingType.kcls.java.classLoader)
                .relationship(field.containingType.name, field.name)
        val value =
            if (abstract != null && !abstract.collection) {
                abstract.targets.mapNotNull { record.value(abstract.targetField(it)) }.singleOrNull()
            } else {
                record.value(field.name)
            }
        val child = reflection.fieldReflection.childSelections(selections, field)
        val shape = reflection.connection(field.type, child, field.containingType)
        return when {
            value == null -> null
            shape != null -> error("Resolve connections through fetchConnection with Viaduct connection arguments")
            value is StoredObject -> relationNode(context, field.type, targetRecord(value, abstract), child, references)
            value is Iterable<*> ->
                value.map {
                    relationNode(context, field.type, targetRecord(it as StoredObject, abstract), child, references)
                }
            else -> error("Stored relationship ${record.type}.${field.name} is not an object")
        }
    }

    private fun targetRecord(
        record: StoredObject,
        abstract: AbstractRelationship?,
    ): StoredObject =
        if (abstract?.collection == true) {
            requireNotNull(
                abstract.targets.mapNotNull { record.value(abstract.targetField(it)) as? StoredObject }.singleOrNull(),
            )
        } else {
            record
        }

    @Suppress("UNCHECKED_CAST")
    private fun relationNode(
        context: ExecutionContext,
        declared: Type<*>,
        record: StoredObject,
        selected: SelectionSet<*>,
        references: Boolean,
    ): Any {
        val concrete = reflection.concreteType(declared, record.type)
        return if (references && NodeObject::class.java.isAssignableFrom(concrete.kcls.java)) {
            reference(context, declared, record)
        } else {
            build(context, record, selected as SelectionSet<CompositeOutput>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun reference(
        context: ExecutionContext,
        declared: Type<*>,
        record: StoredObject,
    ): NodeObject {
        val type = reflection.concreteType(declared, record.type) as Type<NodeObject>
        return (context as ResolverExecutionContext<out Query>).ref(context.globalIDFor(type, record.id))
    }
}

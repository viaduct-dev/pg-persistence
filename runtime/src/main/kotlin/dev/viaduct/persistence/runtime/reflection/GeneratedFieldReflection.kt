@file:OptIn(
    viaduct.apiannotations.ExperimentalApi::class,
    viaduct.apiannotations.InternalApi::class,
)

package dev.viaduct.persistence.runtime.reflection

import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput

/** Reflects generated field singletons and nested selection sets. */
internal class GeneratedFieldReflection {
    fun field(
        type: Type<*>,
        name: String,
    ): CompositeField<*, *>? = allFields(type).singleOrNull { it.name == name } as? CompositeField<*, *>

    fun structuralConnectionNodeType(type: Type<*>): Type<*>? {
        val edgeType = field(type, "edges")?.type ?: return null
        return field(edgeType, "node")?.type
    }

    fun anyField(
        type: Type<*>,
        name: String,
    ): Field<*>? = allFields(type).singleOrNull { it.name == name }

    fun allFields(type: Type<*>): List<Field<*>> {
        if (!CompositeOutput::class.java.isAssignableFrom(type.kcls.java) ||
            (type.kcls.java.isInterface && viaduct.api.types.Union::class.java.isAssignableFrom(type.kcls.java))
        ) {
            return emptyList()
        }

        val fieldsClass = Class.forName("${type.kcls.java.name}\$Fields", true, type.kcls.java.classLoader)
        val fieldsInstance = fieldsClass.getField("INSTANCE").get(null)
        return fieldsClass.methods
            .asSequence()
            .filter {
                it.parameterCount == 0 && Field::class.java.isAssignableFrom(it.returnType)
            }.mapNotNull { it.invoke(fieldsInstance) as? Field<*> }
            .distinctBy(Field<*>::name)
            .toList()
    }

    @Suppress("UNCHECKED_CAST")
    fun childSelections(
        parent: SelectionSet<*>,
        field: CompositeField<*, *>,
    ): SelectionSet<*> =
        (parent as SelectionSet<CompositeOutput>).selectionSetFor(
            field as CompositeField<CompositeOutput, CompositeOutput>,
        )
}

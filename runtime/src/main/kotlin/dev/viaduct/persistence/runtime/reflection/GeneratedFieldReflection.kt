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
    private val fieldsByClass =
        object : ClassValue<List<Field<*>>>() {
            override fun computeValue(type: Class<*>): List<Field<*>> = readFields(type)
        }

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

    fun allFields(type: Type<*>): List<Field<*>> = fieldsByClass.get(type.kcls.java)

    fun isListField(
        owner: Type<*>,
        name: String,
    ): Boolean =
        owner.kcls.java.declaredClasses
            .firstOrNull { it.simpleName == "Builder" }
            ?.methods
            ?.any {
                it.name == name &&
                    it.parameterCount == 1 &&
                    Collection::class.java.isAssignableFrom(it.parameterTypes.single())
            } == true

    private fun readFields(type: Class<*>): List<Field<*>> {
        if (!CompositeOutput::class.java.isAssignableFrom(type) ||
            (type.isInterface && viaduct.api.types.Union::class.java.isAssignableFrom(type))
        ) {
            return emptyList()
        }

        val fieldsClass = Class.forName("${type.name}\$Fields", true, type.classLoader)
        val fieldsInstance = fieldsClass.getField("INSTANCE").get(null)
        val fields =
            fieldsClass.methods
                .asSequence()
                .filter {
                    it.parameterCount == 0 && Field::class.java.isAssignableFrom(it.returnType)
                }.mapNotNull { it.invoke(fieldsInstance) as? Field<*> }
                .distinctBy(Field<*>::name)
                .toList()
        return java.util.List.copyOf(fields)
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

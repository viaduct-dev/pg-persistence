package dev.viaduct.persistence.runtime.node

import dev.viaduct.persistence.runtime.reflection.GeneratedFieldReflection
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import java.lang.reflect.ParameterizedType

/** Typed foreign-key fields contain PostgreSQL UUIDs, not already encoded Viaduct global IDs. */
internal object GlobalIdReferencePlanner {
    fun <T : CompositeOutput> plan(
        selections: SelectionSet<T>,
        owner: Type<T>,
    ): List<NodeReferenceSelection> {
        val builder =
            owner.kcls.java.declaredClasses
                .firstOrNull { it.simpleName == "Builder" } ?: return emptyList()
        val fields = GeneratedFieldReflection()
        return builder.methods.mapNotNull { method ->
            if (method.name == "id" || method.parameterCount != 1) return@mapNotNull null
            val parameter = method.genericParameterTypes.single() as? ParameterizedType ?: return@mapNotNull null
            if (parameter.rawType != GlobalID::class.java) return@mapNotNull null
            val field = fields.anyField(owner, method.name) ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            if (!selections.contains(field as viaduct.api.reflect.Field<T>)) return@mapNotNull null
            val target = parameter.actualTypeArguments.single() as? Class<*> ?: return@mapNotNull null
            val type = GeneratedTypeReflection().reflectedType(owner, target.simpleName)
            NodeReferenceSelection(method.name, type, NodeReferenceKind.GLOBAL_ID, type)
        }
    }
}

package dev.viaduct.persistence.hibernate

/** Pure association bookkeeping remains a native Hibernate row, with no synthetic GraphQL type. */
internal fun storageSource(
    shape: GrtDelegateShape,
    mapping: HbmEntityMapping,
    shapes: Map<String, GrtDelegateShape>,
    grtPackage: String,
): String {
    val properties =
        mapping.attributes.map { attribute ->
            when (attribute) {
                is HbmToOneMapping -> {
                    val target = shapes.getValue(attribute.targetEntityName).className
                    "    open var `${attribute.name}`: $target? = null"
                }
                is HbmToManyMapping -> "    open var `${attribute.name}`: MutableList<${shapes.getValue(
                    attribute.targetEntityName,
                ).className}> = arrayListOf()"
                is HbmBasicMapping -> {
                    val field =
                        shape.entity.attributes.single {
                            it.name == attribute.name
                        } as dev.viaduct.persistence.model.PersistenceBasicAttribute
                    val element =
                        if (field.columnDefinition ==
                            "jsonb"
                        ) {
                            "Any"
                        } else if (field.enumTypeName != null) {
                            "String"
                        } else {
                            field.kotlinType
                        }
                    val type =
                        if (field.collection) {
                            "Array<$element${if (field.elementNullable) "?" else ""}>"
                        } else {
                            element
                        }
                    "    open var `${attribute.name}`: $type? = null"
                }
            }
        }
    return """
package $grtPackage.persistence

import dev.viaduct.persistence.orm.grt.StorageBinding

/** Storage-only row: Hibernate owns its fields, associations and native UUID. */
open class ${shape.className} {
${properties.joinToString("\n")}

    companion object {
        val BINDING = StorageBinding("${shape.name}", ${shape.className}::class.java) { id ->
            ${shape.className}().also { it.${shape.identityField} = id }
        }
    }
}
""".trimStart()
}

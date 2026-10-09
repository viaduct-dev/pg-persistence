package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceBasicAttribute

/** Adapt the public GRT value to Hibernate's existing scalar/array mapping, without a wire protocol. */
internal fun scalarRead(
    field: PersistenceBasicAttribute,
    receiver: String = "grt()",
): String {
    val read = grtGetter(field.name, receiver)
    val converted = scalarConversion(field, "it", toDatabase = true)
    return if (field.collection) {
        val element = if (field.elementNullable) "it?.let { $converted }" else converted
        if (converted == "it") "$read?.toTypedArray()" else "$read?.map { $element }?.toTypedArray()"
    } else if (converted == "it") {
        read
    } else {
        "$read?.let { $converted }"
    }
}

internal fun scalarProperty(
    field: PersistenceBasicAttribute,
    grtPackage: String,
): String {
    val elementType =
        when {
            field.columnDefinition == "jsonb" -> "Any"
            field.enumTypeName != null -> "String"
            else -> field.kotlinType
        }
    val type = if (field.collection) "Array<$elementType${if (field.elementNullable) "?" else ""}>" else elementType
    val write = scalarFromDatabase(field, "value", grtPackage)
    val supplied = if (field.nullable) write else "requireNotNull($write)"
    // Explicit bean methods avoid Kotlin's isX Boolean accessor naming, which drops the "is" in
    // JavaBeans metadata. Hibernate must see the exact schema-derived property name.
    val suffix = field.name.replaceFirstChar(Char::uppercaseChar)
    return """
    open fun get$suffix(): $type? = ${scalarRead(field)}
    open fun set$suffix(value: $type?) { __builder.`${field.name}`($supplied) }
""".trimEnd()
}

/** The same native conversion serves entity setters and read-only tuple projections. */
internal fun scalarFromDatabase(
    field: PersistenceBasicAttribute,
    value: String,
    grtPackage: String,
): String {
    val converted = scalarConversion(field, "it", toDatabase = false, grtPackage)
    val write =
        if (field.collection) {
            val element = if (field.elementNullable) "it?.let { $converted }" else converted
            if (converted == "it") "$value?.toList()" else "$value?.map { $element }"
        } else if (converted == "it") {
            value
        } else {
            "$value?.let { $converted }"
        }
    return write
}

private fun scalarConversion(
    field: PersistenceBasicAttribute,
    expression: String,
    toDatabase: Boolean,
    grtPackage: String = "",
): String =
    when {
        field.enumTypeName != null ->
            if (toDatabase) "$expression.name" else "$grtPackage.${field.enumTypeName}.valueOf($expression)"
        field.kotlinType == "java.time.OffsetDateTime" ->
            if (toDatabase) "$expression.atOffset(java.time.ZoneOffset.UTC)" else "$expression.toInstant()"
        field.kotlinType == "java.util.UUID" ->
            if (toDatabase) "java.util.UUID.fromString($expression)" else "$expression.toString()"
        else -> expression
    }

/** Viaduct's generated public getters follow Kotlin's isX naming for every field type. */
internal fun grtGetter(
    name: String,
    receiver: String = "grt()",
): String {
    val method =
        if (name.startsWith("is") && name.length > 2 && !name[2].isLowerCase()) {
            name
        } else {
            "get${name.replaceFirstChar(Char::uppercaseChar)}"
        }
    return "$receiver.$method()"
}

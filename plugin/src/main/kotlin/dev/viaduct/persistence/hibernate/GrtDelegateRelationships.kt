package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship

/** Native FK properties stay separate from the one GraphQL field declared by an interface/union. */
internal class GrtDelegateRelationships(
    shapes: Map<String, GrtDelegateShape>,
) {
    private val shapes = java.util.Map.copyOf(shapes)

    fun property(field: PersistenceToOneAttribute): String {
        val target = shapes.getValue(field.targetTypeName).className
        return """
    private var __native_${field.name}: $target? = null
    open var `${field.name}`: $target?
        get() = __native_${field.name}
        set(value) { __native_${field.name} = value; __relationshipsChanged = true }
""".trimEnd()
    }

    fun reference(
        target: String,
        expression: String,
        context: String = "executionContext()",
        idOnly: Boolean = false,
    ): String {
        val shape = shapes.getValue(target)
        val id =
            if (shape.node) {
                "$context.globalIDFor(${shape.className}.BINDING.type, " +
                    "requireNotNull($expression.internalId).toString())"
            } else {
                "requireNotNull($expression.internalId).toString()"
            }
        return when {
            idOnly -> id
            shape.node -> "$context.ref($id)"
            else -> "${shape.grtName}.Builder($context).id($id).build()"
        }
    }

    fun resolve(field: PersistenceToOneAttribute): String {
        val shape = shapes.getValue(field.targetTypeName)
        val read = grtGetter(field.name, "value")
        val identity =
            if (field.idOfDirected) {
                "$read?.let { require(it.type == ${shape.className}.BINDING.type); " +
                    "java.util.UUID.fromString(it.internalID) }"
            } else {
                "$read?.let { requireNotNull(${shape.className}.BINDING.identityOf(it)) " +
                    "{ \"${field.name} requires a persisted identity\" } }"
            }
        return "        val resolved_${field.name} = $identity?.let { " +
            "session.getReference(\"${shape.name}\", it) as ${shape.className} }" +
            if (field.nullable) "" else "\n        require(resolved_${field.name} != null)"
    }

    fun resolveAbstract(field: AbstractRelationship): String {
        val cases =
            field.targets.joinToString("\n") { target ->
                val shape = shapes.getValue(target)
                "            is $target -> \"$target\" to " +
                    "requireNotNull(${shape.className}.BINDING.identityOf(supplied))"
            }
        val read = grtGetter(field.fieldName, "value")
        val checks = if (field.nullable) "null" else "error(\"${field.fieldName} cannot be null\")"
        return """
        val resolved_${field.fieldName} = when (val supplied = $read) {
$cases
            null -> $checks
            else -> error("Invalid concrete target for ${field.fieldName}")
        }
${field.targets.joinToString("\n") { target ->
            val name = field.targetField(target)
            val shape = shapes.getValue(target)
            "        val resolved_$name = resolved_${field.fieldName}?.takeIf { it.first == \"$target\" }?.let { session.getReference(\"$target\", it.second) as ${shape.className} }"
        }}
""".trimEnd()
    }

    fun snapshot(
        field: PersistenceToOneAttribute,
        alias: String?,
    ): String {
        val ref = reference(field.targetTypeName, "it", idOnly = field.idOfDirected)
        val value = required("`${field.name}`?.let { $ref }", field.nullable)
        val writes = mutableListOf("        __result.`${field.name}`($value)")
        if (alias != null) {
            val id = reference(field.targetTypeName, "it", idOnly = true)
            val aliasValue = required("`${field.name}`?.let { $id }", field.nullable)
            writes += "        __result.`$alias`($aliasValue)"
        }
        return writes.joinToString("\n")
    }

    fun abstractValue(
        field: AbstractRelationship,
        context: String,
        selected: Boolean = false,
    ): String {
        val values =
            field.targets.joinToString(", ") { target ->
                val name = field.targetField(target)
                val ref =
                    if (selected) {
                        selectedValue(target, field.fieldName, "it")
                    } else {
                        reference(target, "it", context)
                    }
                "`$name`?.let { $ref }"
            }
        val result =
            "listOfNotNull($values).also { " +
                "require(it.size <= 1) { \"Conflicting ${field.fieldName} targets\" } }.singleOrNull()"
        return required(result, field.nullable)
    }

    fun selected(
        field: PersistenceToOneAttribute,
        alias: String? = null,
    ): String {
        val name = alias ?: field.name
        val ref =
            if (field.idOfDirected || alias != null) {
                reference(field.targetTypeName, "it", "context", true)
            } else {
                selectedValue(field.targetTypeName, name, "it")
            }
        val value = required("`${field.name}`?.let { $ref }", field.nullable)
        return "        if (\"$name\" in fields) result.`$name`($value)"
    }

    private fun selectedValue(
        target: String,
        field: String,
        expression: String,
    ): String =
        if (shapes.getValue(target).node) {
            reference(target, expression, "context")
        } else {
            "$expression.project(context, session, requireNotNull(selections)" +
                ".selectionSetFor(GRT.Fields.`$field`).selectionSetFor($target.Reflection))"
        }

    fun collection(
        owner: GrtDelegateShape,
        field: PersistenceToManyAttribute,
        abstract: AbstractRelationship?,
    ): String {
        if (abstract != null) return abstractCollection(owner, field, abstract)
        val target = shapes.getValue(field.targetTypeName)
        val value =
            if (target.node) {
                "context.ref(context.globalIDFor(${target.className}.BINDING.type, it.toString()))"
            } else {
                "(session.find(\"${target.name}\", it) as ${target.className})" +
                    ".project(context, session, requireNotNull(selections).selectionSetFor(GRT.Fields.`${field.name}`))"
            }
        return """
        if ("${field.name}" in fields) {
            val ids = session.createSelectionQuery(
                "select child.${target.identityField} from ${owner.name} parent join parent.${field.name} child where parent.${owner.identityField} = :id order by child.${target.identityField}",
                java.util.UUID::class.java,
            ).setParameter("id", internalId).resultList
            result.`${field.name}`(ids.map { $value })
        }
""".trimEnd()
    }

    private fun abstractCollection(
        owner: GrtDelegateShape,
        field: PersistenceToManyAttribute,
        abstract: AbstractRelationship,
    ): String {
        val columns =
            abstract.targets.joinToString {
                "child.${abstract.targetField(it)}.${shapes.getValue(it).identityField}"
            }
        val cases =
            abstract.targets.mapIndexed { index, target ->
                val shape = shapes.getValue(target)
                val ref =
                    if (shape.node) {
                        "context.ref(context.globalIDFor(${shape.className}.BINDING.type, row[$index].toString()))"
                    } else {
                        "(session.find(\"$target\", row[$index]) as ${shape.className})" +
                            ".project(context, session, requireNotNull(selections)" +
                            ".selectionSetFor(GRT.Fields.`${field.name}`).selectionSetFor($target.Reflection))"
                    }
                "                    row[$index] != null -> $ref"
            }
        return """
        if ("${field.name}" in fields) {
            val rows = session.createSelectionQuery(
                "select $columns from ${owner.name} parent join parent.${field.name} child where parent.${owner.identityField} = :id order by child.internalId",
                jakarta.persistence.Tuple::class.java,
            ).setParameter("id", internalId).resultList
            result.`${field.name}`(rows.map { tuple ->
                val row = tuple.toArray()
                require(row.count { it != null } == 1) { "Invalid ${field.name} storage target" }
                when {
${cases.joinToString("\n")}
                    else -> error("Missing ${field.name} storage target")
                }
            })
        }
""".trimEnd()
    }
}

internal fun required(
    value: String,
    nullable: Boolean,
): String = if (nullable) value else "requireNotNull($value)"

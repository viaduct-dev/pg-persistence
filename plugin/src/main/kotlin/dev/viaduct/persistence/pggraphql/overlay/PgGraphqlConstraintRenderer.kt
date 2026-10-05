package dev.viaduct.persistence.pggraphql.overlay

import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import dev.viaduct.persistence.hibernate.EffectiveHibernateRelationship
import dev.viaduct.persistence.hibernate.GraphqlNameKind
import dev.viaduct.persistence.model.associationTypeName
import dev.viaduct.persistence.model.needsEsSuffix

/** Renders pg_graphql foreign-key naming comments for ordinary relationships. */
internal object PgGraphqlConstraintRenderer {
    fun render(model: EffectiveHibernateModel): String =
        buildString {
            commentsByConstraint(model).forEach { (constraint, comment) ->
                appendLine(graphqlConstraintComment(constraint, comment))
            }
        }

    /** The desired `@graphql({...})` comment text for a single foreign-key column, if any. */
    fun commentValue(
        model: EffectiveHibernateModel,
        schemaName: String,
        tableName: String,
        columnName: String,
    ): String? = commentsByConstraint(model)[ConstraintColumn(schemaName, tableName, columnName)]

    private fun commentsByConstraint(model: EffectiveHibernateModel): Map<ConstraintColumn, String> {
        val namesByConstraint = linkedMapOf<ConstraintColumn, MutableMap<String, String>>()
        val relationships =
            relationshipsIncludingEdgeFields(model).filter {
                it.graphqlNameKind != GraphqlNameKind.NONE
            }
        relationships.forEach { relationship ->
            val names =
                namesByConstraint.getOrPut(
                    ConstraintColumn(
                        relationship.schemaName,
                        relationship.tableName,
                        relationship.columnName,
                    ),
                    ::linkedMapOf,
                )
            names[relationship.nameKey()] = relationship.fieldName
        }
        synthesizeAmbiguousLocalNames(relationships, namesByConstraint)
        return namesByConstraint.mapValues { (_, names) -> graphqlCommentText(names) }
    }

    private fun relationshipsIncludingEdgeFields(model: EffectiveHibernateModel): List<EffectiveHibernateRelationship> =
        model.relationships +
            model.computedRelationships.flatMap { association ->
                association.edgeFields.mapNotNull { field ->
                    if (field.collection != null || field.targetSchemaName == null || field.targetTableName == null) {
                        return@mapNotNull null
                    }
                    EffectiveHibernateRelationship(
                        ownerTypeName = associationTypeName(association.ownerTypeName, association.fieldName),
                        fieldName = field.name,
                        schemaName = association.joinSchemaName,
                        tableName = association.joinTableName,
                        columnName = field.columnName,
                        graphqlNameKind = GraphqlNameKind.FOREIGN,
                        targetSchemaName = field.targetSchemaName,
                        targetTableName = field.targetTableName,
                    )
                }
            }

    /**
     * pg_graphql derives a reverse-collection name from the source table alone, not the FK
     * column, so two or more foreign keys from one table to the same target table collide on
     * that default name unless every affected constraint carries an explicit `local_name`. When
     * no GraphQL field declares that reverse collection (so [EffectiveHibernateRelationship]
     * never produces a real [GraphqlNameKind.LOCAL] entry for it), synthesize one instead of
     * leaving the collision in place.
     */
    private fun synthesizeAmbiguousLocalNames(
        relationships: List<EffectiveHibernateRelationship>,
        namesByConstraint: MutableMap<ConstraintColumn, MutableMap<String, String>>,
    ) {
        relationships
            .filter { it.graphqlNameKind == GraphqlNameKind.FOREIGN && it.targetTableName != null }
            .groupBy {
                TargetGroup(it.schemaName, it.tableName, it.targetSchemaName.orEmpty(), it.targetTableName.orEmpty())
            }.values
            .filter { it.size > 1 }
            .flatten()
            .forEach { relationship ->
                val constraint =
                    ConstraintColumn(relationship.schemaName, relationship.tableName, relationship.columnName)
                namesByConstraint.getValue(constraint).putIfAbsent(
                    "local_name",
                    synthesizeLocalName(relationship.ownerTypeName, relationship.fieldName),
                )
            }
    }

    private fun synthesizeLocalName(
        ownerTypeName: String,
        fieldName: String,
    ): String =
        pluralize(ownerTypeName).replaceFirstChar { it.lowercaseChar() } + "By" +
            fieldName.replaceFirstChar { it.uppercaseChar() }

    private fun pluralize(value: String): String =
        when {
            value.endsWith("y") && value.length > 1 && value[value.length - 2].lowercaseChar() !in "aeiou" ->
                value.dropLast(1) + "ies"
            needsEsSuffix(value, listOf("s", "x", "z", "ch", "sh")) -> value + "es"
            else -> value + "s"
        }

    private fun EffectiveHibernateRelationship.nameKey(): String =
        when (graphqlNameKind) {
            GraphqlNameKind.FOREIGN -> "foreign_name"
            GraphqlNameKind.LOCAL -> "local_name"
            GraphqlNameKind.NONE -> error("Relationships without GraphQL names must be filtered before rendering")
        }

    private data class TargetGroup(
        val schemaName: String,
        val tableName: String,
        val targetSchemaName: String,
        val targetTableName: String,
    )

    private fun graphqlCommentText(names: Map<String, String>): String =
        "@graphql({${names.entries.joinToString(", ") { (name, value) -> "\"$name\": \"$value\"" }}})"

    private fun graphqlConstraintComment(
        constraint: ConstraintColumn,
        commentText: String,
    ): String =
        """
        DO ${'$'}pg_graphql${'$'}
        DECLARE
          constraint_name text;
        BEGIN
          SELECT constraint_def.conname
            INTO constraint_name
            FROM pg_constraint constraint_def
            JOIN pg_attribute column_def
              ON column_def.attrelid = constraint_def.conrelid
             AND column_def.attnum = ANY (constraint_def.conkey)
           WHERE constraint_def.contype = 'f'
             AND constraint_def.conrelid =
                 '${constraint.schemaName}.${constraint.tableName}'::regclass
             AND column_def.attname = '${constraint.columnName}'
           LIMIT 1;
          IF constraint_name IS NULL THEN
            RAISE EXCEPTION 'No foreign key found for ${constraint.schemaName}.${constraint.tableName}.${constraint.columnName}';
          END IF;
          EXECUTE format(
            'COMMENT ON CONSTRAINT %I ON ${quoteIdentifier(constraint.schemaName)}.${quoteIdentifier(constraint.tableName)} IS %L',
            constraint_name,
            '$commentText'
          );
        END
        ${'$'}pg_graphql${'$'};
        """.trimIndent()

    private data class ConstraintColumn(
        val schemaName: String,
        val tableName: String,
        val columnName: String,
    )
}

package dev.viaduct.persistence.pggraphql.overlay

import dev.viaduct.persistence.hibernate.EffectiveHibernateComputedRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import dev.viaduct.persistence.model.associationTypeName

/**
 * Names the real association-table relationships consumed by the pg_graphql adapter.
 *
 * Association rows stay ordinary PostgreSQL rows. There are deliberately no generated views,
 * functions, or projection tables here. The owner-side relationship is named using the persisted
 * Viaduct field plus `Associations`, for example `Group.membersAssociations`.
 */
internal object PgGraphqlAssociationRelationshipRenderer {
    fun render(model: EffectiveHibernateModel): String =
        buildString {
            model.computedRelationships.forEach { relationship ->
                appendLine(renderTableComment(relationship))
                appendLine(
                    renderForeignKeyComment(
                        relationship,
                        relationship.joinOwnerColumnName,
                        ownerNames(relationship),
                    ),
                )
                appendLine(
                    renderForeignKeyComment(
                        relationship,
                        relationship.joinTargetColumnName,
                        targetNames(relationship),
                    ),
                )
            }
        }

    fun tableCommentValue(
        model: EffectiveHibernateModel,
        schema: String,
        table: String,
    ): String? =
        model.computedRelationships
            .lastOrNull { it.joinSchemaName == schema && it.joinTableName == table }
            ?.let { """@graphql({"name": "${associationTypeName(it.ownerTypeName, it.fieldName)}"})""" }

    fun constraintCommentValue(
        model: EffectiveHibernateModel,
        schema: String,
        table: String,
        column: String,
    ): String? =
        model.computedRelationships
            .filter { it.joinSchemaName == schema && it.joinTableName == table }
            .mapNotNull { relationship ->
                when (column) {
                    relationship.joinOwnerColumnName -> ownerNames(relationship)
                    relationship.joinTargetColumnName -> targetNames(relationship)
                    else -> null
                }
            }.lastOrNull()
            ?.let(::commentText)

    private fun ownerNames(relationship: EffectiveHibernateComputedRelationship): Map<String, String> =
        mapOf("foreign_name" to "owner", "local_name" to "${relationship.fieldName}Associations")

    private fun targetNames(relationship: EffectiveHibernateComputedRelationship): Map<String, String> =
        mapOf(
            "foreign_name" to "node",
            "local_name" to
                "${associationTypeName(
                    relationship.ownerTypeName,
                    relationship.fieldName,
                ).replaceFirstChar(Char::lowercaseChar)}Associations",
        )

    private fun commentText(names: Map<String, String>): String =
        "@graphql({${names.entries.joinToString(", ") { (name, value) -> "\"$name\": \"$value\"" }}})"

    private fun renderTableComment(relationship: EffectiveHibernateComputedRelationship): String =
        "COMMENT ON TABLE " +
            "${qualifiedName(relationship.joinSchemaName, relationship.joinTableName)} " +
            "IS E'@graphql({\"name\": \"" +
            "${associationTypeName(relationship.ownerTypeName, relationship.fieldName)}\"})';"

    private fun renderForeignKeyComment(
        relationship: EffectiveHibernateComputedRelationship,
        columnName: String,
        names: Map<String, String>,
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
                 '${relationship.joinSchemaName}.${relationship.joinTableName}'::regclass
             AND column_def.attname = '$columnName'
           LIMIT 1;
          IF constraint_name IS NULL THEN
            RAISE EXCEPTION 'No foreign key found for ${relationship.joinSchemaName}.${relationship.joinTableName}.$columnName';
          END IF;
          EXECUTE format(
            'COMMENT ON CONSTRAINT %I ON ${qualifiedName(relationship.joinSchemaName, relationship.joinTableName)} IS %L',
            constraint_name,
            '${commentText(names)}'
          );
        END
        ${'$'}pg_graphql${'$'};
        """.trimIndent()
}

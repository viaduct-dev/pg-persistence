package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateComputedRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import kotlin.reflect.KClass

private typealias MigrationOperations = MutableList<PostgresqlMigrationOperation>

internal class PostgresqlMigrationPlan(
    operations: List<PostgresqlMigrationOperation>,
) {
    val operations: List<PostgresqlMigrationOperation> = java.util.List.copyOf(operations)
}

internal data class EdgeFieldSpec(
    val schemaName: String,
    val tableName: String,
    val columnName: String,
    val sqlType: String,
    val nullable: Boolean,
)

internal data class GlobalIdSpec(
    val graphqlName: String,
    val schemaName: String,
    val tableName: String,
    val internalIdColumnName: String,
    val globalIdColumnName: String,
)

internal data class ArrayCheckSpec(
    val schemaName: String,
    val tableName: String,
    val columnName: String,
)

internal sealed interface PostgresqlMigrationOperation {
    data class AddAbstractCheck(
        val reference: dev.viaduct.persistence.hibernate.EffectiveAbstractReference,
    ) : PostgresqlMigrationOperation

    data class AddEdgeField(
        val field: EdgeFieldSpec,
    ) : PostgresqlMigrationOperation

    data class AddForeignKey(
        val foreignKey: ForeignKeySpec,
    ) : PostgresqlMigrationOperation

    data class AddGlobalId(
        val globalId: GlobalIdSpec,
    ) : PostgresqlMigrationOperation

    data class AddArrayCheck(
        val check: ArrayCheckSpec,
    ) : PostgresqlMigrationOperation

    companion object {
        val types: Set<KClass<out PostgresqlMigrationOperation>> =
            java.util.Set.of(
                AddEdgeField::class,
                AddForeignKey::class,
                AddGlobalId::class,
                AddArrayCheck::class,
                AddAbstractCheck::class,
            )
    }
}

/** Maps effective Hibernate objects into an ordered, renderer-independent migration plan. */
internal object EffectiveModelToMigrationPlanMapper {
    fun map(model: EffectiveHibernateModel): PostgresqlMigrationPlan =
        PostgresqlMigrationPlan(
            operations =
                buildList {
                    addEdgeFields(model)
                    addRelationshipForeignKeys(model)
                    addComputedRelationshipForeignKeys(model)
                    model.abstractReferences.forEach { add(PostgresqlMigrationOperation.AddAbstractCheck(it)) }
                    model.entities.filter { it.generatedGlobalId }.forEach {
                        add(
                            PostgresqlMigrationOperation.AddGlobalId(
                                GlobalIdSpec(
                                    it.graphqlName,
                                    it.schemaName,
                                    it.tableName,
                                    requireNotNull(it.internalIdColumnName),
                                    requireNotNull(it.globalIdColumnName),
                                ),
                            ),
                        )
                    }
                    model.arrays.filterNot { it.elementNullable }.forEach {
                        add(
                            PostgresqlMigrationOperation.AddArrayCheck(
                                ArrayCheckSpec(it.schemaName, it.tableName, it.columnName),
                            ),
                        )
                    }
                },
        )

    private fun MigrationOperations.addEdgeFields(model: EffectiveHibernateModel) {
        model.computedRelationships.forEach { relationship ->
            relationship.edgeFields.filter { it.collection == null }.forEach { field ->
                add(
                    PostgresqlMigrationOperation.AddEdgeField(
                        EdgeFieldSpec(
                            relationship.joinSchemaName,
                            relationship.joinTableName,
                            field.columnName,
                            field.sqlType,
                            field.nullable,
                        ),
                    ),
                )
            }
        }
    }

    private fun MigrationOperations.addRelationshipForeignKeys(model: EffectiveHibernateModel) {
        model.relationships
            .distinctBy { listOf(it.schemaName, it.tableName, it.columnName) }
            .forEach { relationship ->
                add(
                    PostgresqlMigrationOperation.AddForeignKey(
                        ForeignKeySpec(
                            relationship.schemaName,
                            relationship.tableName,
                            relationship.columnName,
                            requireNotNull(relationship.targetSchemaName),
                            requireNotNull(relationship.targetTableName),
                            requireNotNull(relationship.targetIdColumnName),
                        ),
                    ),
                )
            }
    }

    private fun MigrationOperations.addComputedRelationshipForeignKeys(model: EffectiveHibernateModel) {
        model.computedRelationships.forEach { relationship ->
            addForeignKey(
                relationship,
                relationship.joinOwnerColumnName,
                relationship.owner,
                relationship.ownerIdColumnName,
            )
            addForeignKey(
                relationship,
                relationship.joinTargetColumnName,
                relationship.target,
                relationship.targetIdColumnName,
            )
            relationship.edgeFields.filter { it.targetTableName != null }.forEach { field ->
                add(
                    PostgresqlMigrationOperation.AddForeignKey(
                        ForeignKeySpec(
                            relationship.joinSchemaName,
                            relationship.joinTableName,
                            field.columnName,
                            requireNotNull(field.targetSchemaName),
                            requireNotNull(field.targetTableName),
                            requireNotNull(field.targetIdColumnName),
                        ),
                    ),
                )
            }
        }
    }

    private fun MigrationOperations.addForeignKey(
        relationship: EffectiveHibernateComputedRelationship,
        columnName: String,
        target: dev.viaduct.persistence.hibernate.EffectiveHibernateTable,
        targetColumnName: String,
    ) {
        add(
            PostgresqlMigrationOperation.AddForeignKey(
                ForeignKeySpec(
                    relationship.joinSchemaName,
                    relationship.joinTableName,
                    columnName,
                    target.schemaName,
                    target.tableName,
                    targetColumnName,
                ),
            ),
        )
    }
}

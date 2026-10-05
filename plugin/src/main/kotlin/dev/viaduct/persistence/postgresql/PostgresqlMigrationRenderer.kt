package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateModel

/** Renders an ordered migration plan by dispatching each operation to its registered renderer. */
internal object PostgresqlMigrationRenderer {
    private val rendererRegistry =
        MigrationRendererRegistry.create(
            listOf(
                EdgeFieldMigrationRenderer,
                ForeignKeyMigrationRenderer,
                GlobalIdMigrationRenderer,
                ArrayConstraintMigrationRenderer,
                AbstractReferenceMigrationRenderer,
            ),
        )

    fun render(model: EffectiveHibernateModel): String = render(EffectiveModelToMigrationPlanMapper.map(model))

    internal fun render(plan: PostgresqlMigrationPlan): String =
        buildString {
            plan.operations.forEach { operation ->
                appendLine(rendererRegistry.render(operation))
            }
        }
}

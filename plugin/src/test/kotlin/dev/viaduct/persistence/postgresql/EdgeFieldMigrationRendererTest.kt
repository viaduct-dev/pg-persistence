package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateComputedRelationship
import dev.viaduct.persistence.hibernate.EffectiveHibernateEdgeCollection
import dev.viaduct.persistence.hibernate.EffectiveHibernateEdgeField
import dev.viaduct.persistence.hibernate.EffectiveHibernateJoinTable
import dev.viaduct.persistence.hibernate.EffectiveHibernateModel
import dev.viaduct.persistence.hibernate.EffectiveHibernateTable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class EdgeFieldMigrationRendererTest {
    @Test
    fun `renders scalar payload nullability`() {
        val required =
            renderEdgeField(
                EdgeFieldSpec("viaduct_internal", "group_members_associations", "role", "varchar(255)", false),
            )
        val optional =
            renderEdgeField(
                EdgeFieldSpec("viaduct_internal", "group_members_associations", "role", "varchar(255)", true),
            )

        assertContains(required, "ADD COLUMN \"role\" varchar(255) NOT NULL")
        assertContains(optional, "ADD COLUMN \"role\" varchar(255);")
        assertFalse(optional.contains("NOT NULL"))
    }

    @Test
    fun `renders object payload foreign key idempotently`() {
        val sql =
            PostgresqlMigrationRenderer.render(
                PostgresqlMigrationPlan(
                    listOf(
                        PostgresqlMigrationOperation.AddEdgeField(
                            EdgeFieldSpec(
                                "viaduct_internal",
                                "group_members_associations",
                                "invited_by_id",
                                "uuid",
                                true,
                            ),
                        ),
                        PostgresqlMigrationOperation.AddForeignKey(
                            ForeignKeySpec(
                                "viaduct_internal",
                                "group_members_associations",
                                "invited_by_id",
                                "application",
                                "persons",
                                "_uuid_id",
                            ),
                        ),
                    ),
                ),
            )

        assertContains(sql, "constraint_def.contype = 'f'")
        assertContains(sql, "attname = 'invited_by_id'")
        assertContains(sql, "'\"viaduct_internal\".\"group_members_associations\"'::regclass")
        assertContains(sql, "ADD CONSTRAINT \"group_members_associations_invited_by_id_fkey\"")
        assertContains(sql, "FOREIGN KEY (\"invited_by_id\")")
        assertContains(sql, "REFERENCES \"application\".\"persons\" (\"_uuid_id\")")
    }

    @Test
    fun `does not render collection mappings as columns on the parent association`() {
        val model =
            EffectiveHibernateModel(
                entities = emptyList(),
                relationships = emptyList(),
                computedRelationships =
                    listOf(
                        relationship(
                            listOf(
                                edgeField(name = "reviewers", columnName = "association_id").copy(
                                    collection =
                                        EffectiveHibernateEdgeCollection(
                                            target = EffectiveHibernateTable("application", "persons", "_uuid_id"),
                                            ownerColumnName = "association_id",
                                        ),
                                ),
                            ),
                        ),
                    ),
                arrays = emptyList(),
            )
        assertFalse(
            EffectiveModelToMigrationPlanMapper.map(model).operations.any {
                it is PostgresqlMigrationOperation.AddEdgeField
            },
        )
    }

    private fun relationship(edgeFields: List<EffectiveHibernateEdgeField> = emptyList()) =
        EffectiveHibernateComputedRelationship(
            ownerTypeName = "Group",
            fieldName = "members",
            owner = EffectiveHibernateTable("application", "groups", "_uuid_id"),
            target = EffectiveHibernateTable("application", "persons", "_uuid_id"),
            join =
                EffectiveHibernateJoinTable(
                    "viaduct_internal",
                    "group_members_associations",
                    "group_id",
                    "person_id",
                ),
            edgeFields = edgeFields,
        )

    private fun edgeField(
        name: String = "role",
        columnName: String = "role",
        nullable: Boolean = true,
    ) = EffectiveHibernateEdgeField(
        name = name,
        columnName = columnName,
        sqlType = "varchar(255)",
        nullable = nullable,
    )

    private fun renderEdgeField(field: EdgeFieldSpec): String =
        EdgeFieldMigrationRenderer.render(PostgresqlMigrationOperation.AddEdgeField(field))
}

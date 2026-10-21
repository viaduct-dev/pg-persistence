package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.gradle.PersistenceSchemaModelLoader
import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.model.PersistenceEntity
import dev.viaduct.persistence.model.PersistenceModel
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class PlacementIdentityValidationTest {
    @Test
    fun `customized physical table collision within one schema is rejected`() {
        withGeneratedModel(
            "type Reviewer implements Node { id: ID!, name: String } " +
                "type Team implements Node { id: ID!, label: String }",
            emptyMap(),
            namingStrategy = TenantCollisionNamingStrategy::class.java.name,
        ) { model, _, _, handle ->
            assertContains(
                assertFailsWith<IllegalArgumentException> {
                    EffectiveHibernateModelBuilder.build(handle.metadata, model)
                }.message.orEmpty(),
                "Conflicting physical table public.shared_records: Reviewer, Team",
            )
        }
    }

    @Test
    fun `public GraphQL collection collision is rejected even with distinct physical schemas`() {
        withGeneratedModel(
            "type User implements Node { id: ID! } type user implements Node { id: ID! }",
            emptyMap(),
            modelLoader = { schema, _ ->
                val model = PersistenceSchemaModelLoader.build(schema, null)
                PersistenceModel(
                    model.entities.map { entity ->
                        PersistenceEntity(
                            entity.graphqlName,
                            entity.generatedGlobalId,
                            entity.attributes,
                            entity.uniqueKeys,
                            entity.indexes,
                            entity.graphqlName.lowercase() + if (entity.graphqlName == "User") "_one" else "_two",
                            "fixture.${entity.graphqlName}",
                        )
                    },
                    model.enums,
                    model.semanticNotNullCoordinates,
                    model.abstractTypes,
                    model.retryableTransactions,
                )
            },
        ) { model, _, _, handle ->
            assertContains(
                assertFailsWith<IllegalArgumentException> {
                    EffectiveHibernateModelBuilder.build(handle.metadata, model)
                }.message.orEmpty(),
                "Conflicting GraphQL collection userCollection: User, user",
            )
        }
    }
}

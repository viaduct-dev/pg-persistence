package dev.viaduct.persistence.model

import dev.viaduct.persistence.hibernate.HibernateSchemaModelWriter
import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceRelationshipTest {
    @TempDir private var directory: File? = null

    @ParameterizedTest
    @ValueSource(strings = ["Subject", "Actor"])
    fun `persisted abstract idOf is rejected instead of becoming an unconstrained UUID`(target: String) {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                model(
                    "subjectId: ID @idOf(type: \"$target\")",
                    extraTypes = "directive @idOf(type: String!) on FIELD_DEFINITION",
                )
            }
        assertTrue(failure.message.orEmpty().contains("Activity.subjectId"))
        assertTrue(failure.message.orEmpty().contains("withReference"))
    }

    @Test fun `abstract idOf on custom edges is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            model(
                "subjects: SubjectConnection",
                extraTypes =
                    connectionTypes.replace("label: String", "targetId: ID @idOf(type: \"Actor\")") +
                        "\ndirective @idOf(type: String!) on FIELD_DEFINITION",
            )
        }
    }

    @Test fun `abstract idOf on resolver-only fields remains outside persistence`() {
        val result =
            model(
                "subjectId: ID @idOf(type: \"Subject\") @resolver",
                extraTypes =
                    """
                    directive @idOf(type: String!) on FIELD_DEFINITION
                    directive @resolver on FIELD_DEFINITION
                    """.trimIndent(),
            )
        assertEquals(emptyList(), result.abstractTypes.relationships)
    }

    @Test fun `concrete idOf still creates a foreign key`() {
        val result =
            model(
                "personId: ID @idOf(type: \"Person\")",
                extraTypes = "directive @idOf(type: String!) on FIELD_DEFINITION",
            )
        assertEquals(
            listOf("Person"),
            result.entities
                .single { it.graphqlName == "Activity" }
                .attributes
                .filterIsInstance<PersistenceToOneAttribute>()
                .map { it.targetTypeName },
        )
    }

    @Test fun `union reference generates concrete nullable foreign keys`() {
        val model = model("subject: Subject!")
        val fields =
            model.entities
                .single { it.graphqlName == "Activity" }
                .attributes
                .filterIsInstance<PersistenceToOneAttribute>()
        assertEquals(listOf("subjectGroup", "subjectPerson"), fields.map { it.name })
        assertTrue(fields.all { it.nullable })
        assertEquals(
            false,
            model.abstractTypes.relationships
                .single()
                .nullable,
        )
    }

    @Test fun `interface reference includes inherited implementations`() {
        assertEquals(
            setOf("Group", "Person"),
            model("actor: Actor")
                .abstractTypes.relationships
                .single()
                .targets,
        )
    }

    @Test fun `mixed list has a single association table`() {
        val model = model("subjects: [Subject!]")
        val row = model.entities.single { it.graphqlName == "ActivitySubjectsReference" }
        assertEquals(
            listOf("owner", "nodeGroup", "nodePerson"),
            row.attributes.filterIsInstance<PersistenceToOneAttribute>().map { it.name },
        )
    }

    @Test fun `connection detection preserves abstract node and edge types`() {
        val model = model("subjects: SubjectConnection", extraTypes = connectionTypes)
        assertEquals(
            AbstractRelationship(
                ownerType = "Activity",
                fieldName = "subjects",
                abstractType = "Subject",
                targets = setOf("Group", "Person"),
                nullable = false,
                collection = true,
                connectionType = "SubjectConnection",
                edgeType = "SubjectEdge",
            ),
            model.abstractTypes.relationships.single(),
        )
    }

    @Test fun `connection association retains concrete keys and custom edge fields`() {
        val model = model("subjects: SubjectConnection", extraTypes = connectionTypes)
        val row = model.entities.single { it.graphqlName == "ActivitySubjectsReference" }
        assertEquals(
            listOf(
                PersistenceBasicAttribute("internalId", false, "java.util.UUID"),
                PersistenceBasicAttribute("id", false, "String"),
                PersistenceToOneAttribute("owner", false, "Activity"),
                PersistenceToOneAttribute("nodeGroup", true, "Group"),
                PersistenceToOneAttribute("nodePerson", true, "Person"),
                PersistenceBasicAttribute("label", true, "String"),
            ),
            row.attributes,
        )
    }

    @Test fun `resolver fields do not generate stored abstract relationships`() {
        val model =
            model(
                "subject: Subject @resolver",
                extraTypes = "directive @resolver on FIELD_DEFINITION",
            )
        assertEquals(emptyList(), model.abstractTypes.relationships)
    }

    @Test fun `association type name collision is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            model("subjects: [Subject!]", extraTypes = "type ActivitySubjectsReference { label: String }")
        }
    }

    @Test fun `association edge field collision is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            model("subjects: SubjectConnection", extraTypes = connectionTypes.replace("label: String", "owner: String"))
        }
    }

    @Test fun `denied possible type is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> { model("subject: Subject", setOf("Activity", "Person")) }
        assertTrue(failure.message!!.contains("Activity.subject"))
    }

    @Test fun `generated field name collision is rejected`() {
        assertFailsWith<IllegalArgumentException> { model("subject: Subject subjectPersonId: ID") }
    }

    @Test fun `generated resource records abstract membership`() {
        val model = model("subject: Subject")
        val directory = requireNotNull(directory)
        HibernateSchemaModelWriter().write(model, directory.resolve("generated"))
        val text = directory.resolve("generated/resources/${AbstractTypeMappings.RESOURCE}").readText()
        assertEquals(model.abstractTypes.encode(), text)
    }

    @Test fun `one possible type remains an abstract reference`() {
        val model = model("person: Person subject: Solo", extraTypes = "union Solo = Person")
        assertEquals(
            listOf(
                PersistenceToOneAttribute("person", true, "Person"),
                PersistenceToOneAttribute("subjectPerson", true, "Person"),
            ),
            model.entities
                .single { it.graphqlName == "Activity" }
                .attributes
                .filterIsInstance<PersistenceToOneAttribute>(),
        )
        assertEquals(
            "Solo",
            model.abstractTypes.relationships
                .single()
                .abstractType,
        )
    }

    @Test fun `one possible type still uses an abstract association row`() {
        val model = model("subjects: [Solo!]", extraTypes = "union Solo = Person")
        assertEquals(
            listOf("owner", "nodePerson"),
            model.entities
                .single { it.graphqlName == "ActivitySubjectsReference" }
                .attributes
                .filterIsInstance<PersistenceToOneAttribute>()
                .map { it.name },
        )
    }

    @Test fun `abstract connection does not change concrete inverse collection storage`() {
        val model =
            model(
                "groups: [Group!] subjects: SubjectConnection",
                extraTypes = connectionTypes + "\nextend type Group { activities: [Activity!] }",
            )
        assertEquals(
            PersistenceToManyAttribute(
                "groups",
                true,
                "Group",
                null,
                PersistenceToManyStorage.JOIN_TABLE_OWNER,
                "ActivityGroupsAssociation",
            ),
            model.entities
                .single { it.graphqlName == "Activity" }
                .attributes
                .single { it.name == "groups" },
        )
    }

    @Test fun `required policy uses the same relationship description as generated metadata`() {
        val model =
            model(
                "subject: Subject",
                policy = PersistenceModelPolicy(semanticNotNullFieldCoordinates = setOf("Activity.subject")),
            )
        assertEquals(
            false,
            model.abstractTypes.relationships
                .single()
                .nullable,
        )
        assertEquals(setOf("Activity.subject"), model.semanticNotNullCoordinates)
    }

    @Test fun `required policy rejects abstract collections`() {
        assertFailsWith<IllegalArgumentException> {
            model(
                "subjects: [Subject!]",
                policy = PersistenceModelPolicy(semanticNotNullFieldCoordinates = setOf("Activity.subjects")),
            )
        }
    }

    @Test fun `denied target uses the common relationship validator`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                model("subject: Subject", policy = PersistenceModelPolicy(deniedTypeNames = setOf("Group")))
            }
        assertTrue(failure.message.orEmpty().contains("targets denied type 'Group'"))
    }

    private fun model(
        fields: String,
        included: Set<String> = setOf("Activity", "Person", "Group"),
        extraTypes: String = "",
        policy: PersistenceModelPolicy = PersistenceModelPolicy(),
    ): PersistenceModel {
        val schema =
            ViaductSchemaFactory.fromTypeDefinitionRegistry(
                """
                interface Node { id: ID! }
                interface Actor { name: String }
                interface NamedActor implements Actor { name: String }
                type Person implements Node & NamedActor & Actor { id: ID! name: String }
                type Group implements Node & Actor { id: ID! name: String }
                union Subject = Person | Group
                type Activity implements Node { id: ID! $fields }
                $extraTypes
                """.trimIndent(),
            )
        return PersistenceModelBuilder().build(schema, included, policy)
    }

    private val connectionTypes =
        """
        type SubjectConnection { edges: [SubjectEdge!]! nodes: [Subject!]! }
        type SubjectEdge { node: Subject! cursor: String! label: String }
        """.trimIndent()
}

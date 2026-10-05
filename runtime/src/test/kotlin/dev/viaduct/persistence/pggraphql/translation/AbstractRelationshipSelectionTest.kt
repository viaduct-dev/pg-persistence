package dev.viaduct.persistence.pggraphql.translation

import dev.viaduct.persistence.runtime.reflection.AbstractRelationship
import dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
import graphql.language.AstPrinter
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.Selection
import graphql.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AbstractRelationshipSelectionTest {
    private val relationship =
        AbstractRelationship(
            "Activity",
            "subjects",
            "Subject",
            setOf("Person"),
            false,
            collection = true,
        )

    @Test
    fun `lists and connection nodes share association row selections`() {
        val list = translate(field("selected: subjects { name }"), relationship) as Field
        val connection =
            translate(
                field("subjects { selected: nodes { name } }"),
                relationship.copy(connectionType = "SubjectConnection", edgeType = "SubjectEdge"),
            ) as Field
        val listEdges = requireNotNull(list.selectionSet).selections.single() as Field
        val connectionEdges = requireNotNull(connection.selectionSet).selections.single() as Field
        assertEquals(
            AstPrinter.printAstCompact(requireNotNull(listEdges.selectionSet)),
            AstPrinter.printAstCompact(requireNotNull(connectionEdges.selectionSet)),
        )
    }

    @Test
    fun `edge fragment directives apply to both metadata and row fields`() {
        val translated =
            translate(
                field("subjects { chosen: edges { ... @include(if: true) { position: cursor label } } }"),
                relationship.copy(connectionType = "SubjectConnection", edgeType = "SubjectEdge"),
            )
        assertEquals(
            AstPrinter.printAstCompact(
                field(
                    """
                    subjects {
                        ${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}chosen: edges {
                            ... @include(if: true) { position: cursor }
                            ${VIADUCT_ASSOCIATION_ROW_ALIAS}: node { ... @include(if: true) { label } }
                        }
                    }
                    """,
                ),
            ),
            AstPrinter.printAstCompact(translated),
        )
    }

    @Test
    fun `metadata only edge selection does not add an empty row`() {
        val translated =
            translate(
                field("subjects { kind: __typename edges { cursor kind: __typename } }"),
                relationship.copy(connectionType = "SubjectConnection", edgeType = "SubjectEdge"),
            )
        assertEquals(
            AstPrinter.printAstCompact(
                field(
                    """
                    subjects {
                        ${typeAlias("kind", "SubjectConnection")}: __typename
                        ${VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX}edges: edges {
                            cursor
                            ${typeAlias("kind", "SubjectEdge")}: __typename
                        }
                    }
                    """,
                ),
            ),
            AstPrinter.printAstCompact(translated),
        )
    }

    @Test
    fun `single references preserve field directives on the target group`() {
        val translated =
            translate(
                field("selected: subject @include(if: true) { name }"),
                relationship.copy(fieldName = "subject", collection = false),
            )
        assertEquals(
            AstPrinter.printAstCompact(
                selection(
                    """
                    ... @include(if: true) {
                        ${abstractAlias("selected", "Person")}: subjectPerson { name __typename }
                    }
                    """,
                ),
            ),
            AstPrinter.printAstCompact(translated),
        )
    }

    private fun translate(
        field: Field,
        relationship: AbstractRelationship,
    ): Selection<*> =
        AbstractRelationshipSelection(
            relationship,
            ConcreteSelectionProjector(AbstractTypeMappings()) { selection, _ -> selection },
        ).translate(field)

    private fun field(source: String): Field = selection(source) as Field

    private fun selection(source: String): Selection<*> {
        val fragment =
            Parser()
                .parseDocument("fragment Main on Activity { $source }")
                .definitions
                .single() as FragmentDefinition
        return fragment.selectionSet.selections.single()
    }
}

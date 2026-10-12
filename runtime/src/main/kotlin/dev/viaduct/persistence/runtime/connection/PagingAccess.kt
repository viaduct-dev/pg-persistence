@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.connection
import dev.viaduct.persistence.pggraphql.translation.SelectionFragmentExpander
import dev.viaduct.persistence.runtime.db.DbRoot
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import dev.viaduct.persistence.runtime.select.exportFragment
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import viaduct.api.reflect.Type
import viaduct.api.types.Connection

/** Paging must enter through the generated modern context, not raw database arguments. */
internal object PagingAccess {
    private val pagingArguments = java.util.Set.of("first", "after", "last", "before", "offset")

    private fun isPagingArgument(name: String): Boolean = name in pagingArguments

    private const val MESSAGE = "Use fetchConnection with Viaduct's ConnectionFieldExecutionContext for paging"

    fun validateRoot(root: DbRoot) {
        if (root.arguments.isBlank()) return
        val operation =
            Parser()
                .parseDocument("{ collection${root.arguments} { __typename } }")
                .getFirstDefinitionOfType(OperationDefinition::class.java)
                .orElseThrow()
        val field = operation.selectionSet.selections.single() as Field
        require(field.arguments.none { it.name in pagingArguments }) { MESSAGE }
    }

    fun validateSelections(
        selections: viaduct.api.select.SelectionSet<*>,
        reflection: GeneratedTypeReflection,
    ) {
        val document = Parser().parseDocument(selections.exportFragment().document)
        val expanded = SelectionFragmentExpander(document).expand()
        require(!Connection::class.java.isAssignableFrom(selections.type.kcls.java)) { MESSAGE }
        SelectionGuard(reflection, selections.type).visit(expanded.selectionSet, selections.type)
    }

    /** Arbitrary operations must not provide a native paging route around the typed client. */
    fun validateOperation(document: String) {
        val parsed = Parser().parseDocument(document)
        val expander = SelectionFragmentExpander(parsed)
        parsed.definitions.filterIsInstance<OperationDefinition>().forEach { operation ->
            RawSelectionGuard.visit(expander.expandSelectionSet(operation.selectionSet))
        }
    }

    private object RawSelectionGuard {
        fun visit(
            set: SelectionSet,
            edge: Boolean = false,
        ) {
            val fields = directFields(set).toList()
            val connection = fields.any { it.name == "edges" || it.name == "pageInfo" }
            fields.forEach { field ->
                require(!(connection && field.name == "pageInfo") && !(edge && field.name == "cursor")) { MESSAGE }
                val children = field.selectionSet ?: return@forEach
                val isCollection =
                    field.name.endsWith("Collection") ||
                        directFields(children).any { it.name == "edges" || it.name == "pageInfo" }
                require(!isCollection || field.arguments.none { isPagingArgument(it.name) }) { MESSAGE }
                visit(children, connection && field.name == "edges")
            }
        }

        private fun directFields(set: SelectionSet): Sequence<Field> =
            sequence {
                set.selections.forEach { selection ->
                    when (selection) {
                        is Field -> yield(selection)
                        is InlineFragment -> yieldAll(directFields(selection.selectionSet))
                    }
                }
            }
    }

    private class SelectionGuard(
        private val reflection: GeneratedTypeReflection,
        root: Type<*>,
    ) {
        private val schema = reflection.translationSchema(root)

        fun visit(
            selection: SelectionSet,
            parent: Type<*>,
            edge: Boolean = false,
        ) {
            selection.selections.forEach { child ->
                when (child) {
                    is InlineFragment -> {
                        val name = child.typeCondition?.name ?: parent.name
                        val type = if (name == parent.name) parent else reflection.reflectedType(parent, name)
                        visit(child.selectionSet, type, edge)
                    }
                    is Field -> visitField(child, parent, edge)
                }
            }
        }

        private fun visitField(
            child: Field,
            parent: Type<*>,
            edge: Boolean,
        ) {
            val connection = schema.connectionNodeType(parent.name) != null
            require(!(connection && child.name == "pageInfo") && !(edge && child.name == "cursor")) { MESSAGE }
            if (schema.fieldType(parent.name, child.name) == null) return
            val target = reflection.fieldReflection.field(parent, child.name)?.type ?: return
            if (schema.connectionNodeType(target.name) != null) {
                require(
                    !Connection::class.java.isAssignableFrom(target.kcls.java) &&
                        child.arguments.none { isPagingArgument(it.name) },
                ) { MESSAGE }
            }
            child.selectionSet?.let { visit(it, target, connection && child.name == "edges") }
        }
    }
}

@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.runtime.node
import dev.viaduct.persistence.runtime.connection.ConnectionPaginationArguments
import dev.viaduct.persistence.runtime.connection.ConnectionShape
import dev.viaduct.persistence.runtime.reflection.GeneratedTypeReflection
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject

/** Identifies requested fields that must be hydrated from their pg_graphql references. */
internal class NodeReferencePlanner(
    private val typeReflection: GeneratedTypeReflection,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> plan(
        requestedSelections: SelectionSet<T>,
        ownedSelections: SelectionSet<T>,
    ): List<NodeReferenceSelection> where T : CompositeOutput, T : NodeObject {
        val paginationArguments =
            ConnectionPaginationArguments.fromFragment(
                requestedSelections.toFragment(),
            )
        return typeReflection
            .fieldReflection
            .allFields(ownedSelections.type)
            .asSequence()
            .mapNotNull { it as? CompositeField<T, *> }
            .filter { requestedSelections.contains(it) }
            .mapNotNull { field ->
                val connection = typeReflection.connection(field.type, ownerType = ownedSelections.type)
                val fieldSelections =
                    connection?.let {
                        childSelections(requestedSelections, field)
                    }
                referenceFor(
                    field,
                    paginationArguments[field.name] ?: ConnectionPaginationArguments.none(),
                    fieldSelections,
                    ownedSelections.type,
                )
            }.distinctBy(NodeReferenceSelection::fieldName)
            .toList() + GlobalIdReferencePlanner.plan(requestedSelections, ownedSelections.type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : CompositeOutput> childSelections(
        parent: SelectionSet<T>,
        field: CompositeField<*, *>,
    ): SelectionSet<*> =
        (parent as SelectionSet<CompositeOutput>).selectionSetFor(
            field as CompositeField<CompositeOutput, CompositeOutput>,
        )

    private fun referenceFor(
        field: CompositeField<*, *>,
        paginationArguments: ConnectionPaginationArguments,
        requestedFieldSelections: SelectionSet<*>?,
        ownerType: Type<*>,
    ): NodeReferenceSelection? {
        val connection = typeReflection.connection(field.type, requestedFieldSelections, ownerType)
        val abstract =
            dev.viaduct.persistence.runtime.reflection.AbstractTypeMappings
                .load(ownerType.kcls.java.classLoader)
                .relationship(ownerType.name, field.name)
        if (abstract != null) {
            return NodeReferenceSelection(
                field.name,
                field.type,
                NodeReferenceKind.ABSTRACT,
                connection?.nodeField?.type ?: field.type,
                connection = connection?.copy(edge = connection.edge.copy(isAssociationBacked = false)),
                connectionArguments = paginationArguments,
                abstractRelationship = abstract,
            )
        }
        val collectionElementType = typeReflection.legacyCollectionNodeType(field.type)
        return when {
            connection != null ->
                NodeReferenceSelection(
                    fieldName = field.name,
                    targetType = field.type,
                    kind = NodeReferenceKind.CONNECTION,
                    nodeType = connection.nodeField.type,
                    connection = connection,
                    connectionArguments = paginationArguments,
                )
            collectionElementType != null ->
                NodeReferenceSelection(
                    fieldName = field.name,
                    targetType = field.type,
                    kind = NodeReferenceKind.LEGACY_COLLECTION,
                    nodeType = collectionElementType,
                )
            NodeObject::class.java.isAssignableFrom(field.type.kcls.java) ->
                NodeReferenceSelection(
                    fieldName = field.name,
                    targetType = field.type,
                    kind = if (isListField(ownerType, field.name)) NodeReferenceKind.LIST else NodeReferenceKind.TO_ONE,
                    nodeType = field.type,
                )
            else -> null
        }
    }

    private fun isListField(
        owner: Type<*>,
        name: String,
    ): Boolean =
        owner.kcls.java.declaredClasses
            .firstOrNull { it.simpleName == "Builder" }
            ?.methods
            ?.any {
                it.name == name &&
                    it.parameterCount == 1 &&
                    Collection::class.java.isAssignableFrom(it.parameterTypes.single())
            } == true
}

internal enum class NodeReferenceKind(
    val isCollection: Boolean,
) {
    TO_ONE(false),
    GLOBAL_ID(false),
    LIST(true),
    LEGACY_COLLECTION(true),
    CONNECTION(true),
    ABSTRACT(false),
}

internal data class NodeReferenceSelection(
    val fieldName: String,
    val targetType: Type<*>,
    val kind: NodeReferenceKind,
    val nodeType: Type<*>,
    val connection: ConnectionShape? = null,
    val connectionArguments: ConnectionPaginationArguments = ConnectionPaginationArguments.none(),
    val abstractRelationship: dev.viaduct.persistence.runtime.reflection.AbstractRelationship? = null,
) {
    val responseAlias: String = "_viaduct_ref_$fieldName"
    val responseKeys: Set<String> =
        java.util.Set.of(if (kind.isCollection || kind == NodeReferenceKind.ABSTRACT) fieldName else responseAlias)

    val upstreamSelection: String
        get() = upstreamSelection(null)

    fun upstreamSelection(typeReflection: GeneratedTypeReflection?): String =
        when (kind) {
            NodeReferenceKind.ABSTRACT -> {
                val relationship = requireNotNull(abstractRelationship)
                val node = "__typename " + relationship.targets.joinToString(" ") { "... on $it { uuidId }" }
                if (relationship.connectionType == null) {
                    "$fieldName { $node }"
                } else {
                    val customFields =
                        connection
                            ?.edge
                            ?.customFields
                            .orEmpty()
                            .joinToString(" ") { field ->
                                field.valueSelection(typeReflection)
                            }
                    "$fieldName${connectionArguments.render()} { edges { cursor node { $node } $customFields } " +
                        "pageInfo { hasNextPage hasPreviousPage startCursor endCursor } }"
                }
            }
            NodeReferenceKind.CONNECTION ->
                checkNotNull(connection) {
                    "Connection reference '$fieldName' has no reflected connection shape"
                }.upstreamSelection(fieldName, connectionArguments, typeReflection)
            NodeReferenceKind.LEGACY_COLLECTION ->
                "$fieldName { nodes { uuidId } }"
            NodeReferenceKind.LIST ->
                listSelection()
            NodeReferenceKind.TO_ONE ->
                "$responseAlias: ${fieldName}Id"
            NodeReferenceKind.GLOBAL_ID ->
                "$responseAlias: $fieldName"
        }

    fun listSelection(after: String? = null): String {
        val arguments = after?.let { "(after: ${kotlinx.serialization.json.JsonPrimitive(it)})" }.orEmpty()
        return "$fieldName$arguments { edges { node { uuidId } } pageInfo { hasNextPage endCursor } }"
    }
}

package dev.viaduct.persistence.pggraphql.translation

internal const val VIADUCT_NODES_RESPONSE_ALIAS = "_viaduct_nodes"
internal const val VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX = "_viaduct_association_connection_"
internal const val VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX = "_viaduct_association_edges_"
internal const val VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX = "_viaduct_association_nodes_"
internal const val VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX = "_viaduct_association_node_"
internal const val VIADUCT_ASSOCIATION_ROW_ALIAS = "_viaduct_association_row"

internal fun internalAssociationAlias(
    prefix: String,
    responseKey: String,
): String = "$prefix$responseKey"

internal fun isInternalAssociationAlias(alias: String?): Boolean =
    alias != null &&
        listOf(
            VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX,
            VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX,
            VIADUCT_ASSOCIATION_NODES_ALIAS_PREFIX,
            VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX,
            VIADUCT_ASSOCIATION_ROW_ALIAS,
            ABSTRACT_ALIAS_PREFIX,
        ).any(alias::startsWith)

internal fun responseKeyFromInternalAlias(
    prefix: String,
    alias: String,
): String = alias.removePrefix(prefix)

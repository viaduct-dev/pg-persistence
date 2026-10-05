package dev.viaduct.persistence.pggraphql.translation

internal const val ABSTRACT_ALIAS_PREFIX = "_viaduct_abstract_"
internal const val ABSTRACT_LIST_PREFIX = "_viaduct_abstract_list_"
internal const val ABSTRACT_LIST_PAGE_PREFIX = "_viaduct_paged_abstract_list_"
internal const val ABSTRACT_NODES_PREFIX = "_viaduct_abstract_nodes_"
internal const val ABSTRACT_TYPE_PREFIX = "_viaduct_abstract_type_"

internal fun abstractAlias(
    key: String,
    type: String,
): String = "$ABSTRACT_ALIAS_PREFIX${key.length}_${key}_$type"

internal fun typeAlias(
    key: String,
    type: String,
): String = "$ABSTRACT_TYPE_PREFIX${key.length}_${key}_$type"

internal fun decodeAbstractAlias(
    alias: String,
    prefix: String = ABSTRACT_ALIAS_PREFIX,
): Pair<String, String> {
    val encoded = alias.removePrefix(prefix)
    val length = encoded.substringBefore('_').toInt()
    val rest = encoded.substringAfter('_')
    require(rest.length > length && rest[length] == '_') { "Invalid internal abstract alias $alias" }
    return rest.take(length) to rest.drop(length + 1)
}

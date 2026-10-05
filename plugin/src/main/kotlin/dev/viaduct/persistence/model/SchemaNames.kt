package dev.viaduct.persistence.model

private val ACRONYM_NORMALIZATIONS =
    listOf(
        Regex("GitHub") to "Github",
        Regex("GraphQL") to "Graphql",
    )

private val INVARIANT_PLURAL_NOUNS =
    setOf(
        "news",
        "series",
        "species",
    )

private val VOWEL_Y_ENDINGS = setOf("ay", "ey", "iy", "oy", "uy")

fun toSnakeCase(name: String): String {
    var normalized = name
    for ((pattern, replacement) in ACRONYM_NORMALIZATIONS) {
        normalized = normalized.replace(pattern, replacement)
    }
    return normalized
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
}

fun toTableName(typeName: String): String {
    val snake = toSnakeCase(typeName)
    val noun = snake.substringAfterLast('_')
    return when {
        noun in INVARIANT_PLURAL_NOUNS -> snake
        snake.endsWith("_identity") || snake == "identity" ->
            snake.dropLast("identity".length) + "identities"
        snake.endsWith("icy") ->
            snake.dropLast(1) + "ies"
        endsWithConsonantY(snake) ->
            snake.dropLast(1) + "ies"
        needsEsSuffix(snake) ->
            snake + "es"
        else -> snake + "s"
    }
}

private fun endsWithConsonantY(name: String): Boolean = name.endsWith("y") && VOWEL_Y_ENDINGS.none(name::endsWith)

internal fun needsEsSuffix(
    name: String,
    endings: List<String> = listOf("s", "x", "z"),
): Boolean = endings.any(name::endsWith)

package dev.viaduct.persistence.gradle

/** Only familiar additive statements are automatic. Unfamiliar SQL always requires review. */
internal object ConservativeSql {
    fun isAllowed(sql: String): Boolean {
        val statements = tokens(sql) ?: return false
        return statements.isNotEmpty() && statements.all(::isAllowedStatement)
    }

    private fun isAllowedStatement(tokens: List<String>): Boolean =
        when {
            tokens.take(2) == listOf("CREATE", "TABLE") ->
                "(" in tokens && "AS" !in tokens && "SELECT" !in tokens
            tokens.take(2) == listOf("CREATE", "INDEX") -> true
            tokens.take(2) == listOf("COMMENT", "ON") -> tokens.takeLast(2) == listOf("IS", "STRING")
            tokens.take(2) == listOf("ALTER", "TABLE") ->
                isNullableColumnAddition(tokens)
            else -> false
        }

    private fun isNullableColumnAddition(tokens: List<String>): Boolean {
        if (tokens.count { it == "ADD" } != 1 || tokens.drop(2).any { it in UNSAFE_COLUMN_TOKENS }) return false
        val addition = tokens.drop(tokens.indexOf("ADD") + 1)
        val definition = if (addition.firstOrNull() == "COLUMN") addition.drop(1) else addition
        // Pseudo-types (serial) and domains can add defaults or NOT NULL implicitly.
        return definition.getOrNull(1) in NULLABLE_COLUMN_TYPES
    }

    private val NULLABLE_COLUMN_TYPES =
        setOf(
            "BOOLEAN",
            "BOOL",
            "SMALLINT",
            "INT2",
            "INTEGER",
            "INT",
            "INT4",
            "BIGINT",
            "INT8",
            "REAL",
            "FLOAT4",
            "DOUBLE",
            "FLOAT8",
            "NUMERIC",
            "DECIMAL",
            "TEXT",
            "VARCHAR",
            "CHAR",
            "CHARACTER",
            "BYTEA",
            "UUID",
            "JSON",
            "JSONB",
            "DATE",
            "TIME",
            "TIMETZ",
            "TIMESTAMP",
            "TIMESTAMPTZ",
            "INTERVAL",
        )

    private fun tokens(sql: String): List<List<String>>? {
        val statements = mutableListOf<List<String>>()
        var statement = mutableListOf<String>()
        val reader = SqlTokens(sql)
        while (reader.hasMore) {
            when (val token = reader.next() ?: return null) {
                "" -> Unit
                ";" -> {
                    if (statement.isNotEmpty()) statements += statement
                    statement = mutableListOf()
                }
                else -> statement += token
            }
        }
        if (statement.isNotEmpty()) statements += statement
        return statements
    }

    private val UNSAFE_COLUMN_TOKENS =
        setOf(
            "NOT",
            "DEFAULT",
            "GENERATED",
            "REFERENCES",
            "CONSTRAINT",
            "CHECK",
            "UNIQUE",
            "PRIMARY",
            "FOREIGN",
            "EXCLUDE",
            "DROP",
            "ALTER",
            "SET",
            "RENAME",
            ",",
        )
}

/** Minimal SQL lexical reader; unsupported or unterminated syntax fails closed. */
private class SqlTokens(
    private val sql: String,
) {
    private var index = 0
    val hasMore: Boolean get() = index < sql.length

    fun next(): String? {
        val character = sql[index]
        return when {
            character.isWhitespace() -> {
                index++
                ""
            }
            sql.startsWith("--", index) -> {
                index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length
                ""
            }
            sql.startsWith("/*", index) -> skipComment()
            character == '\'' || character == '"' -> quoted(character)
            character == '$' -> null // Dollar-quoted bodies require review.
            character.isLetterOrDigit() || character == '_' -> word()
            else -> {
                index++
                character.toString()
            }
        }
    }

    private fun skipComment(): String? {
        var depth = 1
        index += 2
        while (hasMore && depth > 0) {
            when {
                sql.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                sql.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }
                else -> index++
            }
        }
        return "".takeIf { depth == 0 }
    }

    private fun quoted(quote: Char): String? {
        val start = ++index
        while (hasMore) {
            when {
                sql[index] == '\\' -> break // Escape semantics depend on session settings.
                sql[index] != quote -> index++
                sql.getOrNull(index + 1) == quote -> index += 2
                else -> {
                    val token =
                        when {
                            quote == '"' -> "IDENTIFIER"
                            index == start -> "EMPTY_STRING"
                            else -> "STRING"
                        }
                    index++
                    return token
                }
            }
        }
        return null
    }

    private fun word(): String {
        val start = index++
        while (hasMore && (sql[index].isLetterOrDigit() || sql[index] == '_')) index++
        return sql.substring(start, index).uppercase()
    }
}

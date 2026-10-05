package dev.viaduct.persistence.runtime.connection

import viaduct.api.select.OutputSelectionFragment

/** The standard pagination arguments declared by a Viaduct connection field. */
internal class ConnectionPaginationArguments private constructor(
    private val renderedArguments: List<String>,
) {
    fun render(): String =
        renderedArguments.takeUnless(List<String>::isEmpty)?.joinToString(
            prefix = "(",
            postfix = ")",
            separator = ",",
        ) ?: ""

    companion object {
        /** Used only by storage queries, never by resolver-owned selections. */
        fun backend(arguments: String) =
            ConnectionPaginationArguments(
                listOf(arguments.removePrefix("(").removeSuffix(")")).filter(String::isNotBlank),
            )

        fun none() = ConnectionPaginationArguments(emptyList())

        internal fun fromArguments(arguments: List<String>) = ConnectionPaginationArguments(arguments)

        fun fromFragment(fragment: OutputSelectionFragment): Map<String, ConnectionPaginationArguments> =
            ConnectionArgumentExtractor.fromFragment(fragment)
    }
}

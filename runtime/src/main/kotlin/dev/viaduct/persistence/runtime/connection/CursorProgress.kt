package dev.viaduct.persistence.runtime.connection

/** Local to a paging loop; provider cursors must make progress. */
internal class CursorProgress(
    private val description: String,
) {
    private val seen = mutableSetOf<String>()

    fun record(cursor: String) {
        check(seen.add(cursor)) { "$description repeated cursor '$cursor'" }
    }
}

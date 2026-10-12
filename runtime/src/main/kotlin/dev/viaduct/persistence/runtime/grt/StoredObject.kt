package dev.viaduct.persistence.runtime.grt

/** Values consumed while building a detached Viaduct object, not a query or persistence API. */
@viaduct.apiannotations.InternalApi
interface StoredObject {
    val type: String
    val id: String

    fun value(field: String): Any?
}

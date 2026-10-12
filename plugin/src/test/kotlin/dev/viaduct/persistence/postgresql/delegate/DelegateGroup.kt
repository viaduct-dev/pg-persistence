@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import viaduct.api.internal.ObjectBase

/** Scalar state stays in the GRT; Hibernate owns FK/join collections and their dirty checking. */
internal open class DelegateGroup() : DelegateEntity() {
    private var nativeMembers: MutableList<DelegateMembership> = arrayListOf()
    private var nativeUsers: MutableList<DelegatePerson> = arrayListOf()
    private var nativeFeaturedUsers: MutableList<DelegatePerson> = arrayListOf()
    protected override val nativeFields: Set<String> = COLLECTION_FIELDS

    constructor(binding: DelegateBinding) : this() {
        bind(binding)
    }

    open var name: String
        get() = value.get("name", String::class)
        set(value) = state.put("name", value)

    // These setters only retain Hibernate's collection wrappers. Hydration must not enumerate them.
    open var members: MutableList<DelegateMembership>
        get() = nativeMembers
        set(value) {
            nativeMembers = value
        }

    open var users: MutableList<DelegatePerson>
        get() = nativeUsers
        set(value) {
            nativeUsers = value
        }

    open var featuredUsers: MutableList<DelegatePerson>
        get() = nativeFeaturedUsers
        set(value) {
            nativeFeaturedUsers = value
        }

    /** Resolve only selected collections while the session is open; publish immutable node refs. */
    internal override fun selected(fields: Set<String>): ObjectBase {
        if (fields.none { it in COLLECTION_FIELDS }) return state.selected(fields)
        val builder = GrtDelegate.toBuilder(value)
        if ("members" in fields) builder.put("members", members.map { reference("membership", it) })
        if ("users" in fields) builder.put("users", users.map { reference("person", it) })
        if ("featuredUsers" in fields) {
            builder.put("featuredUsers", featuredUsers.map { reference("person", it) })
        }
        return state.selected(fields, builder.build() as ObjectBase)
    }

    companion object {
        val COLLECTION_FIELDS: Set<String> = java.util.Set.of("members", "users", "featuredUsers")
        val PERSISTED_FIELDS: Set<String> = java.util.Set.of("id", "name", "members", "users", "featuredUsers")
    }
}

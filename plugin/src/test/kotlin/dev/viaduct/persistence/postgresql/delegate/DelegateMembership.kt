@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

/** A membership is an ordinary Hibernate entity with its own role and native person association. */
internal open class DelegateMembership() : DelegateEntity() {
    private var nativePerson: DelegatePerson? = null
    private var nativeGroup: DelegateGroup? = null
    protected override val nativeFields: Set<String> = java.util.Set.of("person", "groupId")

    constructor(binding: DelegateBinding) : this() {
        bind(binding)
    }

    open var role: String
        get() = value.get("role", String::class)
        set(value) = state.put("role", value)

    open var person: DelegatePerson?
        get() = nativePerson
        set(value) {
            nativePerson = value
            state.put("person", value?.let { reference("person", it) })
        }

    // @idOf preserves the schema's exact property name while Hibernate stores the association.
    open var groupId: DelegateGroup?
        get() = nativeGroup
        set(value) {
            nativeGroup = value
            val binding = state.binding
            state.put(
                "groupId",
                value?.let {
                    binding.context.globalIDFor(binding.types.getValue("group"), checkNotNull(it.internalId).toString())
                },
            )
        }

    companion object {
        val PERSISTED_FIELDS: Set<String> = java.util.Set.of("id", "role", "person", "groupId")
    }
}

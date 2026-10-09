@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import org.hibernate.Session
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineObjectData
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Set.of
import java.util.UUID

/**
 * Handwritten stand-in for one generated Hibernate delegate, not a new public entity API.
 * Only the native identifier and Hibernate association are sidecars; scalars are read from GRTs.
 * The no-arg constructor permits Hibernate proxies. Real entities receive a per-session binding.
 */
internal open class DelegatePerson() : DelegateEntity() {
    private var nativeManager: DelegatePerson? = null
    private var nativeReports: MutableList<DelegatePerson> = arrayListOf()
    protected override val nativeFields: Set<String> = of("manager", "reports")

    constructor(binding: DelegateBinding) : this() {
        bind(binding)
    }

    // Hibernate installs its PersistentBag here. Never store that mutable/session-bound bag in a GRT.
    open var reports: MutableList<DelegatePerson>
        get() = nativeReports
        set(value) {
            nativeReports = value
        }

    internal override fun selected(fields: Set<String>): ObjectBase {
        if ("reports" !in fields) return state.selected(fields)
        val snapshot =
            GrtDelegate
                .toBuilder(value)
                .put(
                    "reports",
                    reports.map { reference("person", it) },
                ).build() as ObjectBase
        return state.selected(fields, snapshot)
    }

    open var username: String
        get() = state.value.get("username", String::class)
        set(value) = state.put("username", value)

    open var nickname: String?
        get() = state.value.get("nickname", String::class)
        set(value) = state.put("nickname", value)

    open var status: String?
        get() = state.value.get<Enum<*>?>("status", state.binding.enumClass.kotlin)?.name
        set(value) = state.put("status", value?.let(state.binding::enumValue))

    open var happenedAt: OffsetDateTime?
        get() = state.value.get<Instant?>("happenedAt", Instant::class)?.atOffset(ZoneOffset.UTC)
        set(value) = state.put("happenedAt", value?.toInstant())

    open var manager: DelegatePerson?
        get() = nativeManager
        set(value) {
            nativeManager = value
            val binding = state.binding
            state.put(
                "manager",
                value?.let {
                    val id = binding.context.globalIDFor(binding.type, checkNotNull(it.internalId).toString())
                    binding.context.ref(id)
                },
            )
        }

    /** Reject incomplete replacements before changing any managed state. */
    fun replace(
        value: ObjectBase,
        session: Session,
    ) {
        require(
            state.binding.type.kcls.java
                .isInstance(value),
        ) { "Wrong GRT type" }
        val data = value.__engineObject as EngineObjectData.Sync
        require(PERSISTED_FIELDS.all(data::isPresent)) { "A replacement must contain all persisted fields" }
        val id = value.get<GlobalID<*>>("id", GlobalID::class)
        require(id.type == state.binding.type && UUID.fromString(id.internalID) == internalId) { "Wrong identity" }
        require(!data.isPresent("reports")) { "Update inverse collections through their owning association" }
        // Validate every scalar before assigning. A bad or partial GRT cannot damage pending state.
        value.get<String>("username", String::class)
        value.get<String?>("nickname", String::class)
        value.get<Enum<*>?>("status", state.binding.enumClass.kotlin)
        value.get<Instant?>("happenedAt", Instant::class)
        val manager = value.get<NodeObject?>("manager", NodeObject::class)
        nativeManager =
            manager?.let {
                val managerId = (it as ObjectBase).get<GlobalID<*>>("id", GlobalID::class)
                require(managerId.type == state.binding.type) { "Wrong relationship target" }
                session.getReference(state.binding.type.name, UUID.fromString(managerId.internalID)) as DelegatePerson
            }
        state.replace(value)
    }

    companion object {
        val PERSISTED_FIELDS: Set<String> = of("id", "username", "nickname", "status", "happenedAt", "manager")
    }
}

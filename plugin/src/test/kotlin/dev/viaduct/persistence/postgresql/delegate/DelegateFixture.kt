@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.postgresql.ApprovalRequestFixture
import dev.viaduct.persistence.postgresql.PostgresqlOverlay
import dev.viaduct.persistence.postgresql.withGeneratedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hibernate.Hibernate
import org.hibernate.Interceptor
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.metamodel.spi.EntityRepresentationStrategy
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.mocks.resolverExecutionContext
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** A contained native PostgreSQL experiment: no pg_graphql execution, overlay, or projector. */
internal class DelegateFixture(
    val type: Type<NodeObject>,
    types: Map<String, Type<NodeObject>>,
    private val enumClass: Class<*>,
    val internal: MockInternalContext,
    val factory: SessionFactory,
) {
    val types: Map<String, Type<NodeObject>> = java.util.Map.copyOf(types)
    val context = internal.resolverExecutionContext
    val schema get() = internal.schema
    private val selections = SelectionSetFactoryImpl(EngineSelectionSetFactoryImpl(schema), internal.globalIDCodec)

    fun binding(
        context: ResolverExecutionContext<Query> = this.context,
        target: Type<NodeObject> = type,
    ) = DelegateBinding(context, target, enumClass, types)

    fun group(name: String): DelegateGroup =
        DelegateGroup(binding(target = types.getValue("group"))).also {
            it.initialize(binding(target = types.getValue("group")).builder().put("name", name).build() as ObjectBase)
        }

    fun membership(
        person: DelegatePerson,
        group: DelegateGroup,
        role: String = "MEMBER",
    ): DelegateMembership =
        DelegateMembership(binding(target = types.getValue("membership"))).also {
            it.role = role
            it.person = person
            it.groupId = group
        }

    fun person(
        username: String,
        nickname: String? = "initial",
        status: String? = "ACTIVE",
        happenedAt: Instant? = null,
    ): ObjectBase =
        binding()
            .builder()
            .put("username", username)
            .put("nickname", nickname)
            .put("status", status?.let(binding()::enumValue))
            .put("happenedAt", happenedAt)
            .put("manager", null)
            .build() as ObjectBase

    suspend fun insert(username: String): GlobalID<NodeObject> =
        transaction { session ->
            val entity = DelegatePerson(binding()).also { it.initialize(person(username)) }
            session.persist(type.name, entity)
            id(entity.value)
        }

    fun id(value: ObjectBase): GlobalID<NodeObject> = value.get("id", GlobalID::class)

    fun nodeContext(
        id: GlobalID<NodeObject>,
        fields: String,
    ) = MockNodeExecutionContext(id, null, selections.selectionsOn(id.type, fields, emptyMap()), internal)

    suspend fun fetch(context: SelectiveNodeExecutionContext<NodeObject>): ObjectBase =
        transaction(context) { session ->
            val entity = findEntity(session, context.id)
            val owned = context.ownedSelections()
            val requested = context.selections()
            val (persisted, relationships) =
                when (entity) {
                    is DelegatePerson -> (DelegatePerson.PERSISTED_FIELDS + "reports") to setOf("manager", "reports")
                    is DelegateGroup -> DelegateGroup.PERSISTED_FIELDS to DelegateGroup.COLLECTION_FIELDS
                    else -> DelegateMembership.PERSISTED_FIELDS to setOf("person")
                }
            val fields =
                persisted.filter { owned.contains(field(context.id.type, it)) }.toSet() +
                    relationships.filter { requested.contains(field(context.id.type, it)) }
            entity.selected(fields)
        }

    @Suppress("UNCHECKED_CAST")
    private fun field(
        type: Type<NodeObject>,
        name: String,
    ): Field<NodeObject> {
        val fields = Class.forName("${type.kcls.java.name}\$Fields", true, type.kcls.java.classLoader)
        return fields
            .getMethod("get" + name.replaceFirstChar(Char::uppercaseChar))
            .invoke(fields.getField("INSTANCE").get(null)) as Field<NodeObject>
    }

    fun find(
        session: Session,
        id: GlobalID<NodeObject>,
    ): DelegatePerson = findEntity(session, id) as DelegatePerson

    fun findEntity(
        session: Session,
        id: GlobalID<NodeObject>,
    ): DelegateEntity {
        val entity = session.find(id.type.name, UUID.fromString(id.internalID))
        return Hibernate.unproxy(requireNotNull(entity) { "Node not found" }) as DelegateEntity
    }

    /** Execute collection children through real node resolvers instead of recursively hydrating graphs. */
    suspend fun execute(
        id: GlobalID<NodeObject>,
        query: String,
        denied: Boolean = false,
    ): List<Any?> =
        withTimeout(10000) {
            var response: List<Any?>? = null
            EngineTestModule(schema) {
                fieldWithValue("Query" to "healthy", "ok")
                val root = if (id.type == type) "person" else "group"
                field("Query" to root) {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            val nodeType = checkNotNull(schema.schema.getObjectType(id.type.name))
                            ctx.createNodeReference(id.internalID, nodeType)
                        }
                    }
                }
                types.values.forEach { nodeType ->
                    type(nodeType.name) {
                        nodeUnbatchedExecutor(true) { internalId, selections, engineContext ->
                            val nodeId = context.globalIDFor(nodeType, internalId)
                            val base = nodeContext(nodeId, checkNotNull(selections).printAsFieldSet())
                            val nodeContext =
                                object :
                                    SelectiveNodeExecutionContext<NodeObject> by base,
                                    InternalContext by internal {
                                    // Stub mock refs cannot fetch selections. Use this execution's real references.
                                    override fun <T : NodeObject> ref(id: GlobalID<T>): T {
                                        val reference =
                                            engineContext.createNodeReference(
                                                id.internalID,
                                                checkNotNull(schema.schema.getObjectType(id.type.name)),
                                            )
                                        @Suppress("UNCHECKED_CAST")
                                        return id.type.kcls.java
                                            .getConstructor(
                                                InternalContext::class.java,
                                                EngineObject::class.java,
                                            ).newInstance(this, reference) as T
                                    }
                                }
                            fetch(nodeContext).__engineObject as EngineObjectData
                        }
                    }
                }
                field(type.name to "username") {
                    checker { fn { _, _ -> check(!denied) { "Access denied" } } }
                }
            }.runFeatureTest {
                val result = runQuery(query)
                response = listOf(result.getData<Map<String, Any?>>(), result.errors.map { it.path })
            }
            checkNotNull(response)
        }

    /** Native Hibernate sessions stay serial and confined to a single transaction thread. */
    suspend fun <T> transaction(
        context: ResolverExecutionContext<Query> = this.context,
        block: (Session) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            val interceptor =
                object : Interceptor {
                    override fun instantiate(
                        entityName: String,
                        representationStrategy: EntityRepresentationStrategy,
                        id: Any?,
                    ): Any? =
                        types.values.singleOrNull { it.name == entityName }?.let { target ->
                            val binding = binding(context, target)
                            val entity =
                                when (target) {
                                    types.getValue("person") -> DelegatePerson(binding)
                                    types.getValue("group") -> DelegateGroup(binding)
                                    else -> DelegateMembership(binding)
                                }
                            // A custom Interceptor.instantiate must assign Hibernate's supplied ID.
                            entity.also { it.internalId = id as UUID? }
                        }
                }
            factory.withOptions().interceptor(interceptor).openSession().use { session ->
                val tx = session.beginTransaction()
                runCatching {
                    block(session).also {
                        coroutine.ensureActive()
                        tx.commit()
                    }
                }.getOrElse {
                    if (tx.isActive) tx.rollback()
                    throw it
                }
            }
        }

    companion object {
        private const val GRT_PACKAGE = "dev.viaduct.persistence.approvalfixture"

        fun withFixture(test: suspend (DelegateFixture) -> Unit) {
            val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
            require(listOf("jdbc:postgresql://127.0.0.1:", "jdbc:postgresql://localhost:").any(url::startsWith))
            val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
            val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val name = "DelegatePerson$suffix"
            val sdl = schema(suffix)
            val settings =
                mapOf(
                    "hibernate.connection.url" to url,
                    "hibernate.connection.username" to user,
                    "hibernate.connection.password" to password,
                    "hibernate.connection.driver_class" to "org.postgresql.Driver",
                    "hibernate.boot.allow_jdbc_metadata_access" to "true",
                )
            DriverManager.getConnection(url, user, password).use { database ->
                withGeneratedModel(sdl, settings, persistencePolicy = "types:\n  $name:\n    unique: [[username]]") {
                    model,
                    schemaFile,
                    generated,
                    handle,
                    ->
                    val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
                    // Change only the entity representation; preserve generated tables and columns.
                    handle.metadata.entityBindings.forEach { mapping ->
                        val entityClass =
                            when (mapping.entityName) {
                                name -> DelegatePerson::class.java
                                "DelegateGroup$suffix" -> DelegateGroup::class.java
                                else -> DelegateMembership::class.java
                            }
                        mapping.className = entityClass.name
                        mapping.proxyInterfaceName = entityClass.name
                        mapping.isLazy = true
                    }
                    ApprovalRequestFixture.withGrts(schemaFile, generated) { loader ->
                        handle.metadata.sessionFactoryBuilder
                            .applyStatementInspector { sql ->
                                check("graphql.resolve" !in sql.lowercase()) { "Native SQL only" }
                                sql
                            }.build()
                            .use { factory ->
                                try {
                                    factory.schemaManager.exportMappedObjects(false)
                                    JdbcOperations.execute(database, PostgresqlOverlay.renderMigration(effective))
                                    runBlocking { test(fixture(sdl, name, suffix, loader, factory)) }
                                } finally {
                                    factory.schemaManager.dropMappedObjects(false)
                                }
                            }
                    }
                }
            }
        }

        private fun fixture(
            sdl: String,
            name: String,
            suffix: String,
            loader: ClassLoader,
            factory: SessionFactory,
        ): DelegateFixture {
            val schema =
                createSchemaWithWiring(
                    sdl
                        .removePrefix("interface Node { id: ID! }")
                        .replace("type Query {", "extend type Query {")
                        .lineSequence()
                        .filterNot { it.startsWith("scalar ") || it.startsWith("directive @") }
                        .joinToString("\n"),
                )
            val internal = MockInternalContext.create(schema, GRT_PACKAGE, loader)

            val types =
                mapOf(
                    "person" to name,
                    "group" to "DelegateGroup$suffix",
                    "membership" to "DelegateMembership$suffix",
                ).mapValues { (_, typeName) ->
                    @Suppress("UNCHECKED_CAST")
                    (
                        loader
                            .loadClass("$GRT_PACKAGE.$typeName\$Reflection")
                            .getField("INSTANCE")
                            .get(null) as Type<NodeObject>
                    )
                }
            val enumClass = loader.loadClass("$GRT_PACKAGE.DelegateStatus$suffix")
            return DelegateFixture(types.getValue("person"), types, enumClass, internal, factory)
        }

        internal fun schema(suffix: String): String =
            """
            interface Node { id: ID! }
            directive @idOf(type: String!) on FIELD_DEFINITION
            scalar DateTime
            enum DelegateStatus$suffix { ACTIVE INACTIVE }
            type DelegatePerson$suffix implements Node {
              id: ID!, username: String!, nickname: String, manager: DelegatePerson$suffix
              status: DelegateStatus$suffix, happenedAt: DateTime
              reports: [DelegatePerson$suffix!]!
            }
            type DelegateGroup$suffix implements Node {
              id: ID!, name: String!, members: [DelegateMembership$suffix!]!
              users: [DelegatePerson$suffix!]!, featuredUsers: [DelegatePerson$suffix!]!
            }
            type DelegateMembership$suffix implements Node {
              id: ID!, role: String!, person: DelegatePerson$suffix!, groupId: ID! @idOf(type: "DelegateGroup$suffix")
            }
            type Query { person: DelegatePerson$suffix, group: DelegateGroup$suffix, healthy: String! }
            """.trimIndent()
    }
}

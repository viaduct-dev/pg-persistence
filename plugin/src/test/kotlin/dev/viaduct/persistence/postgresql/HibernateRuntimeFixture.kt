@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.orm.HibernateClient
import dev.viaduct.persistence.pggraphql.overlay.PgGraphqlOverlay
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbLookup
import dev.viaduct.persistence.runtime.db.DbRead
import dev.viaduct.persistence.runtime.db.DbRoot
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import org.hibernate.SessionFactory
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.mocks.executionContext
import viaduct.api.mocks.resolverExecutionContext
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Input
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import java.sql.DriverManager
import java.util.UUID
import viaduct.api.types.Connection as ViaductConnection
import viaduct.api.types.Object as ViaductObject

internal class HibernateRuntimeFixture(
    val suffix: String,
    val loader: ClassLoader,
    val factory: SessionFactory,
    val database: java.sql.Connection,
    val sdl: String,
) {
    val client = HibernateClient(factory) { _, _ -> }

    val schema =
        createSchemaWithWiring(
            sdl
                .removePrefix("interface Node { id: ID! }")
                .replace("type Query {", "extend type Query {")
                .replace(Regex("type PageInfo \\{[^}]+\\}"), "")
                .lineSequence()
                .filterNot { it.startsWith("directive @") || it.startsWith("scalar ") }
                .joinToString("\n"),
        )
    val internal = MockInternalContext.create(schema, PACKAGE, loader)
    val ctx = internal.resolverExecutionContext
    private val selections = SelectionSetFactoryImpl(EngineSelectionSetFactoryImpl(schema), internal.globalIDCodec)

    @Suppress("UNCHECKED_CAST")
    fun type(name: String = "OrmPerson"): Type<NodeObject> =
        loader.loadClass("$PACKAGE.$name$suffix\$Reflection").getField("INSTANCE").get(null) as Type<NodeObject>

    fun id(
        value: String = UUID.randomUUID().toString(),
        name: String = "OrmPerson",
    ): GlobalID<NodeObject> = ctx.globalIDFor(type(name), value)

    @Suppress("UNCHECKED_CAST")
    fun field(
        name: String,
        owner: String = "OrmPerson",
    ): Field<NodeObject> {
        val fields = loader.loadClass("$PACKAGE.$owner$suffix\$Fields")
        return fields
            .getMethod("get" + name.replaceFirstChar(Char::uppercaseChar))
            .invoke(fields.getField("INSTANCE").get(null)) as Field<NodeObject>
    }

    fun input(
        vararg fields: Pair<String, Any?>,
        name: String = "OrmPersonInput",
    ): Input {
        val builderClass = loader.loadClass("$PACKAGE.$name$suffix\$Builder")
        val builder =
            builderClass
                .getConstructor(viaduct.api.context.ExecutionContext::class.java)
                .newInstance(internal.executionContext)
        fields.forEach { (field, value) ->
            builderClass.methods.single { it.name == field && it.parameterCount == 1 }.invoke(builder, value)
        }
        return builderClass.getMethod("build").invoke(builder) as Input
    }

    fun selections(
        fields: String,
        type: Type<NodeObject> = type(),
    ): SelectionSet<NodeObject> = selections.selectionsOn(type, fields, emptyMap())

    suspend fun orm(
        id: GlobalID<NodeObject>,
        fields: String,
    ): ObjectBase = client.fetchNode(nodeContext(id, fields)) as ObjectBase

    fun nodeContext(
        id: GlobalID<NodeObject>,
        fields: String,
    ) = MockNodeExecutionContext(id, null, selections(fields, id.type), internal)

    suspend fun update(
        id: GlobalID<NodeObject>,
        input: Input,
    ) {
        client.transaction(ctx) { session ->
            @Suppress("UNCHECKED_CAST")
            val entity = session.find(id.type.name, UUID.fromString(id.internalID)) as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val fields = input.javaClass.getMethod("getInputData").invoke(input) as Map<String, Any?>
            fields.keys.forEach { field ->
                val value = input.javaClass.getMethod("get" + field.replaceFirstChar(Char::uppercaseChar)).invoke(input)
                entity[field] =
                    if (value is GlobalID<*>) {
                        session.getReference(value.type.name, UUID.fromString(value.internalID))
                    } else {
                        value
                    }
            }
        }
    }

    suspend fun pg(
        id: GlobalID<NodeObject>,
        fields: String,
    ): ObjectBase =
        database.graphqlClient().use { http ->
            val nodeCtx = MockNodeExecutionContext(id, null, selections(fields, id.type), internal)
            DbClient(http, "http://fixture/graphql").fetchNode(
                nodeCtx,
                DbRead(
                    DbRoot(
                        PgGraphqlEntity(id.type.name).collectionField,
                        arguments = """(filter: {uuidId: {eq: "${id.internalID}"}})""",
                        singleViaFilteredCollection = true,
                    ),
                ),
            ) as ObjectBase
        }

    suspend fun insert(
        name: String,
        nickname: String? = "initial",
    ): GlobalID<NodeObject> =
        client.transaction(ctx) { session ->
            val entity = mutableMapOf<String, Any?>("username" to name, "nickname" to nickname)
            session.persist(type().name, entity)
            id(session.getIdentifier(entity).toString())
        }

    suspend fun lookup(
        field: String,
        key: Any?,
    ): List<String> =
        client.transaction(ctx) { session ->
            session
                .createSelectionQuery(
                    "from ${type().name} p where p.$field = :key " +
                        "order by p.internalId",
                    Any::class.java,
                ).setParameter("key", key)
                .resultList
                .map { session.getIdentifier(it).toString() }
        }

    @Suppress("UNCHECKED_CAST")
    fun connectionContext(
        arguments: Map<String, Any?>,
    ): ConnectionFieldExecutionContext<ViaductObject, Query, FixtureConnectionArguments, ViaductConnection<*, *>> =
        object :
            ConnectionFieldExecutionContext<ViaductObject, Query, FixtureConnectionArguments, ViaductConnection<*, *>>,
            ResolverExecutionContext<Query> by ctx,
            viaduct.api.internal.InternalContext by internal {
            override val arguments = FixtureConnectionArguments(arguments)

            override suspend fun getObjectValue(): ViaductObject = error("Not used")

            override suspend fun getQueryValue(): Query = error("Not used")
        }

    @Suppress("UNCHECKED_CAST")
    suspend fun page(
        arguments: Map<String, Any?>,
        pg: Boolean,
    ): ObjectBase {
        val context = connectionContext(arguments)
        val connection = type("OrmPersonConnection") as Type<ViaductConnection<*, *>>
        return if (pg) {
            database.graphqlClient().use { http ->
                val selected =
                    selections.selectionsOn(
                        connection,
                        "edges { cursor node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor }",
                        emptyMap(),
                    )
                DbClient(http, "http://fixture/graphql").lookupConnection(
                    context,
                    DbLookup.by<String, NodeObject>(field("nickname")),
                    "initial",
                    selected,
                ) as ObjectBase
            }
        } else {
            val selected =
                selections.selectionsOn(
                    connection,
                    "edges { cursor node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor }",
                    emptyMap(),
                )
            client.fetchConnection(context, selected) { session ->
                session
                    .createSelectionQuery(
                        "from ${type().name} p where p.nickname = :name " +
                            "order by p.internalId",
                        Any::class.java,
                    ).setParameter("name", "initial")
            } as ObjectBase
        }
    }

    companion object {
        const val PACKAGE = "dev.viaduct.persistence.approvalfixture"

        fun withFixture(
            pgGraphql: Boolean = true,
            inspect: (String) -> String = { it },
            namingStrategy: String = dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy::class.java.name,
            test: suspend (HibernateRuntimeFixture) -> Unit,
        ) {
            val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
            val localPrefixes = listOf("jdbc:postgresql://127.0.0.1:", "jdbc:postgresql://localhost:")
            require(localPrefixes.any(url::startsWith))
            val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
            val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val sdl = schema(suffix)
            DriverManager.getConnection(url, user, password).use { db ->
                val settings =
                    mapOf(
                        "hibernate.connection.url" to url,
                        "hibernate.connection.username" to user,
                        "hibernate.connection.password" to password,
                        "hibernate.connection.driver_class" to "org.postgresql.Driver",
                        "hibernate.boot.allow_jdbc_metadata_access" to "true",
                    )
                val policy = "types:\n  OrmPerson$suffix:\n    unique: [[username]]"
                withGeneratedModel(sdl, settings, namingStrategy = namingStrategy, persistencePolicy = policy) {
                    model,
                    schema,
                    generated,
                    handle,
                    ->
                    val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
                    handle.metadata.sessionFactoryBuilder
                        .applyStatementInspector { sql ->
                            check("graphql.resolve" !in sql.lowercase()) { "Hibernate must execute SQL directly" }
                            inspect(sql)
                        }.build()
                        .use { factory ->
                            factory.schemaManager.exportMappedObjects(false)
                            try {
                                JdbcOperations.execute(db, PostgresqlOverlay.renderMigration(effective))
                                if (pgGraphql) JdbcOperations.execute(db, PgGraphqlOverlay.render(effective))
                                ApprovalRequestFixture.withGrts(schema, generated) { loader ->
                                    val fixture = HibernateRuntimeFixture(suffix, loader, factory, db, sdl)
                                    kotlinx.coroutines.runBlocking { test(fixture) }
                                }
                            } finally {
                                factory.schemaManager.dropMappedObjects(false)
                            }
                        }
                }
            }
        }

        private fun schema(suffix: String): String =
            """
            interface Node { id: ID! }
            scalar JSON
            scalar BigDecimal
            scalar DateTime
            enum OrmStatus$suffix { ACTIVE INACTIVE }
            type OrmPerson$suffix implements Node {
              id: ID!, username: String!, nickname: String, manager: OrmPerson$suffix, labels: [String!]
              metadata: JSON, amount: BigDecimal, happenedAt: DateTime
              status: OrmStatus$suffix, statuses: [OrmStatus$suffix], happenedTimes: [DateTime]
            }
            input OrmPersonInput$suffix {
              username: String, nickname: String, managerId: ID @idOf(type: "OrmPerson$suffix")
              manager: ID @idOf(type: "OrmPerson$suffix"), usernameId: String, id: ID @idOf(type: "OrmPerson$suffix")
            }
            type OrmGroup$suffix implements Node { id: ID!, name: String!, members: [OrmMember$suffix!]! }
            type OrmMember$suffix implements Node { id: ID!, person: OrmPerson$suffix! }
            type OrmChecklist$suffix implements Node {
              id: ID!, title: String!, completed: Boolean!, groupId: ID! @idOf(type: "OrmGroup$suffix")
            }
            type OrmPersonEdge$suffix @edge { cursor: String!, node: OrmPerson$suffix! }
            type OrmPersonConnection$suffix @connection { edges: [OrmPersonEdge$suffix!]!, pageInfo: PageInfo! }
            type PageInfo {
              hasNextPage: Boolean!, hasPreviousPage: Boolean!, startCursor: String, endCursor: String
            }
            type Query { person: OrmPerson$suffix, healthy: String! }
            directive @edge on OBJECT
            directive @connection on OBJECT
            directive @idOf(type: String!) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION
            """.trimIndent()
    }
}

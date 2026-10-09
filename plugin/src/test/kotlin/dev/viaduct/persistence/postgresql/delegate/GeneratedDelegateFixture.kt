@file:OptIn(viaduct.apiannotations.InternalApi::class, viaduct.apiannotations.ExperimentalApi::class)

package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.hibernate.EffectiveHibernateModelBuilder
import dev.viaduct.persistence.hibernate.GrtDelegateGenerator
import dev.viaduct.persistence.hibernate.ViaductImplicitNamingStrategy
import dev.viaduct.persistence.hibernate.ViaductPhysicalNamingStrategy
import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.orm.grt.DelegateHibernateClient
import dev.viaduct.persistence.orm.grt.GrtBindings
import dev.viaduct.persistence.postgresql.ApprovalRequestFixture
import dev.viaduct.persistence.postgresql.FixtureConnectionArguments
import dev.viaduct.persistence.postgresql.PostgresqlOverlay
import dev.viaduct.persistence.postgresql.withGeneratedModel
import kotlinx.coroutines.runBlocking
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.context.SelectiveNodeExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockNodeExecutionContext
import viaduct.api.mocks.resolverExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Connection
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

private typealias FixtureDelegateConnectionContext =
    ConnectionFieldExecutionContext<viaduct.api.types.Object, Query, FixtureConnectionArguments, Connection<*, *>>

/** Typed test adapter is compiled against fresh, unchanged GRTs alongside generated delegates. */
interface GeneratedDelegateAccess {
    suspend fun batch(
        client: DelegateHibernateClient,
        contexts: List<SelectiveNodeExecutionContext<NodeObject>>,
    ): Map<SelectiveNodeExecutionContext<NodeObject>, viaduct.api.FieldValue<ObjectBase>>

    fun insert(
        client: DelegateHibernateClient,
        context: ResolverExecutionContext<out Query>,
        session: Session,
        value: ObjectBase,
    ): ObjectBase

    fun find(
        client: DelegateHibernateClient,
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: GlobalID<NodeObject>,
    ): ObjectBase

    fun update(
        client: DelegateHibernateClient,
        context: ResolverExecutionContext<out Query>,
        session: Session,
        value: ObjectBase,
    ): ObjectBase

    fun delete(
        client: DelegateHibernateClient,
        context: ResolverExecutionContext<out Query>,
        session: Session,
        id: GlobalID<NodeObject>,
    )

    suspend fun fetch(
        client: DelegateHibernateClient,
        context: SelectiveNodeExecutionContext<NodeObject>,
    ): ObjectBase

    @Suppress("LongParameterList") // Typed test adapter exposes native query variants explicitly.
    suspend fun connection(
        client: DelegateHibernateClient,
        context: ConnectionFieldExecutionContext<*, *, *, Connection<*, *>>,
        selections: SelectionSet<Connection<*, *>>,
        prePaged: Boolean,
        warmProxy: Boolean,
        identifiersOnly: Boolean,
    ): ObjectBase
}

internal class GeneratedDelegateFixture(
    val suffix: String,
    val loader: ClassLoader,
    val factory: SessionFactory,
    private val bindings: GrtBindings,
    val access: GeneratedDelegateAccess,
    val schemaUnchanged: Boolean,
) {
    private val statements = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun recordStatement(sql: String) {
        statements.add(sql)
    }

    fun clearStatements() = statements.clear()

    fun selectStatements(): List<String> = statements.filter { it.startsWith("select", ignoreCase = true) }

    private val extended = runCatching { loader.loadClass("$PACKAGE.DelegateRecord$suffix") }.isSuccess
    val internal =
        MockInternalContext.create(
            createSchemaWithWiring(
                schema(suffix, extended)
                    .removePrefix("interface Node { id: ID! }")
                    .replaceFirst("type Query {", "extend type Query {")
                    .lineSequence()
                    .filterNot { it.startsWith("scalar ") || it.startsWith("directive @") }
                    .joinToString("\n"),
            ),
            PACKAGE,
            loader,
        )
    val context = internal.resolverExecutionContext
    val types: Map<String, Type<NodeObject>> =
        java.util.Map.copyOf(
            (
                listOf(
                    "Person",
                    "Group",
                    "Membership",
                    "Scalar",
                ) + if (extended) listOf("Record") else emptyList()
            ).associateWith { kind ->
                @Suppress("UNCHECKED_CAST")
                (
                    loader
                        .loadClass("$PACKAGE.Delegate$kind$suffix\$Reflection")
                        .getField("INSTANCE")
                        .get(null) as Type<NodeObject>
                )
            },
        )
    val client =
        DelegateHibernateClient(factory, bindings) { session, _ ->
            // Bound blocking SQL on this transaction, rather than assuming coroutine deadlines stop JDBC.
            session.createNativeMutationQuery("set local lock_timeout = '1s'").executeUpdate()
            session.createNativeMutationQuery("set local statement_timeout = '3s'").executeUpdate()
        }
    private val selections =
        SelectionSetFactoryImpl(
            EngineSelectionSetFactoryImpl(internal.schema),
            internal.globalIDCodec,
        )

    fun person(
        name: String,
        manager: ObjectBase? = null,
        nickname: String? = "initial",
        happenedAt: Instant? = null,
    ): ObjectBase =
        builder("Person")
            .put("username", name)
            .put("nickname", nickname)
            .put("manager", manager)
            .put("happenedAt", happenedAt)
            .put("status", loader.loadClass("$PACKAGE.DelegateStatus$suffix").enumConstants.first())
            .build() as ObjectBase

    fun builder(kind: String): ObjectBase.Builder<*> =
        loader
            .loadClass("$PACKAGE.Delegate$kind$suffix\$Builder")
            .getConstructor(viaduct.api.context.ExecutionContext::class.java)
            .newInstance(context) as ObjectBase.Builder<*>

    fun nodeContext(
        id: GlobalID<NodeObject>,
        fields: String,
    ) = MockNodeExecutionContext(id, null, selections.selectionsOn(id.type, fields, emptyMap()), internal)

    suspend fun insert(value: ObjectBase): ObjectBase =
        client.transaction(context) { session ->
            access.insert(client, context, session, value)
        }

    fun newClient(initialize: (Session, ResolverExecutionContext<out Query>) -> Unit) =
        DelegateHibernateClient(factory, bindings, initialize)

    suspend fun connection(
        arguments: Map<String, Any?>,
        prePaged: Boolean = false,
        warmProxy: Boolean = false,
        identifiersOnly: Boolean = false,
    ): ObjectBase {
        val fields =
            "edges { cursor node { id } } nodes { id } " +
                "pageInfo { hasNextPage hasPreviousPage }"
        val selected = connectionSelections("People", fields)
        return access.connection(client, connectionContext(arguments), selected, prePaged, warmProxy, identifiersOnly)
    }

    suspend fun customConnection(
        arguments: Map<String, Any?>,
        kind: String,
        query: String,
    ): ObjectBase {
        val fields =
            "edges { cursor label node { __typename ... on DelegatePerson$suffix { id } " +
                "... on DelegateGroup$suffix { id } } } nodes { __typename } pageInfo { hasNextPage hasPreviousPage }"
        return client.fetchConnection(connectionContext(arguments), connectionSelections(kind, fields)) {
            it.createSelectionQuery(query, Any::class.java)
        } as ObjectBase
    }

    @Suppress("UNCHECKED_CAST") // Randomly named connection GRT loaded from this fixture.
    internal fun connectionSelections(kind: String, fields: String): SelectionSet<Connection<*, *>> {
        val type =
            loader
                .loadClass("$PACKAGE.Delegate$kind$suffix\$Reflection")
                .getField("INSTANCE")
                .get(null) as Type<Connection<*, *>>
        return selections.selectionsOn(type, fields, emptyMap())
    }

    internal fun connectionContext(arguments: Map<String, Any?>): FixtureDelegateConnectionContext =
        object :
            FixtureDelegateConnectionContext,
            ResolverExecutionContext<Query> by this.context,
            viaduct.api.internal.InternalContext by internal {
            override val arguments = FixtureConnectionArguments(arguments)

            override suspend fun getObjectValue(): viaduct.api.types.Object = error("Not needed")

            override suspend fun getQueryValue(): Query = error("Not needed")
        }

    @Suppress("UNCHECKED_CAST") // Test classes come from this fixture's isolated generated GRT loader.
    fun objectType(kind: String): Type<viaduct.api.types.Object> =
        loader
            .loadClass(
                "$PACKAGE.Delegate$kind$suffix\$Reflection",
            ).getField("INSTANCE")
            .get(null) as Type<viaduct.api.types.Object>

    fun objectSelections(
        kind: String,
        fields: String,
    ): SelectionSet<viaduct.api.types.Object> = selections.selectionsOn(objectType(kind), fields, emptyMap())

    @Suppress("UNCHECKED_CAST") // All native GRT delegates implement the common concrete Object contract.
    fun objectEntity(value: Any): dev.viaduct.persistence.orm.grt.GrtEntity<viaduct.api.types.Object> =
        org.hibernate.Hibernate.unproxy(value) as dev.viaduct.persistence.orm.grt.GrtEntity<viaduct.api.types.Object>

    fun binding(entityName: String): dev.viaduct.persistence.orm.grt.GrtBinding<*> {
        val companion = loader.loadClass("$PACKAGE.persistence.${entityName}Entity").getField("Companion").get(null)
        return companion.javaClass
            .getMethod(
                "getBINDING",
            ).invoke(companion) as dev.viaduct.persistence.orm.grt.GrtBinding<*>
    }

    fun id(value: ObjectBase): GlobalID<NodeObject> = value.get("id", GlobalID::class)

    fun native(
        session: Session,
        id: GlobalID<NodeObject>,
    ): Any = checkNotNull(session.find(id.type.name, UUID.fromString(id.internalID)))

    companion object {
        private const val PACKAGE = "dev.viaduct.persistence.approvalfixture"

        fun withFixture(
            extended: Boolean = false,
            test: suspend (GeneratedDelegateFixture) -> Unit,
        ) {
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
            require(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:"))
            val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
            val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
            val settings =
                mapOf(
                    "hibernate.connection.url" to url,
                    "hibernate.connection.username" to user,
                    "hibernate.connection.password" to password,
                    "hibernate.connection.driver_class" to "org.postgresql.Driver",
                    "hibernate.boot.allow_jdbc_metadata_access" to "true",
                )
            val policy = persistencePolicy(suffix, extended)
            DriverManager.getConnection(url, user, password).use { database ->
                withGeneratedModel(
                    schema(suffix, extended),
                    settings,
                    persistencePolicy = policy,
                    modelLoader = if (extended) extendedModelLoader(suffix) else null,
                ) { model, schema, generated, handle ->
                    val effective = EffectiveHibernateModelBuilder.build(handle.metadata, model)
                    withDelegates(model, schema, generated, suffix) { loader, bindings ->
                        withRuntimeMetadata(loader, generated, settings, bindings) { metadata ->
                            var fixture: GeneratedDelegateFixture? = null
                            withMappedTables(
                                metadata,
                                effective,
                                database,
                                { fixture?.recordStatement(it) },
                            ) { factory ->
                                val access =
                                    loader
                                        .loadClass("$PACKAGE.persistence.FixtureAccess")
                                        .getConstructor()
                                        .newInstance() as GeneratedDelegateAccess
                                val unchanged = effective == EffectiveHibernateModelBuilder.build(metadata, model)
                                val current =
                                    GeneratedDelegateFixture(suffix, loader, factory, bindings, access, unchanged)
                                fixture = current
                                runBlocking {
                                    test(current)
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun persistencePolicy(
            suffix: String,
            extended: Boolean,
        ): String =
            """
            types:
              DelegatePerson$suffix:
                unique: [[username]]
                fields:
                  reports:
                    relationship:
                      inverseField: manager
            """.trimIndent() +
                if (extended) {
                    "\n" +
                        """
                        DelegateDocument$suffix:
                          fields:
                            children:
                              relationship:
                                inverseField: parent
                        """.trimIndent().prependIndent("  ")
                } else {
                    ""
                }

        private fun withDelegates(
            model: dev.viaduct.persistence.model.PersistenceModel,
            schema: File,
            generated: File,
            suffix: String,
            test: (ClassLoader, GrtBindings) -> Unit,
        ) {
            val sources = generated.resolve("delegates")
            GrtDelegateGenerator().write(model, PACKAGE, listOf(schema), sources)
            sources.resolve("FixtureAccess.kt").writeText(
                accessSource(
                    suffix,
                    model.entities.any {
                        it.graphqlName ==
                            "DelegateRecord$suffix"
                    },
                ),
            )
            ApprovalRequestFixture.withGrts(schema, generated) { grts ->
                val classes = generated.resolve("delegate-classes")
                compile(sources, classes, generated.resolve("grts"))
                URLClassLoader(arrayOf(classes.toURI().toURL()), grts).use { loader ->
                    val bindings =
                        loader.loadClass("$PACKAGE.persistence.PersistenceDelegates").let {
                            it.getMethod("getBindings").invoke(it.getField("INSTANCE").get(null)) as GrtBindings
                        }
                    test(loader, bindings)
                }
            }
        }

        private fun withMappedTables(
            metadata: org.hibernate.boot.Metadata,
            effective: dev.viaduct.persistence.hibernate.EffectiveHibernateModel,
            database: java.sql.Connection,
            inspect: (String) -> Unit,
            test: (SessionFactory) -> Unit,
        ) {
            metadata.sessionFactoryBuilder
                .applyStatementInspector {
                    check("graphql.resolve" !in it.lowercase()) { "Native SQL only" }
                    inspect(it)
                    it
                }.build()
                .use { factory ->
                    try {
                        factory.schemaManager.exportMappedObjects(false)
                        JdbcOperations.execute(database, PostgresqlOverlay.renderMigration(effective))
                        test(factory)
                    } finally {
                        factory.schemaManager.dropMappedObjects(false)
                    }
                }
        }

        private fun withRuntimeMetadata(
            loader: ClassLoader,
            generated: File,
            settings: Map<String, String>,
            bindings: GrtBindings,
            test: (org.hibernate.boot.Metadata) -> Unit,
        ) {
            val bootstrap = BootstrapServiceRegistryBuilder().applyClassLoader(loader).build()
            val registry =
                StandardServiceRegistryBuilder(bootstrap)
                    .applySettings(settings)
                    .applySetting("hibernate.classLoaders", listOf(loader))
                    .build()
            try {
                val metadata =
                    MetadataSources(registry)
                        .addFile(generated.resolve("resources/META-INF/viaduct-persistence.hbm.xml"))
                        .metadataBuilder
                        .applyTempClassLoader(loader)
                        .applyImplicitNamingStrategy(ViaductImplicitNamingStrategy())
                        .applyPhysicalNamingStrategy(ViaductPhysicalNamingStrategy())
                        .build()
                bindings.configure(metadata)
                test(metadata)
            } finally {
                StandardServiceRegistryBuilder.destroy(registry)
            }
        }

        private fun compile(
            sources: File,
            classes: File,
            grts: File,
        ) {
            val messages = ByteArrayOutputStream()
            val classpath = System.getProperty("java.class.path") + File.pathSeparator + grts.path
            val arguments =
                listOf("-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath, "-d", classes.path) +
                    sources
                        .walkTopDown()
                        .filter { it.extension == "kt" }
                        .map { it.path }
                        .toList()
            val result = PrintStream(messages).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
            check(result.code == 0) { "Generated delegates did not compile:\n$messages" }
        }

        @Suppress("LongMethod") // Generated test adapter keeps its complete class template together.
        private fun accessSource(suffix: String, extended: Boolean): String {
            val types =
                (
                    listOf("Person", "Group", "Membership", "Item", "Scalar") +
                        if (extended) listOf("Record") else emptyList()
                ).map {
                    "Delegate$it$suffix"
                }
            return """@file:OptIn(viaduct.apiannotations.InternalApi::class)
@file:Suppress("UNCHECKED_CAST")
package $PACKAGE.persistence
import $PACKAGE.*
import dev.viaduct.persistence.postgresql.delegate.GeneratedDelegateAccess
import dev.viaduct.persistence.orm.grt.DelegateHibernateClient
import org.hibernate.Session
import viaduct.api.context.*
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.types.*
import viaduct.api.types.Query
import viaduct.api.select.SelectionSet
class FixtureAccess : GeneratedDelegateAccess {
  override suspend fun batch(client: DelegateHibernateClient,
    contexts: List<SelectiveNodeExecutionContext<NodeObject>>,
  ): Map<SelectiveNodeExecutionContext<NodeObject>, viaduct.api.FieldValue<ObjectBase>> =
    client.fetchNodes(contexts as List<SelectiveNodeExecutionContext<DelegatePerson$suffix>>) as
      Map<SelectiveNodeExecutionContext<NodeObject>, viaduct.api.FieldValue<ObjectBase>>
  override fun insert(client: DelegateHibernateClient, context: ResolverExecutionContext<out Query>, session: Session, value: ObjectBase): ObjectBase = when(value) {
    ${types.joinToString("\n") { "is $it -> client.insert(context, session, value)" }}
    ${if (extended) "is DelegateDocument$suffix -> client.insert(context, session, value)" else ""}
    else -> error("Wrong type")
  }
  override fun update(client: DelegateHibernateClient, context: ResolverExecutionContext<out Query>, session: Session, value: ObjectBase): ObjectBase = when(value) {
    ${types.joinToString("\n") { "is $it -> client.update(context, session, value)" }}
    else -> error("Wrong type")
  }
  override fun find(client: DelegateHibernateClient, context: ResolverExecutionContext<out Query>, session: Session, id: GlobalID<NodeObject>): ObjectBase = when(id.type.name) {
    ${types.joinToString("\n") { "\"$it\" -> client.find(context, session, id as GlobalID<$it>)" }}
    else -> error("Wrong type")
  }
  override fun delete(client: DelegateHibernateClient, context: ResolverExecutionContext<out Query>, session: Session, id: GlobalID<NodeObject>) = when(id.type.name) {
    ${types.joinToString("\n") { "\"$it\" -> client.delete(context, session, id as GlobalID<$it>)" }}
    else -> error("Wrong type")
  }
  override suspend fun fetch(client: DelegateHibernateClient, context: SelectiveNodeExecutionContext<NodeObject>): ObjectBase = when(context.id.type.name) {
    ${types.joinToString("\n") { "\"$it\" -> client.fetchNode(context as SelectiveNodeExecutionContext<$it>)" }}
    else -> error("Wrong type")
  }
  override suspend fun connection(
    client: DelegateHibernateClient, context: ConnectionFieldExecutionContext<*, *, *, Connection<*, *>>,
    selections: SelectionSet<Connection<*, *>>, prePaged: Boolean, warmProxy: Boolean, identifiersOnly: Boolean,
  ): ObjectBase = client.fetchConnection(
    context as ConnectionFieldExecutionContext<*, *, *, DelegatePeople$suffix>,
    selections as SelectionSet<DelegatePeople$suffix>,
  ) { session ->
    if (warmProxy) {
      val id = session.createSelectionQuery(
        "select p.internalId from DelegatePerson$suffix p order by p.username, p.internalId",
        java.util.UUID::class.java,
      ).setMaxResults(1).singleResult
      session.getReference("DelegatePerson$suffix", id)
    }
    (if (identifiersOnly) session.createSelectionQuery(
      "select p.internalId from DelegatePerson$suffix p order by p.username, p.internalId", java.util.UUID::class.java,
    ) else session.createSelectionQuery("from DelegatePerson$suffix p order by p.username, p.internalId", DelegatePerson${suffix}Entity::class.java))
      .also { if (prePaged) it.setMaxResults(1) }
  }
}

""".trimStart()
        }

        private fun extendedModelLoader(suffix: String): (File, File?) -> PersistenceModel =
            { directory, policy ->
                val registry =
                    graphql.schema.idl
                        .SchemaParser()
                        .parse(directory.resolve("Model.graphqls"))
                viaduct.graphql.utils.DefaultSchemaFactory
                    .addDefaults(registry, allowExisting = true)
                val schema =
                    viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory
                        .fromTypeDefinitionRegistry(registry)
                val names =
                    dev.viaduct.persistence.model
                        .discoverPersistentTypeNames(listOf(directory.resolve("Model.graphqls")), schema)
                dev.viaduct.persistence.model.PersistenceModelBuilder().build(
                    schema,
                    names + "DelegateDocument$suffix",
                    dev.viaduct.persistence.gradle.PersistenceConfig
                        .load(policy),
                )
            }

        private fun extendedSchema(suffix: String): String =
            """

            union DelegateSubject$suffix = DelegatePerson$suffix | DelegateGroup$suffix
            union DelegateSingle$suffix = DelegatePerson$suffix
            interface DelegateActor$suffix { id: ID! }
            extend type DelegatePerson$suffix implements DelegateActor$suffix
            extend type DelegateGroup$suffix implements DelegateActor$suffix
            type DelegateDocument$suffix {
                id: ID!, label: String!, parent: DelegateDocument$suffix
                children: [DelegateDocument$suffix!]!
                value: String, context: String, binding: String, builder: String, current: String, pending: String, native_label: String
            }
            extend type Query { record: DelegateRecord$suffix, documents: DelegateDocuments$suffix }
            type DelegateDocumentEdge$suffix @edge { cursor: String!, node: DelegateDocument$suffix! }
            type DelegateDocuments$suffix @connection {
                edges: [DelegateDocumentEdge$suffix!]!, nodes: [DelegateDocument$suffix!]!, pageInfo: PageInfo!
            }
            type DelegateRecord$suffix implements Node {
                id: ID!, label: String!, subject: DelegateSubject$suffix!, actor: DelegateActor$suffix
                subjects: [DelegateSubject$suffix!]!, singles: [DelegateSingle$suffix!]!, actors: [DelegateActor$suffix!]!
                document: DelegateDocument$suffix
                links: DelegateLinks$suffix, moreLinks: DelegateLinks$suffix, mixedLinks: DelegateMixedLinks$suffix
            }
            type DelegateLinkEdge$suffix @edge {
                cursor: String!, node: DelegatePerson$suffix!, label: String!, reviewer: DelegatePerson$suffix
            }
            type DelegateLinks$suffix @connection {
                edges: [DelegateLinkEdge$suffix!]!, nodes: [DelegatePerson$suffix!]!, pageInfo: PageInfo!
            }
            type DelegateMixedEdge$suffix @edge { cursor: String!, node: DelegateSubject$suffix!, label: String }
            type DelegateMixedLinks$suffix @connection {
                edges: [DelegateMixedEdge$suffix!]!, nodes: [DelegateSubject$suffix!]!, pageInfo: PageInfo!
            }
            """.trimIndent()

        private fun schema(
            suffix: String,
            extended: Boolean = false,
        ): String =
            DelegateFixture
                .schema(suffix)
                .replace(
                    "manager: DelegatePerson$suffix",
                    "manager: DelegatePerson$suffix, managerId: ID @idOf(type: \"DelegatePerson$suffix\")",
                ).replace(
                    "name: String!, members:",
                    "name: String!, items: [DelegateItem$suffix!]!, members:",
                ) +
                """

                directive @edge on OBJECT
                directive @connection on OBJECT
                scalar JSON
                scalar BigDecimal
                scalar Date
                scalar Long
                scalar Time
                type DelegateScalar$suffix implements Node {
                  id: ID!
                  labels: [String!]!
                  notes: [String]
                  numbers: [Int!]!
                  flags: [Boolean]
                  statuses: [DelegateStatus$suffix]
                  happenedTimes: [DateTime]
                  rawId: ID
                  rawIds: [ID!]
                  metadata: JSON
                  documents: [JSON]
                  requiredDocuments: [JSON!]!
                  time: Time
                  times: [Time]
                  isActive: Boolean!
                  amount: BigDecimal
                  amounts: [BigDecimal]
                  dates: [Date!]
                  longs: [Long!]
                  ratios: [Float!]
                  happenedAt: DateTime
                  isLabel: String
                }
                type DelegateItem$suffix implements Node { id: ID!, title: String! }
                type DelegatePersonEdge$suffix @edge { cursor: String!, node: DelegatePerson$suffix! }
                type DelegatePeople$suffix @connection {
                  edges: [DelegatePersonEdge$suffix!]!, nodes: [DelegatePerson$suffix!]!, pageInfo: PageInfo!
                }
                type PageInfo { hasNextPage: Boolean!, hasPreviousPage: Boolean!, startCursor: String, endCursor: String }
                """.trimIndent() + if (extended) extendedSchema(suffix) else ""
    }
}

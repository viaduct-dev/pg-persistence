@file:OptIn(viaduct.apiannotations.ExperimentalApi::class, viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import dev.viaduct.persistence.jdbc.JdbcOperations
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbEntityMutations
import dev.viaduct.persistence.runtime.db.DbRead
import dev.viaduct.persistence.runtime.db.DbRoot
import dev.viaduct.persistence.runtime.db.DbTransactionScope
import dev.viaduct.persistence.runtime.db.PgGraphqlClient
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlMutationClient
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.pgGraphqlAssociation
import dev.viaduct.persistence.runtime.db.withReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.tenant.codegen.cli.SchemaObjectsBytecode
import viaduct.tenant.runtime.select.SelectionSetFactoryImpl
import java.io.File
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Reduced Gateloom request shapes, isolated from its application schema and data. */
internal class ApprovalRequestFixture(
    val suffix: String,
    private val loader: ClassLoader,
    private val http: HttpClient,
    val sdl: String,
) {
    val assignment = PgGraphqlEntity("ReviewAssignment$suffix")
    val assignmentId: String = UUID.randomUUID().toString()
    private val mutations = PgGraphqlMutationClient(http, "http://fixture/graphql")
    private val filter = PgGraphqlFilter.eq("uuidId", assignmentId)
    private val ownerType = type(assignment.typeName)
    val schema =
        createSchemaWithWiring(
            sdl.removePrefix("interface Node { id: ID! }").replace("type Query {", "extend type Query {"),
        )
    private val internalContext = MockInternalContext.create(schema, PACKAGE, loader)
    val context = internalContext.executionContext
    private val client = DbClient(http, "http://fixture/graphql")
    private val selectionFactory =
        SelectionSetFactoryImpl(EngineSelectionSetFactoryImpl(schema), internalContext.globalIDCodec)
    val requestSelection =
        """
        __typename ... on Node { id }
        ... on AccessRequest$suffix { requestedPermission }
        ... on ImportRequest$suffix { teamName }
        ... on OnboardingRequest$suffix { notificationEmail }
        """.trimIndent()
    val requestField: CompositeField<*, *> get() = field("request")

    fun field(name: String): CompositeField<*, *> =
        loader.loadClass("$PACKAGE.${assignment.typeName}\$Fields").let { fields ->
            fields
                .getMethod("get" + name.replaceFirstChar(Char::uppercaseChar))
                .invoke(fields.getField("INSTANCE").get(null)) as CompositeField<*, *>
        }

    fun selections(
        type: Type<CompositeOutput>,
        fields: String,
    ): SelectionSet<CompositeOutput> = selectionFactory.selectionsOn(type, fields, emptyMap())

    suspend fun transaction(block: DbTransactionScope.() -> Unit) = client.transaction(context, block)

    fun beginTransaction() = client.beginTransaction(context)

    suspend fun createRequest(
        kind: String,
        field: String,
        value: String,
    ): GlobalID<NodeObject> {
        val id = UUID.randomUUID().toString()
        val typeName = kind + suffix
        mutations.insert(PgGraphqlEntity(typeName), requestValues(id, field, value))
        return GlobalID(type(typeName), id)
    }

    fun requestValues(
        id: String,
        field: String,
        value: String,
    ): PgGraphqlObject = PgGraphqlObject.of("uuidId" to id, "summary" to "Review $value", field to value)

    suspend fun assign(target: GlobalID<*>) {
        insertAssignment(PgGraphqlObject.of("uuidId" to assignmentId).withReference(requestField, target))
    }

    suspend fun insertAssignment(values: PgGraphqlObject) {
        mutations.insert(assignment, values)
    }

    suspend fun change(
        target: GlobalID<*>?,
        fieldName: String = "request",
    ) {
        PgGraphqlClient(http, "http://fixture/graphql").updateRecords(
            assignment,
            PgGraphqlObject.of().withReference(field(fieldName), target),
            filter,
            atMost = 1,
            selection = "uuidId",
            recordDeserializer = JsonObject.serializer(),
        )
    }

    suspend fun readRequest(): ObjectBase {
        val owner = readOwner("request { $requestSelection }")
        return owner.get("request", requestField.type.kcls)
    }

    suspend fun readOwner(
        fields: String,
        id: String = assignmentId,
    ): ObjectBase =
        client.fetch(
            context,
            DbRead(
                DbRoot(
                    assignment.collectionField,
                    arguments = """(filter: {uuidId: {eq: "$id"}})""",
                    singleViaFilteredCollection = true,
                ),
            ),
            selections(ownerType, fields),
        ) as ObjectBase

    suspend fun readRoot(
        target: GlobalID<*>,
        declared: String,
        fields: String,
    ): ObjectBase =
        client.fetch(
            context,
            DbRead(
                DbRoot(
                    PgGraphqlEntity(target.type.name).collectionField,
                    arguments = """(filter: {uuidId: {eq: "${target.internalID}"}})""",
                    singleViaFilteredCollection = true,
                ),
                target.type,
            ),
            selections(reflection(declared + suffix), fields),
        ) as ObjectBase

    suspend fun addTo(
        fieldName: String,
        target: GlobalID<*>,
        values: PgGraphqlObject = PgGraphqlObject.of(),
    ) {
        val association = field(fieldName).pgGraphqlAssociation()
        val input = association.insertObject(GlobalID(ownerType, assignmentId), target, values)
        mutations.insert(association.entity, input)
    }

    suspend fun delete(
        entity: PgGraphqlEntity,
        id: String,
    ) = PgGraphqlClient(http, "http://fixture/graphql").delete(entity, PgGraphqlFilter.eq("uuidId", id), atMost = 1)

    suspend fun ids(entity: PgGraphqlEntity): Set<String> =
        PgGraphqlClient(http, "http://fixture/graphql")
            .select(entity, "uuidId")
            .map {
                it.jsonObject
                    .getValue("uuidId")
                    .jsonPrimitive.content
            }.toSet()

    @Suppress("UNCHECKED_CAST")
    fun reflection(name: String): Type<CompositeOutput> =
        loader.loadClass("$PACKAGE.$name\$Reflection").getField("INSTANCE").get(null) as Type<CompositeOutput>

    @Suppress("UNCHECKED_CAST")
    fun entity(kind: String): DbEntityMutations<NodeObject> =
        DbEntityMutations::class.java
            .getConstructor(DbClient::class.java, Type::class.java)
            .newInstance(client, type(kind + suffix)) as DbEntityMutations<NodeObject>

    fun mutationContext(payload: String): MutationFieldExecutionContext<*, *, *, CompositeOutput> =
        typedMutationContext(reflection(payload + suffix).kcls.java, internalContext)

    @Suppress("UNCHECKED_CAST")
    fun type(name: String): Type<NodeObject> =
        loader.loadClass("$PACKAGE.$name\$Reflection").getField("INSTANCE").get(null) as Type<NodeObject>

    companion object {
        private const val PACKAGE = "dev.viaduct.persistence.approvalfixture"

        fun withFixture(test: (ApprovalRequestFixture) -> Unit) {
            val url = System.getenv("PG_INTEGRATION_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:54322/postgres"
            require(url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:")) {
                "Approval request integration tests require local PostgreSQL"
            }
            val user = System.getenv("PG_INTEGRATION_USER") ?: "postgres"
            val password = System.getenv("PG_INTEGRATION_PASSWORD") ?: "postgres"
            val suffix =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
            val sdl = schema(suffix)
            // Deliberately fail, rather than skip, when the local database is unavailable.
            DriverManager.getConnection(url, user, password).use { database ->
                val settings =
                    mapOf(
                        "hibernate.connection.url" to url,
                        "hibernate.connection.username" to user,
                        "hibernate.connection.password" to password,
                        "hibernate.connection.driver_class" to "org.postgresql.Driver",
                        "hibernate.boot.allow_jdbc_metadata_access" to "true",
                    )
                withGeneratedDatabase(database, sdl, settings) { _, schemaFile, generated ->
                    withGrts(schemaFile, generated) { loader ->
                        database.graphqlClient().use { http ->
                            test(ApprovalRequestFixture(suffix, loader, http, sdl))
                        }
                    }
                }
            }
        }

        internal fun withGrts(
            schema: File,
            generated: File,
            test: (ClassLoader) -> Unit,
        ) {
            val classes = generated.resolve("grts")
            SchemaObjectsBytecode().main(
                listOf(
                    "--schema_files",
                    schema.path,
                    "--generated_directory",
                    classes.path,
                    "--pkg_for_generated_classes",
                    PACKAGE,
                    "--include_ineligible_for_testing_only",
                ),
            )
            val urls = arrayOf(classes.toURI().toURL(), generated.resolve("resources").toURI().toURL())
            URLClassLoader(urls, ApprovalRequestFixture::class.java.classLoader).use(test)
        }

        internal fun schema(suffix: String) =
            """
            interface Node { id: ID! }
            interface Summarized$suffix { summary: String! }
            interface Reviewable$suffix implements Summarized$suffix { summary: String! }
            type AccessRequest$suffix implements Node & Summarized$suffix & Reviewable$suffix {
              id: ID!, summary: String!, requestedPermission: String!
            }
            type ImportRequest$suffix implements Node & Summarized$suffix & Reviewable$suffix {
              id: ID!, summary: String!, teamName: String!
            }
            type OnboardingRequest$suffix implements Node & Summarized$suffix & Reviewable$suffix {
              id: ID!, summary: String!, notificationEmail: String!
            }
            union ApprovalRequest$suffix = AccessRequest$suffix | ImportRequest$suffix | OnboardingRequest$suffix
            type ApprovalEdge$suffix { cursor: String!, node: ApprovalRequest$suffix!, label: String }
            type ApprovalConnection$suffix { edges: [ApprovalEdge$suffix!]!, nodes: [ApprovalRequest$suffix!]! }
            type ReviewEdge$suffix { cursor: String!, node: Reviewable$suffix!, label: String }
            type ReviewConnection$suffix { edges: [ReviewEdge$suffix!]!, nodes: [Reviewable$suffix!]! }
            type ReviewAssignment$suffix implements Node {
              id: ID!, request: ApprovalRequest$suffix!, optionalRequest: ApprovalRequest$suffix
              reviewable: Reviewable$suffix
              requests: [ApprovalRequest$suffix!]!
              reviewables: [Reviewable$suffix!]!
              queue(first: Int, after: String, last: Int, before: String): ApprovalConnection$suffix!
              reviewQueue(first: Int, after: String, last: Int, before: String): ReviewConnection$suffix!
            }
            type Query { review(id: ID!): ReviewAssignment$suffix, request: ApprovalRequest$suffix, healthy: String! }
            type SavePayload$suffix { request: ApprovalRequest$suffix }
            type InterfacePayload$suffix { request: Reviewable$suffix }
            type BatchPayload$suffix { requests: [ApprovalRequest$suffix!]! }
            type BatchInterfacePayload$suffix { requests: [Reviewable$suffix!]! }
            union BatchResult$suffix = BatchPayload$suffix
            type SaveAlternative$suffix { request: ApprovalRequest$suffix }
            union AmbiguousResult$suffix = SavePayload$suffix | SaveAlternative$suffix
            union SaveResult$suffix = SavePayload$suffix
            """.trimIndent()
    }
}

/** Only the HTTP hop is replaced: every document and variable is executed by real pg_graphql. */
internal fun Connection.graphqlClient(): HttpClient =
    HttpClient(
        MockEngine { request ->
            val bytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
            val body = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val response =
                JdbcOperations.query(
                    this@graphqlClient,
                    "SELECT graphql.resolve(?::text, ?::jsonb)",
                    { rows ->
                        check(rows.next())
                        rows.getString(1)
                    },
                    body.getValue("query").jsonPrimitive.content,
                    body.getValue("variables").toString(),
                )
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

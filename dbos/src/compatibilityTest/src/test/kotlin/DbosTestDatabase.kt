import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID

/** Only uniquely named schemas in the dedicated, local DBOS test database are changed. */
class DbosTestDatabase : AutoCloseable {
    val source = PGSimpleDataSource()
    val schema = "dbos_graphql_" + UUID.randomUUID().toString().replace("-", "")

    init {
        val url = System.getenv("PG_DBOS_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:55322/kan22_dbos_tests"
        require(url.matches(Regex("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan22_dbos_tests")))
        source.setURL(url)
        source.user = "postgres"
        source.password = System.getenv("PG_DBOS_PASSWORD") ?: "postgres"
        sql("CREATE SCHEMA $schema")
        source.currentSchema = "$schema,public"
        sql(
            """
            CREATE TABLE member (uuid_id uuid PRIMARY KEY, name text NOT NULL);
            COMMENT ON TABLE member IS '@graphql({"name":"DbosMember"})';
            COMMENT ON COLUMN member.uuid_id IS '@graphql({"name":"uuidId"})';
            """.trimIndent(),
        )
    }

    fun sql(
        sql: String,
        vararg values: String,
    ) {
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.execute()
            }
        }
    }

    fun scalar(
        sql: String,
        value: String,
    ): String? =
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    fun name(id: String): String? = scalar("SELECT name FROM member WHERE uuid_id = ?::uuid", id)

    override fun close() = sql("DROP SCHEMA IF EXISTS $schema CASCADE")
}

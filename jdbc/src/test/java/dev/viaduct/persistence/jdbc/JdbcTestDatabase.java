package dev.viaduct.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Uses only a uniquely named schema in the isolated local pg_graphql test database. */
final class JdbcTestDatabase implements AutoCloseable {
    private final PGSimpleDataSource source = new PGSimpleDataSource();
    private final String schema = "jdbc_transport_" + UUID.randomUUID().toString().replace("-", "");

    JdbcTestDatabase() throws SQLException {
        String url = System.getenv().getOrDefault(
                "PG_JDBC_TEST_URL", "jdbc:postgresql://127.0.0.1:55322/kan23_jdbc_tests");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan23_jdbc_tests")) {
            throw new IllegalArgumentException("Use only the isolated local kan23_jdbc_tests database");
        }
        source.setURL(url);
        source.setUser("postgres");
        source.setPassword(System.getenv().getOrDefault("PG_JDBC_TEST_PASSWORD", "postgres"));
        execute("CREATE SCHEMA " + schema);
        source.setCurrentSchema(schema + ",public");
        execute("""
                CREATE TABLE member (uuid_id uuid PRIMARY KEY, name text NOT NULL);
                COMMENT ON TABLE member IS '@graphql({"name":"JdbcMember"})';
                COMMENT ON COLUMN member.uuid_id IS '@graphql({"name":"uuidId"})';
                """);
    }

    DataSource dataSource() {
        return source;
    }

    <T> T withConnection(GraphqlJdbcConnection.Work<T> work) throws SQLException {
        try (var connection = source.getConnection()) {
            return work.execute(connection);
        }
    }

    String name(String id) throws SQLException {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("SELECT name FROM member WHERE uuid_id = ?::uuid")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    static void setLabel(Connection connection, String label) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('application_name', ?, true)")) {
            statement.setString(1, label);
            statement.execute();
        }
    }

    private void execute(String sql) throws SQLException {
        withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
                return null;
            }
        });
    }

    @Override
    public void close() throws SQLException {
        execute("DROP SCHEMA " + schema + " CASCADE");
    }
}

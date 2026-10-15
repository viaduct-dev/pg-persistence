package dev.viaduct.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** KAN-37 feasibility fixture; never uses an application database or changes existing roles. */
final class CrossSchemaTestDatabase implements AutoCloseable {
    static final String PEOPLE = "kan37_people";
    static final String ORDERS = "kan37_orders";
    static final String READER = "kan37_reader";
    static final String LIMITED = "kan37_limited";
    private final PGSimpleDataSource source = new PGSimpleDataSource();

    CrossSchemaTestDatabase() throws SQLException {
        String url = System.getenv().getOrDefault(
                "PG_JDBC_TEST_URL", "jdbc:postgresql://127.0.0.1:55322/kan23_jdbc_tests");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/kan23_jdbc_tests")) {
            throw new IllegalArgumentException("Use only the isolated local kan23_jdbc_tests database");
        }
        source.setURL(url);
        source.setUser(System.getenv().getOrDefault("PG_JDBC_TEST_ADMIN_USER", "supabase_admin"));
        source.setPassword(System.getenv().getOrDefault("PG_JDBC_TEST_PASSWORD", "postgres"));
        withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    // No IF NOT EXISTS: an existing fixture must fail rather than overwrite other data.
                    statement.execute("""
                            CREATE SCHEMA kan37_people;
                            CREATE SCHEMA kan37_orders;
                            CREATE ROLE kan37_reader NOLOGIN;
                            CREATE ROLE kan37_limited NOLOGIN;
                            GRANT kan37_reader, kan37_limited TO postgres;
                            CREATE FUNCTION public.kan37_request_setup() RETURNS void LANGUAGE plpgsql AS $$
                            BEGIN
                              PERFORM set_config('search_path', 'kan37_people,kan37_orders', true);
                            END;
                            $$;
                            REVOKE ALL ON FUNCTION public.kan37_request_setup() FROM PUBLIC;
                            GRANT EXECUTE ON FUNCTION public.kan37_request_setup() TO kan37_reader;
                            CREATE FUNCTION public.kan37_graphql(
                              query text, variables jsonb DEFAULT '{}'::jsonb, "operationName" text DEFAULT NULL
                            ) RETURNS jsonb LANGUAGE sql AS $$
                              SELECT graphql.resolve(query, variables, "operationName");
                            $$;
                            REVOKE ALL ON FUNCTION public.kan37_graphql(text,jsonb,text) FROM PUBLIC;
                            GRANT EXECUTE ON FUNCTION public.kan37_graphql(text,jsonb,text) TO kan37_reader;
                            GRANT USAGE ON SCHEMA public TO kan37_reader;
                            COMMENT ON SCHEMA kan37_people IS '@graphql({"inflect_names":true})';
                            COMMENT ON SCHEMA kan37_orders IS '@graphql({"inflect_names":true})';
                            CREATE TABLE kan37_people.account (id integer PRIMARY KEY, name text NOT NULL);
                            CREATE TABLE kan37_orders.account (id integer PRIMARY KEY, name text NOT NULL);
                            CREATE TABLE kan37_orders.purchase (
                              id integer PRIMARY KEY,
                              buyer_id integer NOT NULL,
                              label text NOT NULL,
                              CONSTRAINT purchase_buyer FOREIGN KEY (buyer_id) REFERENCES kan37_people.account(id)
                            );
                            CREATE TABLE kan37_orders.purchase_reviewer (
                              id integer PRIMARY KEY,
                              purchase_id integer NOT NULL,
                              reviewer_id integer NOT NULL,
                              CONSTRAINT reviewer_purchase FOREIGN KEY (purchase_id) REFERENCES kan37_orders.purchase(id),
                              CONSTRAINT reviewer_account FOREIGN KEY (reviewer_id) REFERENCES kan37_people.account(id)
                            );
                            COMMENT ON TABLE kan37_people.account IS '@graphql({"name":"Kan37Account"})';
                            COMMENT ON TABLE kan37_orders.account IS '@graphql({"name":"Kan37Seller"})';
                            COMMENT ON TABLE kan37_orders.purchase IS '@graphql({"name":"Kan37Purchase"})';
                            COMMENT ON TABLE kan37_orders.purchase_reviewer IS '@graphql({"name":"Kan37Review"})';
                            COMMENT ON CONSTRAINT purchase_buyer ON kan37_orders.purchase
                              IS '@graphql({"foreign_name":"buyer","local_name":"purchases"})';
                            COMMENT ON CONSTRAINT reviewer_purchase ON kan37_orders.purchase_reviewer
                              IS '@graphql({"foreign_name":"purchase","local_name":"reviews"})';
                            COMMENT ON CONSTRAINT reviewer_account ON kan37_orders.purchase_reviewer
                              IS '@graphql({"foreign_name":"reviewer","local_name":"reviews"})';
                            INSERT INTO kan37_people.account VALUES (1, 'Buyer'), (2, 'Reviewer');
                            INSERT INTO kan37_orders.account VALUES (1, 'Seller');
                            INSERT INTO kan37_orders.purchase VALUES (1, 1, 'First purchase');
                            INSERT INTO kan37_orders.purchase_reviewer VALUES (1, 1, 2);
                            GRANT USAGE ON SCHEMA graphql, kan37_people, kan37_orders TO kan37_reader;
                            GRANT USAGE ON SCHEMA graphql, kan37_people TO kan37_limited;
                            GRANT EXECUTE ON FUNCTION graphql.resolve(text,jsonb,text,jsonb)
                              TO kan37_reader, kan37_limited;
                            GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA kan37_people, kan37_orders
                              TO kan37_reader;
                            GRANT SELECT ON ALL TABLES IN SCHEMA kan37_people TO kan37_limited;
                            NOTIFY pgrst, 'reload schema';
                            """);
                }
                connection.commit();
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            return null;
        });
    }

    DataSource dataSource() {
        return source;
    }

    <T> T withConnection(GraphqlJdbcConnection.Work<T> work) throws SQLException {
        try (var connection = source.getConnection()) {
            return work.execute(connection);
        }
    }

    static void role(Connection connection, String role) throws SQLException {
        if (!role.equals(READER) && !role.equals(LIMITED)) {
            throw new IllegalArgumentException("Unknown fixture role");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + role);
        }
    }

    int rows(int id) throws SQLException {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT (SELECT count(*) FROM kan37_people.account WHERE id = ?)
                         + (SELECT count(*) FROM kan37_orders.purchase WHERE id = ?)
                    """)) {
                statement.setInt(1, id);
                statement.setInt(2, id);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    static int backend(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            if (!rows.next()) throw new SQLException("Missing PostgreSQL backend identity");
            return rows.getInt(1);
        }
    }

    static String state(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT current_user || ':' || current_setting('search_path')")) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Override
    public void close() throws SQLException {
        withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        DROP SCHEMA kan37_orders CASCADE;
                        DROP SCHEMA kan37_people CASCADE;
                        DROP FUNCTION public.kan37_request_setup();
                        DROP FUNCTION public.kan37_graphql(text,jsonb,text);
                        REVOKE USAGE ON SCHEMA public FROM kan37_reader;
                        REVOKE USAGE ON SCHEMA graphql FROM kan37_reader, kan37_limited;
                        REVOKE EXECUTE ON FUNCTION graphql.resolve(text,jsonb,text,jsonb)
                          FROM kan37_reader, kan37_limited;
                        DROP ROLE kan37_reader;
                        DROP ROLE kan37_limited;
                        """);
            }
            return null;
        });
    }
}

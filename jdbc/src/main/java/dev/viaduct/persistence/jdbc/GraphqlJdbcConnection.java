package dev.viaduct.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Predicate;
import javax.sql.DataSource;

/** Resource ownership and parameter binding, separate from GraphQL response handling. */
final class GraphqlJdbcConnection {
    private GraphqlJdbcConnection() {}

    static String resolve(Connection connection, String document, String variables, String operationName, int queryTimeoutSeconds)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT graphql.resolve(?, ?::jsonb, ?)")) {
            int previousTimeout = statement.getQueryTimeout();
            statement.setQueryTimeout(previousTimeout > 0 ? Math.min(previousTimeout, queryTimeoutSeconds) : queryTimeoutSeconds);
            statement.setString(1, document);
            statement.setString(2, variables);
            statement.setString(3, operationName);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("graphql.resolve returned no response");
                String response = rows.getString(1);
                if (response == null) throw new SQLException("graphql.resolve returned SQL NULL");
                return response;
            }
        }
    }

    static <T> T owned(DataSource source, Work<T> work, Predicate<T> successful, int networkTimeoutMillis) throws SQLException {
        try (var connection = source.getConnection(); var timeout = new NetworkTimeout(connection, networkTimeoutMillis)) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                if (successful.test(result)) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    static <T> T borrowed(Connection connection, Work<T> work, int networkTimeoutMillis) throws SQLException {
        try (var timeout = new NetworkTimeout(connection, networkTimeoutMillis)) {
            return work.execute(connection);
        }
    }

    private static final class NetworkTimeout implements AutoCloseable {
        private final Connection connection;
        private final int previous;

        NetworkTimeout(Connection connection, int millis) throws SQLException {
            this.connection = connection;
            previous = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, previous > 0 ? Math.min(previous, millis) : millis);
        }

        @Override
        public void close() throws SQLException {
            if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, previous);
        }
    }

    interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }
}

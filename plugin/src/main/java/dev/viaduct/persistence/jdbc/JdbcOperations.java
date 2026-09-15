package dev.viaduct.persistence.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Executes scoped JDBC statements without taking ownership of the caller's connection. */
public final class JdbcOperations {
    private JdbcOperations() {}

    public static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        }
    }

    public static <T> T query(
            Connection connection, String sql, ResultReader<T> reader, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                return reader.read(rows);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    @FunctionalInterface
    public interface ResultReader<T> {
        T read(ResultSet rows) throws SQLException;
    }
}

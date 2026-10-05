package dev.viaduct.persistence.dbos;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.json.SerializationUtil;
import dev.dbos.transact.jdbi.JdbiStepFactory;
import dev.dbos.transact.workflow.internal.StepResult;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.spi.JdbiPlugin;

/** Uses DBOS's transaction and result APIs; never reads or edits DBOS tables directly. */
final class DbosStepFactory extends JdbiStepFactory {
    private final long retryTimeoutNanos;

    DbosStepFactory(DBOS dbos, DataSource source, long retryTimeoutNanos) {
        super(dbos, ownedConnections(source));
        this.retryTimeoutNanos = retryTimeoutNanos;
    }

    private static Jdbi ownedConnections(DataSource source) {
        return Jdbi.create(source).installPlugin(new JdbiPlugin() {
            @Override
            public Connection customizeConnection(Connection connection) throws SQLException {
                // Pools may disable autocommit. Start from a clean connection so JDBI
                // owns the transaction instead of silently joining an existing one.
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                return connection;
            }
        });
    }

    @SuppressWarnings("unchecked")
    <T> T validateResult(T value) {
        var encoded = SerializationUtil.serializeValue(value, null, serializer);
        var restored = SerializationUtil.deserializeValue(encoded.serializedValue(), encoded.serialization(), serializer);
        if (restored == null || !value.getClass().equals(restored.getClass())) {
            throw new IllegalArgumentException("DBOS serializer must restore the transaction result's type");
        }
        return (T) restored;
    }

    @Override
    protected <R, X extends Exception> R runTxStep(TxStepFunction<R, X> execute, String name) throws X {
        var retry = new TransactionRetry(retryTimeoutNanos);
        return super.runTxStep((workflowId, stepId) -> {
            while (true) {
                retry.check();
                try {
                    return execute.execute(workflowId, stepId);
                } catch (Exception failure) {
                    // Cleanup can fail after commit, or another worker can commit this step
                    // while this attempt fails on a business constraint. Neither requires SQLSTATE 08.
                    var saved = findSavedResult(workflowId, stepId, name, failure);
                    // Restoration may throw the recorded error. It is not a lookup failure.
                    if (saved.isPresent()) return saved.get().<R, X>toResult(serializer);
                    if (!retry.after(failure)) throw failure;
                }
            }
        }, name);
    }

    @Override
    protected void recordError(String workflowId, int stepId, Exception original) {
        try {
            super.recordError(workflowId, stepId, original);
        } catch (Exception recordingFailure) {
            if (recordingFailure != original) original.addSuppressed(recordingFailure);
        }
    }

    private Optional<StepResult> findSavedResult(String workflowId, int stepId, String name, Exception original) {
        try {
            return checkExecution(workflowId, stepId, name);
        } catch (Exception lookupFailure) {
            if (lookupFailure != original) original.addSuppressed(lookupFailure);
            return Optional.empty();
        }
    }
}

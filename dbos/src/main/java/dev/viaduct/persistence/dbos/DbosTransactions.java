package dev.viaduct.persistence.dbos;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.txstep.IsolationLevel;
import dev.dbos.transact.txstep.StepFactoryOptions;
import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor;
import dev.viaduct.persistence.jdbc.JdbcRequestSetup;
import dev.viaduct.persistence.runtime.db.DbResult;
import dev.viaduct.persistence.runtime.db.DbTransactionCommit;
import dev.viaduct.persistence.runtime.db.DbTransactionResult;
import dev.viaduct.persistence.runtime.db.DbTransactionScope;
import dev.viaduct.persistence.runtime.db.DbTransactions;
import dev.viaduct.persistence.runtime.db.DbTransactionsKt;
import dev.viaduct.persistence.runtime.db.UpstreamGraphqlException;
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutorKt;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Selects immediate JDBC execution for DbClient.transaction inside a registered DBOS workflow. */
public final class DbosTransactions implements DbTransactions {
    private static final Duration DEFAULT_RETRY_TIMEOUT = Duration.ofSeconds(30);
    private final Function<Function<Connection, StoredCommit>, StoredCommit> transaction;
    private final Function<Connection, JdbcPgGraphqlExecutor> executor;

    public DbosTransactions(DBOS dbos, DataSource source) {
        this(dbos, source, new StepFactoryOptions("pg-persistence-transaction"));
    }

    public DbosTransactions(DBOS dbos, DataSource source, StepFactoryOptions options) {
        this(dbos, source, options, DEFAULT_RETRY_TIMEOUT);
    }

    /** Bounds starting transaction attempts, not running JDBC calls; the default is 30 seconds. */
    public DbosTransactions(DBOS dbos, DataSource source, Duration retryTimeout) {
        this(dbos, source, new StepFactoryOptions("pg-persistence-transaction"), retryTimeout);
    }

    public DbosTransactions(DBOS dbos, DataSource source, StepFactoryOptions options, Duration retryTimeout) {
        this(new DbosStepFactory(dbos, source, timeoutNanos(retryTimeout)), options,
                (Function<Connection, JdbcPgGraphqlExecutor>) JdbcPgGraphqlExecutor::new);
    }

    public DbosTransactions(DBOS dbos, DataSource source, StepFactoryOptions options, JdbcRequestSetup setup) {
        this(dbos, source, options, setup, DEFAULT_RETRY_TIMEOUT);
    }

    public DbosTransactions(DBOS dbos, DataSource source, StepFactoryOptions options,
            JdbcRequestSetup setup, Duration retryTimeout) {
        this(new DbosStepFactory(dbos, source, timeoutNanos(retryTimeout)), options,
                connection -> new JdbcPgGraphqlExecutor(connection, setup));
    }

    private static long timeoutNanos(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("retryTimeout must be positive");
        }
        return timeout.toNanos(); // Reject overflow before opening a database connection.
    }

    private DbosTransactions(
            DbosStepFactory factory, StepFactoryOptions options,
            Function<Connection, JdbcPgGraphqlExecutor> executor) {
        this.executor = executor;
        var isolation = options.isolationLevel();
        this.transaction = work -> factory.inStep(handle -> {
            // Set isolation before any SQL, inside the scope that owns rollback and close.
            // The connection is returned to its pool, not reused by this adapter.
            if (isolation != null && isolation != IsolationLevel.DEFAULT) {
                handle.setTransactionIsolationLevel(isolation.jdbcValue());
            }
            var completed = work.apply(handle.getConnection());
            // Validate the decoded operation results too, before the database commits.
            factory.validateResult(completed).restore();
            return completed;
        }, options.name());
    }

    @Override
    public <T> DbTransactionCommit<T> execute(
            Map<String, String> headers, Function1<? super DbTransactionScope, ? extends T> block) {
        if (DBOS.workflowId() == null || DBOS.stepId() != null) {
            throw new IllegalStateException("DBOS transactions require a workflow, outside any existing step");
        }
        var requestHeaders = Map.copyOf(headers);
        StoredCommit stored = transaction.apply(connection -> {
            var jdbc = executor.apply(connection);
            try {
                var result = DbTransactionsKt.executeImmediateTransaction(
                        request -> jdbc.executeBlocking(request, requestHeaders), block);
                return new StoredCommit(
                        result.getValue() == Unit.INSTANCE ? UnitValue.INSTANCE : result.getValue(),
                        result.getResult().encode());
            } catch (UpstreamGraphqlException failure) {
                // DBOS persists exceptions. Keep error details without non-serializable Kotlin causes.
                String response = PgGraphqlExecutorKt.encodePgGraphqlResponse(new DbResult<>(null, failure.getErrors()));
                throw new DbosGraphqlException(failure.getMessage(), response);
            }
        });
        return stored.restore();
    }

    private enum UnitValue { INSTANCE }

    /** DBOS serializes this before committing; the callback also validates restoration. */
    private record StoredCommit(Object value, String result) {
        @SuppressWarnings("unchecked")
        <T> DbTransactionCommit<T> restore() {
            T restoredValue = (T) (value == UnitValue.INSTANCE ? Unit.INSTANCE : value);
            return new DbTransactionCommit<>(restoredValue, DbTransactionResult.decode(result));
        }
    }
}

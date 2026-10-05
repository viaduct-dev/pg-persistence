package dev.viaduct.persistence.dbos;

import dev.dbos.transact.txstep.PostgresStepFactory;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** One retry policy for JDBC conflicts and the GraphQL errors that no longer carry SQLSTATE. */
final class TransactionRetry {
    private static final int GRAPHQL_ATTEMPTS = 3;
    private static final long MAX_DELAY_MILLIS = 2000;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private Throwable lastFailure;
    private int graphqlFailures;
    private long delayMillis = 1;

    TransactionRetry(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    void check() {
        if (System.nanoTime() - started >= timeoutNanos) {
            var timeout = new DbosTransactionTimeoutException();
            // DBOS examines causes for retryable SQLSTATE. Keep the last failure out of
            // the cause chain, or it could retry the timeout itself indefinitely.
            if (lastFailure != null) timeout.addSuppressed(lastFailure);
            throw timeout;
        }
    }

    boolean after(Exception failure) {
        if (failure instanceof DbosGraphqlException) {
            if (++graphqlFailures >= GRAPHQL_ATTEMPTS) return false;
        } else if (PostgresStepFactory.isSerializationFailure(failure)) {
            graphqlFailures = 0;
        } else {
            return false;
        }
        lastFailure = failure;
        check();
        try {
            Thread.sleep(Duration.ofMillis(nextDelayMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var cancelled = new IllegalStateException("Interrupted during transaction retry", interrupted);
            cancelled.addSuppressed(failure);
            throw cancelled;
        }
        check();
        return true;
    }

    long nextDelayMillis() {
        long delay = ThreadLocalRandom.current().nextLong(Math.max(1, delayMillis / 2), delayMillis + 1);
        delayMillis = Math.min(delayMillis * 2, MAX_DELAY_MILLIS);
        return delay;
    }
}

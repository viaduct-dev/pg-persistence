package dev.viaduct.persistence.dbos;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.txstep.PostgresStepFactory;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TransactionRetryTest {
    @Test
    void rejectsInvalidTimeoutBeforeOpeningConnection() {
        var dbos = mock(DBOS.class);
        var source = mock(DataSource.class);
        for (var timeout : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE) }) {
            assertThatThrownBy(() -> new DbosTransactions(dbos, source, timeout))
                    .isInstanceOfAny(IllegalArgumentException.class, ArithmeticException.class);
        }
        verifyNoInteractions(dbos, source);
    }

    @Test
    void retainsLastFailureWithoutMakingTimeoutRetryable() {
        var retry = new TransactionRetry(1);
        var conflict = new SQLException("Serialization conflict", "40001");
        var timeout = catchThrowable(() -> retry.after(conflict));
        assertThat(timeout).isInstanceOf(DbosTransactionTimeoutException.class).hasNoCause();
        assertThat(timeout.getSuppressed()).containsExactly(conflict);
        assertThat(PostgresStepFactory.isSerializationFailure((Exception) timeout)).isFalse();
    }

    @Test
    void acceptsAttemptWithinDeadline() {
        var retry = new TransactionRetry(Duration.ofHours(1).toNanos());
        retry.check();
        assertThat(retry.after(new SQLException("Retryable", "40001"))).isTrue();
        retry.check();
    }

    @Test
    void delayIncreasesAndStopsAtTwoSeconds() {
        var retry = new TransactionRetry(Duration.ofHours(1).toNanos());
        for (long maximum : new long[] {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2000, 2000}) {
            assertThat(retry.nextDelayMillis()).isBetween(Math.max(1, maximum / 2), maximum);
        }
    }

    @Test
    void limitsConsecutiveGraphqlFailures() {
        var retry = new TransactionRetry(Duration.ofHours(1).toNanos());
        var failure = new DbosGraphqlException("Constraint violation", "{}");
        assertThat(new boolean[] {retry.after(failure), retry.after(failure), retry.after(failure)})
                .containsExactly(true, true, false);
    }

    @Test
    void doesNotRetryApplicationOrConnectionFailures() {
        var retry = new TransactionRetry(Duration.ofHours(1).toNanos());
        assertThat(retry.after(new IllegalArgumentException("Rejected"))).isFalse();
        assertThat(retry.after(new SQLException("Connection lost", "08006"))).isFalse();
    }

    @Test
    void interruptionStopsRetriesAndPreservesInterruptStatus() {
        var retry = new TransactionRetry(Duration.ofHours(1).toNanos());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> retry.after(new SQLException("Conflict", "40001")))
                    .hasMessage("Interrupted during transaction retry")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}

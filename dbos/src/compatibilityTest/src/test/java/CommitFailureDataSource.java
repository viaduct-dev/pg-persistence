import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.ds.PGSimpleDataSource;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.anyInt;

/** Commits real PostgreSQL writes, then simulates losing the server acknowledgement once. */
public final class CommitFailureDataSource extends PGSimpleDataSource {
    private static final long serialVersionUID = 1L;
    private final AtomicBoolean loseCommitResponse;
    private final AtomicBoolean failBeforeCommit;
    private final Fault fault;
    public final AtomicInteger opened = new AtomicInteger();
    public final AtomicInteger closed = new AtomicInteger();
    public final AtomicBoolean autoCommit = new AtomicBoolean(true);

    public enum Phase { OPEN, BEGIN, RESET_AUTOCOMMIT, ISOLATION, BEFORE_COMMIT, AFTER_COMMIT, AFTER_ROLLBACK, AFTER_CLOSE }

    @FunctionalInterface
    public interface Fault {
        void inject(Phase phase) throws SQLException;
    }

    public CommitFailureDataSource(PGSimpleDataSource source, AtomicBoolean loseCommitResponse, AtomicBoolean failBeforeCommit) {
        this(source, loseCommitResponse, failBeforeCommit, phase -> {});
    }

    public CommitFailureDataSource(PGSimpleDataSource source, AtomicBoolean loseCommitResponse,
            AtomicBoolean failBeforeCommit, Fault fault) {
        setURL(source.getURL());
        setUser(source.getUser());
        setPassword(source.getPassword());
        this.loseCommitResponse = loseCommitResponse;
        this.failBeforeCommit = failBeforeCommit;
        this.fault = fault;
    }

    @Override
    public Connection getConnection() throws SQLException {
        fault.inject(Phase.OPEN);
        var borrowed = super.getConnection();
        borrowed.setAutoCommit(autoCommit.get());
        var connection = spy(borrowed);
        opened.incrementAndGet();
        doAnswer(call -> {
            fault.inject(Phase.BEGIN);
            return call.callRealMethod();
        }).when(connection).setAutoCommit(false);
        doAnswer(call -> {
            fault.inject(Phase.RESET_AUTOCOMMIT);
            return call.callRealMethod();
        }).when(connection).setAutoCommit(true);
        doAnswer(call -> {
            fault.inject(Phase.ISOLATION);
            return call.callRealMethod();
        }).when(connection).setTransactionIsolation(anyInt());
        doAnswer(commit -> {
            fault.inject(Phase.BEFORE_COMMIT);
            if (failBeforeCommit.compareAndSet(true, false)) {
                throw new SQLException("Lost connection before commit", "08006");
            }
            commit.callRealMethod();
            fault.inject(Phase.AFTER_COMMIT);
            if (loseCommitResponse.compareAndSet(true, false)) {
                throw new SQLException("Lost commit acknowledgement", "08006");
            }
            return null;
        }).when(connection).commit();
        doAnswer(call -> {
            call.callRealMethod();
            fault.inject(Phase.AFTER_ROLLBACK);
            return null;
        }).when(connection).rollback();
        var wasClosed = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            if (wasClosed.compareAndSet(false, true)) closed.incrementAndGet();
            fault.inject(Phase.AFTER_CLOSE);
            return null;
        }).when(connection).close();
        return connection;
    }
}

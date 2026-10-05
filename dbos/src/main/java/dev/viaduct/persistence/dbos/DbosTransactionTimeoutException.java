package dev.viaduct.persistence.dbos;

/** No further transaction attempt may start after the configured retry deadline. */
public final class DbosTransactionTimeoutException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    DbosTransactionTimeoutException() {
        super("DBOS transaction retry deadline exceeded");
    }
}

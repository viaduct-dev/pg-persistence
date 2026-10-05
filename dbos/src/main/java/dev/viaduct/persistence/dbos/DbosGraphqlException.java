package dev.viaduct.persistence.dbos;

/** Serializable GraphQL failure that DBOS can save and replay without losing paths or extensions. */
public final class DbosGraphqlException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String response;

    DbosGraphqlException(String message, String response) {
        super(message);
        this.response = response;
    }

    /** GraphQL error details as JSON. Rolled-back mutation data is not included. */
    public String getResponse() {
        return response;
    }
}

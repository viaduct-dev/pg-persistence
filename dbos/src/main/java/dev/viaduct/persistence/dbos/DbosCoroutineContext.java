package dev.viaduct.persistence.dbos;

import dev.dbos.transact.context.DBOSContext;
import dev.dbos.transact.context.DBOSContextHolder;
import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElement;
import org.jetbrains.annotations.NotNull;

/** Carries DBOS's thread-local workflow state across a coroutine dispatcher boundary. */
final class DbosCoroutineContext extends AbstractCoroutineContextElement
        implements ThreadContextElement<DBOSContext> {
    private static final Key KEY = new Key();
    private final DBOSContext context;

    private DbosCoroutineContext(DBOSContext context) {
        super(KEY);
        this.context = context;
    }

    static CoroutineContext capture() {
        return new DbosCoroutineContext(DBOSContextHolder.get());
    }

    @Override
    public DBOSContext updateThreadContext(@NotNull CoroutineContext coroutineContext) {
        var previous = DBOSContextHolder.get();
        DBOSContextHolder.set(context);
        return previous;
    }

    @Override
    public void restoreThreadContext(@NotNull CoroutineContext coroutineContext, DBOSContext previous) {
        DBOSContextHolder.set(previous);
    }

    private static final class Key implements CoroutineContext.Key<DbosCoroutineContext> {}
}

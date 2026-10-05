import dev.dbos.transact.DBOS
import dev.dbos.transact.config.DBOSConfig
import dev.dbos.transact.context.WorkflowOptions
import dev.dbos.transact.txstep.IsolationLevel
import dev.dbos.transact.txstep.StepFactoryOptions
import dev.viaduct.persistence.dbos.DbosGraphqlException
import dev.viaduct.persistence.dbos.DbosTransactionTimeoutException
import dev.viaduct.persistence.dbos.DbosTransactions
import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor
import dev.viaduct.persistence.jdbc.JdbcRequestSetup
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real DBOS and pg_graphql through the current project JARs and Kotlin 2.4 dependency graph. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class DbosTransactionsTest {
    private lateinit var database: DbosTestDatabase
    private lateinit var dbos: DBOS
    private lateinit var workflow: MemberWorkflows
    private lateinit var uncertain: MemberWorkflows
    private lateinit var uncertainSerializable: MemberWorkflows
    private lateinit var uncertainSource: CommitFailureDataSource
    private lateinit var implementation: MemberWorkflowsImpl
    private lateinit var serializable: MemberWorkflows
    private lateinit var serializableImplementation: MemberWorkflowsImpl
    private lateinit var limited: MemberWorkflows
    private lateinit var limitedImplementation: MemberWorkflowsImpl
    private val connection = AtomicReference<Connection>()
    private val loseCommitResponse = AtomicBoolean()
    private val failBeforeCommit = AtomicBoolean()
    private val uncertainFault = AtomicReference(CommitFailureDataSource.Fault {})
    private val serializer = TestDbosSerializer()

    @BeforeAll
    fun start() {
        database = DbosTestDatabase()
        dbos =
            DBOS(
                DBOSConfig
                    .defaults("pg-persistence-dbos-test")
                    .withDataSource(database.source)
                    .withDatabaseSchema(database.schema)
                    .withSerializer(serializer)
                    .withAppVersion("test"),
            )

        fun client(
            source: javax.sql.DataSource,
            options: StepFactoryOptions = StepFactoryOptions("membership"),
        ) = DbClient(
            JdbcPgGraphqlExecutor(database.source),
            transactions =
                DbosTransactions(
                    dbos,
                    source,
                    options,
                    JdbcRequestSetup {
                        value,
                        _,
                        ->
                        connection.set(value)
                    },
                ),
        )
        implementation = MemberWorkflowsImpl(client(database.source), database, connection)
        workflow = dbos.registerProxy(MemberWorkflows::class.java, implementation, "normal")
        uncertainSource = CommitFailureDataSource(database.source, loseCommitResponse, failBeforeCommit) { uncertainFault.get().inject(it) }
        val uncertainClient = client(uncertainSource)
        uncertain = dbos.registerProxy(MemberWorkflows::class.java, MemberWorkflowsImpl(uncertainClient, database, connection), "uncertain")
        uncertainSerializable =
            dbos.registerProxy(
                MemberWorkflows::class.java,
                MemberWorkflowsImpl(
                    client(uncertainSource, StepFactoryOptions("faults", IsolationLevel.SERIALIZABLE)),
                    database,
                    connection,
                ),
                "uncertainSerializable",
            )
        serializableImplementation =
            MemberWorkflowsImpl(
                client(database.source, StepFactoryOptions("serializable", IsolationLevel.SERIALIZABLE)),
                database,
                connection,
            )
        serializable = dbos.registerProxy(MemberWorkflows::class.java, serializableImplementation, "serializable")
        val retryCommit = AtomicBoolean()
        val conflictingSource =
            CommitFailureDataSource(database.source, AtomicBoolean(), AtomicBoolean()) { phase ->
                if (phase == CommitFailureDataSource.Phase.BEFORE_COMMIT && retryCommit.compareAndSet(true, false)) {
                    throw SQLException("Repeated serialization conflict", "40001")
                }
            }
        val limitedClient =
            DbClient(
                JdbcPgGraphqlExecutor(database.source),
                transactions =
                    DbosTransactions(
                        dbos,
                        conflictingSource,
                        StepFactoryOptions("retry-deadline"),
                        JdbcRequestSetup { _, _ -> retryCommit.set(true) },
                        Duration.ofSeconds(1),
                    ),
            )
        limitedImplementation = MemberWorkflowsImpl(limitedClient, database, connection)
        limited = dbos.registerProxy(MemberWorkflows::class.java, limitedImplementation, "limited")
        dbos.launch()
    }

    @AfterAll
    fun stop() {
        if (::dbos.isInitialized) dbos.close()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `same client API commits converted input and returns a usable handle`() {
        val id = id()
        val result = run { workflow.insert(id, "Guest community") }
        assertThat(result).contains(id)
        assertThat(database.name(id)).isEqualTo("Guest community")
    }

    @Test
    fun `insert is visible inside JDBC transaction before block ends`() {
        val id = id()
        assertThat(run { workflow.immediate(id) }).isEqualTo("Visible inside")
        assertThat(database.name(id)).isEqualTo("Visible inside")
    }

    @Test
    fun `later GraphQL failure rolls back earlier call`() {
        val id = id()
        assertThatThrownBy { run { workflow.insertMembersInTransaction(id, id) } }
            .isInstanceOf(DbosGraphqlException::class.java)
            .hasMessageContaining("duplicate key")
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `caught mutation error cannot commit earlier writes`() {
        val id = id()
        assertThatThrownBy { run { workflow.caughtFailure(id) } }.isInstanceOf(DbosGraphqlException::class.java)
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `application exception rolls back`() {
        val id = id()
        assertThatThrownBy { run { workflow.applicationFailure(id) } }.hasMessageContaining("Application rejected")
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `batch insert update and delete share one transaction`() {
        val first = id()
        val second = id()
        assertThat(run { workflow.batch(first, second) }).isEqualTo("2, 1, 1, 1, 1")
        assertThat(listOf(database.name(first), database.name(second))).containsExactly(null, null)
    }

    @Test
    fun `Unit result is supported and escaped scope is closed`() {
        assertThat(run { workflow.escaped(id()) }).isTrue()
        val id = id()
        assertThatThrownBy {
            implementation.escapedScope
                .get()
                .entity<DbosMember>()
                .insert(MemberInput(id, "Too late").toPgGraphqlInsert())
        }.hasMessageContaining("closed")
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `null result is supported`() {
        assertThat(run { workflow.nullValue(id()) }).isTrue()
    }

    @Test
    fun `unrestorable return value rolls back before DBOS commits`() {
        val id = id()
        assertThatThrownBy { run { workflow.unsupportedValue(id) } }.hasStackTraceContaining("Cannot construct instance")
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `configured serializer must restore result before writes commit`() {
        val id = id()
        serializer.rejectResult.set(true)
        try {
            assertThatThrownBy { run { workflow.insert(id, "Not committed") } }
                .hasMessageContaining("Configured serializer rejected")
        } finally {
            serializer.rejectResult.set(false)
        }
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `malformed operation results roll back even when the wrapper can be restored`() {
        val id = id()
        serializer.corruptResult.set(true)
        try {
            assertThatThrownBy { run { workflow.insert(id, "Not committed") } }
                .hasStackTraceContaining("JsonDecodingException")
        } finally {
            serializer.corruptResult.set(false)
        }
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `nested transaction is rejected`() {
        val id = id()
        assertThatThrownBy { run { workflow.nested(id) } }.hasMessageContaining("outside any existing step")
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `calls outside a workflow are rejected`() {
        assertThatThrownBy { implementation.insert(id(), "Outside") }.hasMessageContaining("require a workflow")
    }

    @Test
    fun `transaction replay restores value and handles without rerunning block`() {
        val id = id()
        val workflowId = id()
        implementation.failAfterCommit.set(true)
        assertThatThrownBy { run(workflowId) { workflow.insert(id, "Original") } }.hasMessageContaining("After commit")
        val calls = implementation.calls.get()
        dbos.close()
        database.sql("UPDATE member SET name = 'Changed after commit' WHERE uuid_id = ?::uuid", id)
        dbos.launch()

        assertThat(dbos.forkWorkflow<String, RuntimeException>(workflowId, 1).result).contains(id)
        assertThat(implementation.calls.get()).isEqualTo(calls)
        assertThat(database.name(id)).isEqualTo("Changed after commit")
    }

    @Test
    fun `recorded GraphQL failure can be replayed`() {
        val id = id()
        val workflowId = id()
        assertThatThrownBy { run(workflowId) { workflow.insertMembersInTransaction(id, id) } }
            .isInstanceOf(DbosGraphqlException::class.java)
        dbos.close()
        dbos.launch()

        assertThatThrownBy { dbos.resumeWorkflow<String, RuntimeException>(workflowId).result }
            .isInstanceOf(DbosGraphqlException::class.java)
            .satisfies(
                java.util.function.Consumer { error ->
                    assertThat((error as DbosGraphqlException).response).contains("duplicate key")
                },
            )
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `failure saving the DBOS result rolls back business writes`() {
        val id = id()
        val workflowId = id()
        database.sql(
            "ALTER TABLE ${database.schema}.tx_step_outputs ADD CONSTRAINT reject_test_output CHECK (workflow_id <> '$workflowId' OR output IS NULL)",
        )
        try {
            assertThatThrownBy { run(workflowId) { workflow.insert(id, "Not committed") } }.hasMessageContaining("reject_test_output")
            assertThat(database.name(id)).isNull()
        } finally {
            database.sql("ALTER TABLE ${database.schema}.tx_step_outputs DROP CONSTRAINT reject_test_output")
        }
    }

    @Test
    fun `concurrent duplicate workflow produces one committed result`() {
        val id = id()
        val workflowId = id()
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                List(2) {
                    executor.submit<String> {
                        barrier.await(10, TimeUnit.SECONDS)
                        run(workflowId) { workflow.insert(id, "Once") }
                    }
                }
            assertThat(results[0].get(30, TimeUnit.SECONDS)).isEqualTo(results[1].get(30, TimeUnit.SECONDS))
        }
        assertThat(database.scalar("SELECT count(*) FROM member WHERE uuid_id = ?::uuid", id)).isEqualTo("1")
    }

    @Test
    fun `different workflows still enforce business constraints`() {
        val id = id()
        run { workflow.insert(id, "Original") }
        val before = implementation.calls.get()
        assertThatThrownBy { run { workflow.insert(id, "Conflict") } }.isInstanceOf(DbosGraphqlException::class.java)
        assertThat(implementation.calls.get() - before).isEqualTo(3)
        assertThat(database.name(id)).isEqualTo("Original")
    }

    @Test
    fun `lost commit acknowledgement recovers the recorded transaction`() {
        val id = id()
        val workflowId = id()
        loseCommitResponse.set(true)
        assertThat(run(workflowId) { uncertain.insert(id, "Committed") }).contains(id)
        assertThat(database.name(id)).isEqualTo("Committed")
        dbos.close()
        dbos.launch()

        assertThat(dbos.retrieveWorkflow<String, RuntimeException>(workflowId).result).contains(id)
        assertThat(database.scalar("SELECT count(*) FROM member WHERE uuid_id = ?::uuid", id)).isEqualTo("1")
    }

    @Test
    fun `connection failure without a saved result is not reported as success`() {
        val id = id()
        failBeforeCommit.set(true)
        assertThatThrownBy { run { uncertain.insert(id, "Not committed") } }
            .hasRootCauseMessage("Lost connection before commit")
        assertThat(database.name(id)).isNull()
    }

    @ParameterizedTest
    @EnumSource(value = CommitFailureDataSource.Phase::class, names = ["BEGIN", "ISOLATION"])
    fun `transaction setup failure closes every borrowed connection`(target: CommitFailureDataSource.Phase) {
        val failOnce = AtomicBoolean(true)
        uncertainFault.set { phase ->
            if (phase == target && failOnce.compareAndSet(true, false)) throw SQLException("Setup failed at $target", "08006")
        }
        val id = id()
        try {
            assertThatThrownBy { run { uncertainSerializable.insert(id, "Not committed") } }
                .hasRootCauseMessage("Setup failed at $target")
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isNull()
        assertThat(uncertainSource.closed.get()).isEqualTo(uncertainSource.opened.get())
    }

    @Test
    fun `connections borrowed with autocommit disabled still commit`() {
        val id = id()
        uncertainSource.autoCommit.set(false)
        try {
            assertThat(run { uncertain.insert(id, "Committed") }).contains(id)
        } finally {
            uncertainSource.autoCommit.set(true)
        }
        assertThat(database.name(id)).isEqualTo("Committed")
    }

    @Test
    fun `connections borrowed with autocommit disabled roll back on failure`() {
        val id = id()
        uncertainSource.autoCommit.set(false)
        try {
            assertThatThrownBy { run { uncertain.applicationFailure(id) } }.hasMessage("Application rejected the change")
        } finally {
            uncertainSource.autoCommit.set(true)
        }
        assertThat(database.name(id)).isNull()
    }

    @Test
    fun `failed borrowed connection setup still closes the connection`() {
        val failOnce = AtomicBoolean(true)
        uncertainSource.autoCommit.set(false)
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.RESET_AUTOCOMMIT && failOnce.compareAndSet(true, false)) {
                throw SQLException("Cannot reset borrowed connection", "08006")
            }
        }
        try {
            assertThatThrownBy { run { uncertain.insert(id(), "Not committed") } }
                .hasRootCauseMessage("Cannot reset borrowed connection")
        } finally {
            uncertainSource.autoCommit.set(true)
            uncertainFault.set {}
        }
        assertThat(uncertainSource.closed.get()).isEqualTo(uncertainSource.opened.get())
    }

    @Test
    fun `rollback and close errors do not replace the application failure`() {
        val failRollback = AtomicBoolean(true)
        val failClose = AtomicBoolean()
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.AFTER_ROLLBACK && failRollback.compareAndSet(true, false)) {
                failClose.set(true)
                throw SQLException("Rollback acknowledgement lost", "08006")
            }
            if (phase == CommitFailureDataSource.Phase.AFTER_CLOSE && failClose.compareAndSet(true, false)) {
                throw SQLException("Close failed", "08006")
            }
        }
        val id = id()
        try {
            assertThatThrownBy { run { uncertainSerializable.applicationFailure(id) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Application rejected the change")
                .hasStackTraceContaining("Rollback acknowledgement lost")
                .hasStackTraceContaining("Close failed")
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isNull()
        assertThat(uncertainSource.closed.get()).isEqualTo(uncertainSource.opened.get())
    }

    @Test
    fun `cleanup failures preserve the SQLSTATE needed to retry a conflict`() {
        val failCommit = AtomicBoolean(true)
        val failRollback = AtomicBoolean()
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.BEFORE_COMMIT && failCommit.compareAndSet(true, false)) {
                failRollback.set(true)
                throw SQLException("Serialization conflict", "40001")
            }
            if (phase == CommitFailureDataSource.Phase.AFTER_ROLLBACK && failRollback.compareAndSet(true, false)) {
                throw SQLException("Rollback acknowledgement lost", "08006")
            }
        }
        val id = id()
        try {
            assertThat(run { uncertainSerializable.insert(id, "Retried") }).contains(id)
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isEqualTo("Retried")
        assertThat(uncertainSource.closed.get()).isEqualTo(uncertainSource.opened.get())
    }

    @Test
    fun `error recording failure is suppressed on the transaction failure`() {
        val failNextOpens = AtomicInteger()
        uncertainFault.set { phase ->
            // Fail both the saved-result lookup and the subsequent error-recording connection.
            if (phase == CommitFailureDataSource.Phase.AFTER_ROLLBACK) failNextOpens.set(2)
            if (phase == CommitFailureDataSource.Phase.OPEN && failNextOpens.getAndDecrement() > 0) {
                throw SQLException("Cannot record transaction error", "08006")
            }
        }
        val id = id()
        try {
            assertThatThrownBy { run { uncertain.applicationFailure(id) } }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Application rejected the change")
                .hasStackTraceContaining("Cannot record transaction error")
                .satisfies(java.util.function.Consumer { failure -> assertThat(failure.suppressed).hasSize(2) })
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isNull()
    }

    @ParameterizedTest
    @MethodSource("cleanupFailures")
    fun `connection close error after commit restores the saved result`(failure: Exception) {
        val failClose = AtomicBoolean()
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.AFTER_COMMIT) failClose.set(true)
            if (phase == CommitFailureDataSource.Phase.AFTER_CLOSE && failClose.compareAndSet(true, false)) {
                throw failure
            }
        }
        val id = id()
        try {
            assertThat(run { uncertainSerializable.insert(id, "Committed") }).contains(id)
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isEqualTo("Committed")
    }

    @Test
    fun `saved result restoration error is not hidden by lost acknowledgement`() {
        val duplicate = id()
        val recordedError = catchThrowable { run { workflow.insertMembersInTransaction(duplicate, duplicate) } } as DbosGraphqlException
        val id = id()
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.AFTER_COMMIT && loseCommitResponse.get()) {
                serializer.restorationFailure.set(recordedError)
            }
        }
        loseCommitResponse.set(true)
        try {
            assertThatThrownBy { run { uncertain.insert(id, "Committed") } }
                .isInstanceOf(DbosGraphqlException::class.java)
                .hasMessageContaining("duplicate key")
        } finally {
            uncertainFault.set {}
            serializer.restorationFailure.set(null)
        }
        assertThat(database.name(id)).isEqualTo("Committed")
    }

    @Test
    fun `failed saved result lookup is suppressed on the connection failure`() {
        val failNextLookup = AtomicBoolean()
        uncertainFault.set { phase ->
            if (phase == CommitFailureDataSource.Phase.AFTER_COMMIT && loseCommitResponse.get()) failNextLookup.set(true)
            if (phase == CommitFailureDataSource.Phase.OPEN && failNextLookup.compareAndSet(true, false)) {
                throw SQLException("Saved result lookup unavailable", "08006")
            }
        }
        val id = id()
        loseCommitResponse.set(true)
        try {
            assertThatThrownBy { run { uncertain.insert(id, "Committed") } }
                .hasRootCauseMessage("Lost commit acknowledgement")
                .satisfies(
                    java.util.function.Consumer { failure ->
                        assertThat(failure.suppressed)
                            .singleElement(org.assertj.core.api.InstanceOfAssertFactories.THROWABLE)
                            .hasStackTraceContaining("Saved result lookup unavailable")
                    },
                )
        } finally {
            uncertainFault.set {}
        }
        assertThat(database.name(id)).isEqualTo("Committed")
    }

    @Test
    fun `repeated JDBC conflicts stop at the retry deadline and leave no writes`() {
        val id = id()
        val workflowId = id()
        val before = limitedImplementation.calls.get()
        val failure = catchThrowable { run(workflowId) { limited.insert(id, "Not committed") } }
        assertThat(failure).isInstanceOf(DbosTransactionTimeoutException::class.java).hasNoCause()
        assertThat(limitedImplementation.calls.get() - before).isGreaterThan(1)
        assertThat(database.name(id)).isNull()
        assertThatThrownBy { dbos.retrieveWorkflow<String, RuntimeException>(workflowId).result }
            .isInstanceOf(DbosTransactionTimeoutException::class.java)
    }

    @Test
    fun `variables remain data rather than SQL`() {
        val id = id()
        val name = "Guest'); DROP TABLE member; -- \"quoted\""
        run { workflow.insert(id, name) }
        assertThat(database.name(id)).isEqualTo(name)
    }

    @Test
    fun `real deadlock retries the rolled back transaction`() {
        conflictingUpdates(workflow, implementation, false)
    }

    @Test
    fun `real serializable update conflict retries the rolled back transaction`() {
        conflictingUpdates(serializable, serializableImplementation, true)
    }

    private fun conflictingUpdates(
        target: MemberWorkflows,
        state: MemberWorkflowsImpl,
        beforeUpdate: Boolean,
    ) {
        val first = id()
        val second = id()
        val audit1 = id()
        val audit2 = id()
        run { workflow.insertMembersInTransaction(first, second) }
        state.conflictBarrier = CyclicBarrier(2)
        state.gateArrivals.set(0)
        val before = state.calls.get()
        Executors.newFixedThreadPool(2).use { executor ->
            val left = executor.submit<Boolean> { run { target.conflictingUpdate(first, second, audit1, beforeUpdate) } }
            val right =
                executor.submit<Boolean> {
                    run {
                        target.conflictingUpdate(
                            if (beforeUpdate) first else second,
                            if (beforeUpdate) second else first,
                            audit2,
                            beforeUpdate,
                        )
                    }
                }
            assertThat(listOf(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS))).containsExactly(true, true)
        }
        assertThat(state.calls.get() - before).isGreaterThanOrEqualTo(3)
        assertThat(listOf(database.name(first), database.name(second))).containsExactly("Updated", "Updated")
        assertThat(listOf(database.name(audit1), database.name(audit2))).containsExactly("Audit", "Audit")
    }

    private fun <T> run(
        workflowId: String = id(),
        block: () -> T,
    ): T = WorkflowOptions(workflowId).setContext().use { block() }

    private fun id() = UUID.randomUUID().toString()

    companion object {
        @JvmStatic
        fun cleanupFailures(): List<Exception> =
            listOf(
                SQLException("Connection cleanup failed", "08006"),
                SQLException("Connection cleanup failed"),
                SQLException("Connection cleanup failed", "HY000"),
                IllegalStateException("Pool cleanup failed"),
            )
    }
}

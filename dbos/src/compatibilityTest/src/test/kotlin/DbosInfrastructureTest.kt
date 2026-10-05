import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dbos.transact.DBOS
import dev.dbos.transact.config.DBOSConfig
import dev.dbos.transact.context.WorkflowOptions
import dev.dbos.transact.txstep.IsolationLevel
import dev.dbos.transact.txstep.StepFactoryOptions
import dev.viaduct.persistence.dbos.DbosGraphqlException
import dev.viaduct.persistence.dbos.DbosTransactions
import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor
import dev.viaduct.persistence.jdbc.JdbcRequestSetup
import dev.viaduct.persistence.runtime.db.DbClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@Timeout(60)
class DbosInfrastructureTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `one physical pooled connection is reusable after commit and rollback`(autoCommit: Boolean) {
        DbosTestDatabase().use { database ->
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = database.source
                    maximumPoolSize = 1
                    minimumIdle = 1
                    connectionTimeout = 3000
                    isAutoCommit = autoCommit
                    transactionIsolation = "TRANSACTION_READ_COMMITTED"
                },
            ).use { pool ->
                val baseline = pool.snapshot()
                val states = mutableListOf<PoolState>()
                val transactionPids = mutableListOf<Int>()
                withWorkflow(
                    database,
                    pool,
                    JdbcRequestSetup {
                        connection,
                        _,
                        ->
                        transactionPids += connection.backendPid()
                    },
                ) { workflow, _ ->
                    val first = id()
                    val failed = id()
                    val last = id()
                    run { workflow.insert(first, "First") }
                    states += pool.snapshot()
                    assertThatThrownBy { run { workflow.insertMembersInTransaction(failed, failed) } }
                        .isInstanceOf(DbosGraphqlException::class.java)
                    states += pool.snapshot()
                    run { workflow.insert(last, "Last") }
                    states += pool.snapshot()
                    assertThat(
                        listOf(database.name(first), database.name(failed), database.name(last)),
                    ).containsExactly("First", null, "Last")
                }
                assertThat(states).containsExactly(baseline, baseline, baseline)
                assertThat(transactionPids).isNotEmpty().containsOnly(baseline.pid)
            }
        }
    }

    @Test
    fun `DBOS and GraphQL execution preserve tighter driver timeouts`() {
        DbosTestDatabase().use { database ->
            database.source.queryTimeout = 2
            val queryTimeouts = mutableListOf<Int>()
            val source =
                object : DataSource by database.source {
                    override fun getConnection(): Connection =
                        trackQueryTimeouts(
                            database.source.connection.apply { setNetworkTimeout(Runnable::run, 1000) },
                            queryTimeouts,
                        )
                }
            val observedTimeouts = mutableListOf<Int>()
            withWorkflow(database, source, JdbcRequestSetup { connection, _ -> observedTimeouts += connection.networkTimeout }) {
                workflow,
                _,
                ->
                run { workflow.insert(id(), "Bounded") }
            }
            assertThat(queryTimeouts.toSet() to observedTimeouts).isEqualTo(setOf(2) to listOf(1000))
        }
    }

    @Test
    fun `a real lost commit response returns the saved result without repeating the block`() {
        DbosTestDatabase().use { database ->
            CommitResponseProxy(database.source).use { proxy ->
                withWorkflow(database, proxy.source) { workflow, implementation ->
                    val memberId = id()
                    proxy.dropNextCommitResponse()
                    assertThat(run { workflow.insert(memberId, "Committed") }).contains(memberId)
                    assertThat(proxy.dropped.get()).isEqualTo(1)
                    assertThat(implementation.calls.get()).isEqualTo(1)
                    assertThat(database.name(memberId)).isEqualTo("Committed")
                }
            }
        }
    }

    private fun trackQueryTimeouts(
        connection: Connection,
        timeouts: MutableList<Int>,
    ): Connection {
        val wrapper = mock(Connection::class.java, delegatesTo<Connection>(connection))
        doAnswer { call ->
            val statement = connection.prepareStatement(call.getArgument<String>(0))
            val wrapped = mock(PreparedStatement::class.java, delegatesTo<PreparedStatement>(statement))
            doAnswer { change ->
                val timeout = change.getArgument<Int>(0)
                timeouts += timeout
                statement.queryTimeout = timeout
                null
            }.`when`(wrapped).setQueryTimeout(anyInt())
            wrapped
        }.`when`(wrapper).prepareStatement(anyString())
        return wrapper
    }

    private fun withWorkflow(
        database: DbosTestDatabase,
        source: DataSource,
        setup: JdbcRequestSetup = JdbcRequestSetup { _, _ -> },
        block: (MemberWorkflows, MemberWorkflowsImpl) -> Unit,
    ) {
        DBOS(
            DBOSConfig
                .defaults("pg-persistence-infrastructure-test")
                .withDataSource(database.source)
                .withDatabaseSchema(database.schema)
                .withAppVersion("test"),
        ).use { dbos ->
            val client =
                DbClient(
                    JdbcPgGraphqlExecutor(source),
                    transactions = DbosTransactions(dbos, source, StepFactoryOptions("membership", IsolationLevel.SERIALIZABLE), setup),
                )
            val implementation = MemberWorkflowsImpl(client, database, AtomicReference())
            val workflow = dbos.registerProxy(MemberWorkflows::class.java, implementation)
            dbos.launch()
            block(workflow, implementation)
        }
    }

    private data class PoolState(
        val pid: Int,
        val autoCommit: Boolean,
        val isolation: Int,
        val readOnly: Boolean,
        val activeConnections: Int,
    )

    private fun HikariDataSource.snapshot(): PoolState {
        val active = hikariPoolMXBean.activeConnections
        return connection.use { PoolState(it.backendPid(), it.autoCommit, it.transactionIsolation, it.isReadOnly, active) }
    }

    private fun Connection.backendPid(): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun <T> run(block: () -> T): T = WorkflowOptions(id()).setContext().use { block() }

    private fun id() = UUID.randomUUID().toString()
}

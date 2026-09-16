import dev.dbos.transact.DBOS
import dev.dbos.transact.config.DBOSConfig
import dev.dbos.transact.context.WorkflowOptions
import dev.dbos.transact.txstep.StepFactoryOptions
import dev.dbos.transact.workflow.Workflow
import dev.viaduct.persistence.dbos.DbosTransactions
import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor
import dev.viaduct.persistence.jdbc.JdbcRequestSetup
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import viaduct.api.context.ExecutionContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface RecoveryWorkflow {
    fun insert(id: String): String
}

class RecoveryWorkflowImpl(
    private val client: DbClient,
) : RecoveryWorkflow {
    val finished = CompletableFuture<String>()

    @Workflow
    override fun insert(id: String): String {
        try {
            val result =
                runBlocking {
                    val committed =
                        client.transaction(mockk<ExecutionContext>()) {
                            println("MUTATION")
                            entity<DbosMember>().insert(MemberInput(id, "Once").toPgGraphqlInsert())
                        }
                    requireNotNull(committed.result[committed.value]).toString()
                }
            // Report this attempt's result, not a workflow handle that could adopt another worker's result.
            println("RESULT=$result")
            finished.complete(result)
            return result
        } catch (failure: Exception) {
            finished.completeExceptionally(failure)
            throw failure
        }
    }
}

/** Two JVMs share an executor identity to simulate recovery while its original worker is still alive. */
object ConcurrentRecoveryWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val (url, schema, workflowId, memberId, mode) = args
        val source =
            PGSimpleDataSource().apply {
                setURL(url)
                user = "postgres"
                password = System.getenv("PG_DBOS_PASSWORD") ?: "postgres"
            }
        DBOS(
            DBOSConfig
                .defaults("pg-persistence-recovery-test")
                .withDataSource(source)
                .withDatabaseSchema(schema)
                .withExecutorId(workflowId)
                .withAppVersion("test"),
        ).use { dbos ->
            val firstCall = AtomicBoolean(true)
            val transactionSource =
                CommitFailureDataSource(source, AtomicBoolean(), AtomicBoolean()) { phase ->
                    if ((mode == "before-commit" && phase == CommitFailureDataSource.Phase.BEFORE_COMMIT) ||
                        (mode == "after-commit" && phase == CommitFailureDataSource.Phase.AFTER_COMMIT)
                    ) {
                        println("PAUSED")
                        check(readln() == "CONTINUE")
                    }
                }
            val client =
                DbClient(
                    JdbcPgGraphqlExecutor(source),
                    transactions =
                        DbosTransactions(
                            dbos,
                            transactionSource,
                            StepFactoryOptions("membership"),
                            JdbcRequestSetup { _, _ ->
                                if ((mode == "original" || mode == "recovered") && firstCall.compareAndSet(true, false)) {
                                    // The saved-result check has completed and the transaction is open.
                                    println("READY")
                                    check(readln() == "CONTINUE")
                                }
                            },
                        ),
                )
            val implementation = RecoveryWorkflowImpl(client)
            val workflow = dbos.registerProxy(RecoveryWorkflow::class.java, implementation)
            dbos.launch()
            if (mode != "recovered" && mode != "restart") {
                WorkflowOptions(workflowId).setContext().use { workflow.insert(memberId) }
            }
            // On the second worker, launch recovers the first worker's pending workflow.
            implementation.finished.get(40, TimeUnit.SECONDS)
        }
    }
}

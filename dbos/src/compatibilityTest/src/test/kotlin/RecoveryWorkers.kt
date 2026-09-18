import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/** Owns only the child JVMs started by one test; no application processes or DBOS records are changed. */
class RecoveryWorkers(
    private val database: DbosTestDatabase,
    private val logs: Path,
) : AutoCloseable {
    private val readers = Executors.newCachedThreadPool()
    private val workers = mutableListOf<Worker>()
    private val workflowId = UUID.randomUUID().toString()
    val memberId: String = UUID.randomUUID().toString()

    fun start(mode: String): Worker {
        val log = logs.resolve("$mode.log")
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+EnableDynamicAgentLoading",
                "-cp",
                System.getProperty("workerClasspath"),
                "ConcurrentRecoveryWorker",
                database.source.getURL(),
                database.schema,
                workflowId,
                memberId,
                mode,
            ).redirectError(log.toFile()).start()
        return Worker(process, log).also(workers::add)
    }

    inner class Worker(
        private val process: Process,
        private val log: Path,
    ) {
        private val output = process.inputReader()
        val lines = mutableListOf<String>()

        fun await(prefix: String): String =
            readers
                .submit<String> {
                    generateSequence { output.readLine() }
                        .onEach(lines::add)
                        .firstOrNull { it.startsWith(prefix) }
                        ?: error("Worker exited before $prefix:\n${log.readText()}")
                }.get(30, TimeUnit.SECONDS)

        fun release() {
            process.outputWriter().apply {
                write("CONTINUE\n")
                flush()
            }
        }

        fun finish(): Int {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Worker did not exit:\n${log.readText()}" }
            return process.exitValue()
        }

        fun kill() {
            check(process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)) { "Worker did not stop" }
        }
    }

    override fun close() {
        try {
            workers.forEach { it.kill() }
        } finally {
            readers.shutdownNow()
        }
    }
}

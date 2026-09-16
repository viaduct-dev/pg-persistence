import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

@Timeout(90)
class ConcurrentRecoveryTest {
    @TempDir
    lateinit var logs: Path

    @Test
    fun `recovery after the initial result check returns the winner despite a business constraint conflict`() {
        DbosTestDatabase().use { database ->
            RecoveryWorkers(database, logs).use { workers ->
                val original = workers.start("original")
                original.await("READY")
                val recovered = workers.start("recovered")
                recovered.await("READY")

                // Both checked for a saved result before either could commit. Force the original to win.
                original.release()
                val winner = original.await("RESULT=")
                recovered.release()
                assertThat(recovered.await("RESULT=")).isEqualTo(winner).contains(workers.memberId)
                assertThat(database.scalar("SELECT count(*) FROM member WHERE uuid_id = ?::uuid", workers.memberId)).isEqualTo("1")
                assertThat(listOf(original.finish(), recovered.finish())).containsExactly(0, 0)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["before-commit", "after-commit"])
    fun `a killed worker recovers without losing or repeating committed writes`(phase: String) {
        DbosTestDatabase().use { database ->
            RecoveryWorkers(database, logs).use { workers ->
                val original = workers.start(phase)
                original.await("PAUSED")
                // BEFORE_COMMIT follows the mutation and result storage, but precedes their commit.
                val committed = phase == "after-commit"
                assertThat(database.name(workers.memberId)).isEqualTo(if (committed) "Once" else null)
                original.kill()

                val restarted = workers.start("restart")
                assertThat(restarted.await("RESULT=")).contains(workers.memberId)
                assertThat(restarted.finish()).isZero()
                assertThat(restarted.lines.count { it == "MUTATION" }).isEqualTo(if (committed) 0 else 1)
                assertThat(database.scalar("SELECT count(*) FROM member WHERE uuid_id = ?::uuid", workers.memberId)).isEqualTo("1")
            }
        }
    }
}

import dev.dbos.transact.json.DBOSJavaSerializer
import dev.dbos.transact.json.DBOSSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A distinct configured format, including a failure only its decoder can detect. */
class TestDbosSerializer : DBOSSerializer by DBOSJavaSerializer.INSTANCE {
    val rejectResult = AtomicBoolean()
    val corruptResult = AtomicBoolean()
    val restorationFailure = AtomicReference<RuntimeException>()

    override fun name() = "pg-persistence-test"

    override fun deserialize(text: String?): Any? {
        if (text?.contains("DbosTransactions\$StoredCommit") == true) {
            restorationFailure.get()?.let { throw it }
            check(!rejectResult.get()) { "Configured serializer rejected transaction result" }
            if (corruptResult.get()) {
                val corrupted = JsonObject(Json.parseToJsonElement(text).jsonObject + ("result" to JsonPrimitive("not JSON")))
                return DBOSJavaSerializer.INSTANCE.deserialize(corrupted.toString())
            }
        }
        return DBOSJavaSerializer.INSTANCE.deserialize(text)
    }
}

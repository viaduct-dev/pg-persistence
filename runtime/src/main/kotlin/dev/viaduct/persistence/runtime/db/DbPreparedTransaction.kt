package dev.viaduct.persistence.runtime.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen, versioned request. Persist only in trusted storage; this contains application data, not credentials. */
class DbPreparedTransaction private constructor(
    private val encoded: String,
) {
    private val value: JsonObject get() = Json.parseToJsonElement(encoded).jsonObject
    val operationId: String get() = value.getValue("operationId").jsonPrimitive.content
    val scope: String get() = value.getValue("scope").jsonPrimitive.content
    internal val request: JsonObject get() = value.getValue("request").jsonObject
    internal val operationCount: Int get() =
        value
            .getValue("operationCount")
            .jsonPrimitive.content
            .toInt()

    fun encode(): String = encoded

    internal fun decodeResponse(response: JsonObject): DbTransactionResult {
        require(response["errors"] == null || response["errors"] == JsonArray(emptyList())) {
            "Committed response contains errors"
        }
        val data = requireNotNull(response["data"] as? JsonObject) { "Missing committed data" }
        val aliases = (0 until operationCount).map { "operation$it" }
        require(data.keys == aliases.toSet()) { "Committed response has unexpected operation aliases" }
        val payloads =
            aliases.associate { alias ->
                val payload = data.getValue(alias).jsonObject
                require((payload["affectedCount"]?.jsonPrimitive?.intOrNull ?: -1) >= 0) { "Missing affectedCount" }
                val records = requireNotNull(payload["records"] as? JsonArray) { "Missing records" }
                records.forEach {
                    require(it.jsonObject["uuidId"]?.jsonPrimitive?.isString == true) { "Missing uuidId" }
                }
                DbTransactionOperation(alias) to payload
            }
        return DbTransactionResult(payloads)
    }

    companion object {
        private const val MAX_ENCODED_LENGTH = 2_000_000
        private const val MAX_IDENTITY_BYTES = 256
        private const val MAX_OPERATIONS = 10_000

        /** Restore a request exported by [encode]; never accept this format from an untrusted client. */
        fun decode(encoded: String): DbPreparedTransaction {
            require(encoded.length <= MAX_ENCODED_LENGTH) { "Prepared transaction is too large" }
            val prepared = DbPreparedTransaction(encoded)
            require(prepared.operationId.isNotBlank() && prepared.operationId.toByteArray().size <= MAX_IDENTITY_BYTES)
            require(prepared.scope.isNotBlank() && prepared.scope.toByteArray().size <= MAX_IDENTITY_BYTES)
            require(prepared.operationCount in 1..MAX_OPERATIONS)
            require(prepared.request["protocolVersion"]?.jsonPrimitive?.intOrNull == 1) {
                "Unsupported transaction version"
            }
            require(prepared.request["operationName"]?.jsonPrimitive?.content == "DbTransaction")
            require(prepared.request["document"]?.jsonPrimitive?.isString == true)
            require(prepared.request["variables"] is JsonObject)
            return prepared
        }

        internal fun create(
            operationId: String,
            scope: String,
            request: JsonObject,
            operationCount: Int,
        ): DbPreparedTransaction =
            decode(
                buildJsonObject {
                    put("operationId", operationId)
                    put("scope", scope)
                    put("request", request)
                    put("operationCount", operationCount)
                }.toString(),
            )
    }
}

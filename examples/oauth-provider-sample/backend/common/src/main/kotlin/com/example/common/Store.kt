package com.example.common

import kotlinx.serialization.json.*
import viaduct.api.context.ExecutionContext

enum class Entity(val selection: String) {
    Account("id uuidId username passwordHash admin"),
    Group("id uuidId name"),
    Membership("id uuidId accountId groupId"),
    OAuthClient("id uuidId name redirectUris scopes enabled"),
    AccessRule("id uuidId groupId clientId scopes"),
    AuthorizationGrant("id uuidId accountId clientId codeHash codeChallenge redirectUri scopes expiresAt"),
}

interface Store {
    suspend fun list(entity: Entity, filter: JsonObject = buildJsonObject {}): List<JsonObject>
    suspend fun insert(entity: Entity, values: JsonObject): JsonObject
    suspend fun delete(entity: Entity, filter: JsonObject): Int
    suspend fun update(entity: Entity, filter: JsonObject, values: JsonObject): Int
}

data class Principal(val id: String, val admin: Boolean)
data class SampleContext(val principal: Principal)

fun ExecutionContext.principal(): Principal =
    (requestContext as? SampleContext)?.principal ?: error("Authentication required")

fun Principal.requireAdmin() { require(admin) { "Administrator access required" } }
fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean
fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }
fun values(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(fields.toMap())
fun eq(field: String, value: String): JsonObject =
    buildJsonObject { put(field, buildJsonObject { put("eq", value) }) }
fun jsonStrings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
fun globalId(value: String): String =
    String(java.util.Base64.getDecoder().decode(value)).substringAfter(":")

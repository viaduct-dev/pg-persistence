package com.example.provider

import com.example.common.*

data class Decision(val allowed: Boolean, val scopes: List<String>)

/** This tenant computes policy; its schema owns no persistent nodes. */
class AccessPolicy(private val store: Store) {
    suspend fun decide(accountId: String, clientId: String, requested: List<String>): Decision {
        val client = store.list(Entity.OAuthClient, eq("uuidId", clientId)).singleOrNull()
            ?: return Decision(false, emptyList())
        if (!client.flag("enabled") || requested.isEmpty() || requested.any { it !in client.strings("scopes") })
            return Decision(false, emptyList())
        val groups = store.list(Entity.Membership, eq("accountId", accountId)).map { it.text("groupId") }.toSet()
        val allowed = store.list(Entity.AccessRule, eq("clientId", clientId))
            .filter { it.text("groupId") in groups }.flatMap { it.strings("scopes") }.toSet()
        return Decision(requested.all { it in allowed }, requested.filter { it in allowed }.distinct().sorted())
    }
}

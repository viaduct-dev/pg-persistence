@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package com.example.database

import com.example.common.*
import com.example.database.resolverbases.*
import dev.viaduct.persistence.runtime.db.DbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import viaduct.api.grts.*
import viaduct.api.resolver.Resolver

@Resolver
class AccountNode(private val db: DbClient) : NodeResolvers.Account() {
    override suspend fun resolve(ctx: Context): Account = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "accountCollection", ctx.id.internalID)
    }
}
@Resolver
class GroupNode(private val db: DbClient) : NodeResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "groupCollection", ctx.id.internalID)
    }
}
@Resolver
class MembershipNode(private val db: DbClient) : NodeResolvers.Membership() {
    override suspend fun resolve(ctx: Context): Membership = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "membershipCollection", ctx.id.internalID)
    }
}
@Resolver
class ClientNode(private val db: DbClient) : NodeResolvers.OAuthClient() {
    override suspend fun resolve(ctx: Context): OAuthClient = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "oAuthClientCollection", ctx.id.internalID)
    }
}
@Resolver
class RuleNode(private val db: DbClient) : NodeResolvers.AccessRule() {
    override suspend fun resolve(ctx: Context): AccessRule = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "accessRuleCollection", ctx.id.internalID)
    }
}
@Resolver
class GrantNode(private val db: DbClient) : NodeResolvers.AuthorizationGrant() {
    override suspend fun resolve(ctx: Context): AuthorizationGrant = withContext(Dispatchers.IO) {
        ctx.principal().requireAdmin()
        db.fetchByInternalId(ctx, "authorizationGrantCollection", ctx.id.internalID)
    }
}

@Resolver
class Accounts(private val store: Store) : QueryResolvers.Accounts() {
    override suspend fun resolve(ctx: Context): List<Account> {
        ctx.principal().requireAdmin()
        return store.list(Entity.Account).map { ctx.ref(ctx.globalIDFor(Account.Reflection, it.text("uuidId"))) }
    }
}
@Resolver
class Groups(private val store: Store) : QueryResolvers.Groups() {
    override suspend fun resolve(ctx: Context): List<Group> {
        ctx.principal().requireAdmin()
        return store.list(Entity.Group).map { ctx.ref(ctx.globalIDFor(Group.Reflection, it.text("uuidId"))) }
    }
}
@Resolver
class Clients(private val store: Store) : QueryResolvers.Clients() {
    override suspend fun resolve(ctx: Context): List<OAuthClient> {
        ctx.principal().requireAdmin()
        return store.list(Entity.OAuthClient).map { ctx.ref(ctx.globalIDFor(OAuthClient.Reflection, it.text("uuidId"))) }
    }
}
@Resolver
class Rules(private val store: Store) : QueryResolvers.AccessRules() {
    override suspend fun resolve(ctx: Context): List<AccessRule> {
        ctx.principal().requireAdmin()
        return store.list(Entity.AccessRule).map { ctx.ref(ctx.globalIDFor(AccessRule.Reflection, it.text("uuidId"))) }
    }
}

@Resolver
class CreateAccount(private val store: Store) : MutationResolvers.CreateAccount() {
    override suspend fun resolve(ctx: Context): Account {
        ctx.principal().requireAdmin()
        require(ctx.arguments.username.matches(Regex("[a-z][a-z0-9_-]{2,31}"))) { "Invalid username" }
        val passwordHash = withContext(Dispatchers.IO) { Passwords.hash(ctx.arguments.password) }
        val record = store.insert(Entity.Account, values(
            "username" to JsonPrimitive(ctx.arguments.username), "passwordHash" to JsonPrimitive(passwordHash),
            "admin" to JsonPrimitive(false),
        ))
        return ctx.ref(ctx.globalIDFor(Account.Reflection, record.text("uuidId")))
    }
}
@Resolver
class CreateGroup(private val store: Store) : MutationResolvers.CreateGroup() {
    override suspend fun resolve(ctx: Context): Group {
        ctx.principal().requireAdmin()
        require(ctx.arguments.name.isNotBlank() && ctx.arguments.name.length <= 100) { "Invalid group name" }
        val record = store.insert(Entity.Group, values("name" to JsonPrimitive(ctx.arguments.name)))
        return ctx.ref(ctx.globalIDFor(Group.Reflection, record.text("uuidId")))
    }
}
@Resolver
class AddMember(private val store: Store) : MutationResolvers.AddMember() {
    override suspend fun resolve(ctx: Context): Membership {
        ctx.principal().requireAdmin()
        val record = store.insert(Entity.Membership, values(
            "groupId" to JsonPrimitive(ctx.arguments.groupId.internalID),
            "accountId" to JsonPrimitive(ctx.arguments.accountId.internalID),
        ))
        return ctx.ref(ctx.globalIDFor(Membership.Reflection, record.text("uuidId")))
    }
}
@Resolver
class RemoveMember(private val store: Store) : MutationResolvers.RemoveMember() {
    override suspend fun resolve(ctx: Context): Boolean {
        ctx.principal().requireAdmin()
        return store.delete(Entity.Membership, eq("uuidId", ctx.arguments.id.internalID)) == 1
    }
}
@Resolver
class CreateClient(private val store: Store) : MutationResolvers.CreateClient() {
    override suspend fun resolve(ctx: Context): OAuthClient {
        ctx.principal().requireAdmin()
        require(ctx.arguments.name.isNotBlank() && ctx.arguments.name.length <= 100) { "Invalid client name" }
        require(ctx.arguments.redirectUris.size in 1..8) { "Specify 1 to 8 redirect URLs" }
        val redirects = ctx.arguments.redirectUris.map(ClientValidation::redirect).distinct()
        val record = store.insert(Entity.OAuthClient, values(
            "name" to JsonPrimitive(ctx.arguments.name), "redirectUris" to jsonStrings(redirects),
            "scopes" to jsonStrings(ClientValidation.scopes(ctx.arguments.scopes)), "enabled" to JsonPrimitive(true),
        ))
        return ctx.ref(ctx.globalIDFor(OAuthClient.Reflection, record.text("uuidId")))
    }
}
@Resolver
class SetClientEnabled(private val store: Store) : MutationResolvers.SetClientEnabled() {
    override suspend fun resolve(ctx: Context): Boolean {
        ctx.principal().requireAdmin()
        return store.update(Entity.OAuthClient, eq("uuidId", ctx.arguments.id.internalID),
            values("enabled" to JsonPrimitive(ctx.arguments.enabled))) == 1
    }
}
@Resolver
class CreateAccessRule(private val store: Store) : MutationResolvers.CreateAccessRule() {
    override suspend fun resolve(ctx: Context): AccessRule {
        ctx.principal().requireAdmin()
        val scopes = ClientValidation.scopes(ctx.arguments.scopes)
        val client = store.list(Entity.OAuthClient, eq("uuidId", ctx.arguments.clientId.internalID)).single()
        require(scopes.all { it in client.strings("scopes") }) { "Rule scopes must be registered on the client" }
        val record = store.insert(Entity.AccessRule, values(
            "groupId" to JsonPrimitive(ctx.arguments.groupId.internalID),
            "clientId" to JsonPrimitive(ctx.arguments.clientId.internalID), "scopes" to jsonStrings(scopes),
        ))
        return ctx.ref(ctx.globalIDFor(AccessRule.Reflection, record.text("uuidId")))
    }
}
@Resolver
class DeleteAccessRule(private val store: Store) : MutationResolvers.DeleteAccessRule() {
    override suspend fun resolve(ctx: Context): Boolean {
        ctx.principal().requireAdmin()
        return store.delete(Entity.AccessRule, eq("uuidId", ctx.arguments.id.internalID)) == 1
    }
}

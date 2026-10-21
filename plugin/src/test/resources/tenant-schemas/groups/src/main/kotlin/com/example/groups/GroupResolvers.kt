package com.example.groups

import com.example.groups.resolverbases.MutationResolvers
import com.example.groups.resolverbases.NodeResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import viaduct.api.grts.Group
import viaduct.api.grts.Purchase
import viaduct.api.resolver.Resolver
import java.util.UUID

@Resolver
class GroupNodeResolver(private val db: DbClient) : NodeResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group = db.fetchByInternalId(ctx, "groupCollection", ctx.id.internalID)
}

@Resolver
class CreatePurchaseResolver(private val db: DbClient) : MutationResolvers.CreatePurchase() {
    override suspend fun resolve(ctx: Context): Group {
        val id = UUID.randomUUID().toString()
        db.transaction(ctx) {
            entity<Group>().insert(PgGraphqlObject.of("uuidId" to id, "name" to ctx.arguments.name))
            entity<Purchase>().insert(PgGraphqlObject.of("label" to ctx.arguments.name, "buyerId" to id))
            // Fail the last operation after both schemas have been written.
            if (ctx.arguments.fail) {
                entity<Group>().insert(PgGraphqlObject.of("uuidId" to id, "name" to ctx.arguments.name))
            }
        }
        return ctx.ref(ctx.globalIDFor(Group.Reflection, id))
    }
}

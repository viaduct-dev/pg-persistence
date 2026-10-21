package com.example.other

import com.example.other.resolverbases.NodeResolvers
import com.example.other.resolverbases.QueryResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.DbLookup
import viaduct.api.grts.Purchase
import viaduct.api.grts.PurchaseConnection
import viaduct.api.resolver.Resolver

@Resolver
class PurchaseNodeResolver(private val db: DbClient) : NodeResolvers.Purchase() {
    override suspend fun resolve(ctx: Context): Purchase = db.fetchByInternalId(ctx, "purchaseCollection", ctx.id.internalID)
}

@Resolver
class LookupPurchasesResolver(private val db: DbClient) : QueryResolvers.LookupPurchases() {
    override suspend fun resolve(ctx: Context): PurchaseConnection =
        db.lookupConnection(ctx, DbLookup.by<String, Purchase>(Purchase.Fields.label), ctx.arguments.label)
}

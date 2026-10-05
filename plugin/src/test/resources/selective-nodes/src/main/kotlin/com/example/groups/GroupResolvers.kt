package com.example.groups

import com.example.groups.resolverbases.MutationResolvers
import com.example.groups.resolverbases.NodeResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import viaduct.api.grts.AddGroupPayload
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class GroupNodeResolver(
    private val dbClient: DbClient,
) : NodeResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group =
        dbClient.fetchByInternalId(
            ctx = ctx,
            collectionField = "groupCollection",
            id = ctx.id.internalID,
        )
}

@Resolver
class AddGroupResolver(
    private val dbClient: DbClient,
) : MutationResolvers.AddGroup() {
    override suspend fun resolve(ctx: Context): AddGroupPayload {
        val insert = ctx.arguments.input.toPgGraphqlInsert()
        return dbClient.entity<Group>().insert(ctx, insert)
    }
}

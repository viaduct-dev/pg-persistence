package com.example.groups

import com.example.groups.resolverbases.MutationResolvers
import com.example.groups.resolverbases.NodeResolvers
import com.example.groups.resolverbases.QueryResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import viaduct.api.grts.AddGroupPayload
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver
import viaduct.api.FieldValue
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation

@Resolver
class GroupNodeResolver(
    private val dbClient: DbClient,
) : NodeResolvers.Group() {
    override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<Group>> =
        dbClient.fetchByInternalIdsResult(
            contexts = contexts,
            collectionField = "groupCollection",
        )
}

@Resolver
class FirstGroupResolver : QueryResolvers.FirstGroup() {
    override suspend fun resolve(ctx: Context): Group =
        ctx.ref(ctx.globalIDFor(Group.Reflection, "00000000-0000-0000-0000-000000000001"))
}

@Resolver
class SecondGroupResolver : QueryResolvers.SecondGroup() {
    override suspend fun resolve(ctx: Context): Group =
        ctx.ref(ctx.globalIDFor(Group.Reflection, "00000000-0000-0000-0000-000000000002"))
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

@GraphQLOperation("mutation(\$input: AddGroupInput!) { addGroup(input: \$input) { group { name } } }")
object AddGroupMutation : MutationFromAnnotation()

@Resolver
class AddGroupComposedResolver(
    private val dbClient: DbClient,
) : MutationResolvers.AddGroupComposed() {
    override suspend fun resolve(ctx: Context): AddGroupPayload =
        dbClient.transaction(ctx) {
            requireNotNull(
                ctx
                    .mutation(AddGroupMutation, mapOf("input" to ctx.arguments.input))
                    .getAddGroupOrThrow(),
            )
        }.value
}

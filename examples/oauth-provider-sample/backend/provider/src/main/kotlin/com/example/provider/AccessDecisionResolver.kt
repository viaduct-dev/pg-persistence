package com.example.provider

import com.example.common.principal
import com.example.common.ClientValidation
import com.example.provider.resolverbases.QueryResolvers
import viaduct.api.grts.AccessDecision
import viaduct.api.resolver.Resolver

@Resolver
class AccessDecisionResolver(private val policy: AccessPolicy) : QueryResolvers.AccessDecision() {
    override suspend fun resolve(ctx: Context): AccessDecision {
        val scopes = ClientValidation.scopes(ctx.arguments.scopes)
        val decision = policy.decide(ctx.principal().id, ctx.arguments.clientId.internalID, scopes)
        return AccessDecision.Builder(ctx).allowed(decision.allowed).scopes(decision.scopes).build()
    }
}

package com.example.database

import com.example.common.*
import dev.viaduct.persistence.runtime.db.*
import dev.viaduct.persistence.runtime.graphql.PgGraphqlExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** All application reads and writes use pg-persistence; JDBC SQL is only used to install generated DDL. */
class PgStore(executor: PgGraphqlExecutor) : Store {
    private val client = PgGraphqlClient(executor)
    private val mutations = PgGraphqlMutationClient(executor)

    override suspend fun list(entity: Entity, filter: JsonObject): List<JsonObject> = withContext(Dispatchers.IO) {
        client.select(PgGraphqlEntity(entity.name), entity.selection, filter).map { it.jsonObject }
    }

    override suspend fun insert(entity: Entity, values: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        mutations.insert(
            PgGraphqlEntity(entity.name), JsonArray(listOf(values)), "affectedCount records { ${entity.selection} }",
        ).getValue("records").jsonArray.single().jsonObject
    }

    override suspend fun delete(entity: Entity, filter: JsonObject): Int = withContext(Dispatchers.IO) {
        mutations.delete(PgGraphqlEntity(entity.name), filter, 1).getValue("affectedCount").jsonPrimitive.int
    }

    override suspend fun update(entity: Entity, filter: JsonObject, values: JsonObject): Int = withContext(Dispatchers.IO) {
        mutations.update(PgGraphqlEntity(entity.name), values, filter, 1).getValue("affectedCount").jsonPrimitive.int
    }
}

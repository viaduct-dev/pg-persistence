package com.example

import com.example.common.*
import com.example.database.*
import com.example.provider.*
import dev.viaduct.persistence.jdbc.JdbcPgGraphqlExecutor
import dev.viaduct.persistence.runtime.db.DbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.Viaduct
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import javax.inject.Provider
import javax.sql.DataSource

class Sample(dataSource: DataSource, val issuer: String, val tokens: Tokens) {
    private val executor = JdbcPgGraphqlExecutor(dataSource)
    val store = PgStore(executor)
    val policy = AccessPolicy(store)
    val oauth = OAuthProvider(store, policy, tokens)
    private val hashAdmission = Semaphore(4)
    private val dummyHash = Passwords.hash("a-dummy-password-for-timing")
    private val db = DbClient(executor)
    private val dependencies: Map<Class<*>, Any> = mapOf(Store::class.java to store, DbClient::class.java to db, AccessPolicy::class.java to policy)
    val viaduct: Viaduct = ViaductBuilder()
        .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(object : CodeInjector {
            override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider {
                val constructor = clazz.constructors.single()
                clazz.cast(constructor.newInstance(*constructor.parameterTypes.map { dependencies.getValue(it) }.toTypedArray()))
            }
        }))
        .withScopedSchemas(listOf(
            SchemaScopeInfo.Scoped("default", setOf("default")),
            SchemaScopeInfo.Scoped("admin", setOf("default", "admin")),
        )).build()

    suspend fun login(username: String, password: String): String? {
        if (!username.matches(Regex("[a-z][a-z0-9_-]{2,31}"))) return null
        val account = store.list(Entity.Account, eq("username", username)).singleOrNull()
        val valid = hashAdmission.withPermit {
            withContext(Dispatchers.IO) { Passwords.verify(password, account?.text("passwordHash") ?: dummyHash) }
        }
        return if (valid && account != null) tokens.session(account.text("uuidId")) else null
    }

    suspend fun principal(session: String): Principal? {
        val id = tokens.sessionSubject(session) ?: return null
        val account = store.list(Entity.Account, eq("uuidId", id)).singleOrNull() ?: return null
        return Principal(id, account.flag("admin"))
    }

    suspend fun bootstrap(username: String, password: String) {
        require(username.matches(Regex("[a-z][a-z0-9_-]{2,31}"))) { "Invalid administrator username" }
        if (store.list(Entity.Account, eq("username", username)).isNotEmpty()) return
        val hash = withContext(Dispatchers.IO) { Passwords.hash(password) }
        store.insert(Entity.Account, values(
            "username" to JsonPrimitive(username), "passwordHash" to JsonPrimitive(hash), "admin" to JsonPrimitive(true),
        ))
    }
}

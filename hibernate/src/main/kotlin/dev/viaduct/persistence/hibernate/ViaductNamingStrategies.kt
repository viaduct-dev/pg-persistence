package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.model.toSnakeCase
import dev.viaduct.persistence.model.toTableName
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl
import org.hibernate.boot.model.naming.PhysicalNamingStrategy
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment

open class ViaductImplicitNamingStrategy : ImplicitNamingStrategyJpaCompliantImpl()

open class ViaductPhysicalNamingStrategy : PhysicalNamingStrategy {
    override fun toPhysicalCatalogName(
        logicalName: Identifier?,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier? = logicalName

    override fun toPhysicalSchemaName(
        logicalName: Identifier?,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier? = logicalName

    override fun toPhysicalTableName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier = logicalName.transform(::toTableName)

    override fun toPhysicalSequenceName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier = logicalName.transform(::toSnakeCase)

    override fun toPhysicalColumnName(
        logicalName: Identifier,
        jdbcEnvironment: JdbcEnvironment,
    ): Identifier =
        logicalName.transform { name ->
            if (name == "internalId") "_uuid_id" else toSnakeCase(name)
        }
}

private fun Identifier.transform(transformer: (String) -> String): Identifier =
    Identifier.toIdentifier(
        transformer(text),
        isQuoted,
    )

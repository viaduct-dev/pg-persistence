@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.orm

import dev.viaduct.persistence.runtime.grt.StoredObject
import org.hibernate.Session
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.type.BasicType
import org.hibernate.type.CollectionType
import org.hibernate.type.EntityType

/** Reads exact mapped property names. Hibernate resolves associations and lazy collections. */
internal class HibernateObject(
    private val session: Session,
    private val entity: Any,
) : StoredObject {
    override val type: String = session.getEntityName(entity)
    private val mapping =
        (session.sessionFactory as SessionFactoryImplementor).mappingMetamodel.getEntityDescriptor(
            type,
        )
    override val id: String get() = session.getIdentifier(entity).toString()

    override fun value(field: String): Any? {
        val index = mapping.propertyNames.indexOf(field)
        require(index >= 0) { "No mapped property $type.$field; nonpersisted fields need a resolver" }
        val value = mapping.getPropertyValue(entity, index) ?: return null
        return when (mapping.propertyTypes[index]) {
            is EntityType -> HibernateObject(session, value)
            is CollectionType -> (value as Iterable<*>).map { HibernateObject(session, requireNotNull(it)) }
            else -> value
        }
    }
}

/** Global IDs carry strings; Hibernate's identifier Java type supplies the native representation. */
internal fun identifier(
    session: Session,
    type: String,
    id: String,
): Any {
    val mapping = (session.sessionFactory as SessionFactoryImplementor).mappingMetamodel.getEntityDescriptor(type)
    return (mapping.identifierType as BasicType<*>).javaTypeDescriptor.fromString(id)
}

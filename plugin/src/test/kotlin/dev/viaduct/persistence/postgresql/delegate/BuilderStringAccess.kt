package dev.viaduct.persistence.postgresql.delegate

import dev.viaduct.persistence.orm.grt.GrtEntity
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.property.access.spi.Getter
import org.hibernate.property.access.spi.PropertyAccess
import org.hibernate.property.access.spi.PropertyAccessStrategy
import org.hibernate.property.access.spi.Setter
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Type

/**
 * Feasibility experiment: remove a generated scalar bean accessor while retaining direct builder
 * hydration. Only public generated methods are resolved; the class cache holds no request values.
 * This is test instrumentation, not a second production mapping implementation.
 */
internal class BuilderStringAccess : PropertyAccessStrategy {
    override fun buildPropertyAccess(
        containerJavaType: Class<*>,
        propertyName: String,
        setterRequired: Boolean,
    ): PropertyAccess {
        require(propertyName == "username")
        val access = handles.get(containerJavaType) // Resolve public methods once at Hibernate bootstrap.
        return object : PropertyAccess {
            override fun getPropertyAccessStrategy(): PropertyAccessStrategy = this@BuilderStringAccess

            override fun getGetter(): Getter = BuilderGetter(access.read)

            override fun getSetter(): Setter = BuilderSetter(access.builder, access.write)
        }
    }

    private class BuilderGetter(
        private val read: MethodHandle,
    ) : Getter {
        override fun get(owner: Any): Any? = read.invoke((owner as GrtEntity<*>).grt())

        // The session is an unused parameter required by Hibernate's public Getter extension contract.
        override fun getForInsert(
            owner: Any,
            mergeMap: MutableMap<Any, Any>?,
            session: SharedSessionContractImplementor?,
        ): Any? = get(owner)

        override fun getReturnTypeClass(): Class<*> = String::class.java

        override fun getReturnType(): Type = String::class.java

        override fun getMember(): Member? = null

        override fun getMethodName(): String? = null

        override fun getMethod(): Method? = null
    }

    private class BuilderSetter(
        private val builder: MethodHandle,
        private val write: MethodHandle,
    ) : Setter {
        override fun set(
            target: Any,
            value: Any?,
        ) {
            write.invoke(builder.invoke(target), value)
        }

        override fun getMethodName(): String? = null

        override fun getMethod(): Method? = null
    }

    private class Handles(
        val read: MethodHandle,
        val builder: MethodHandle,
        val write: MethodHandle,
    )

    private companion object {
        private val handles =
            object : ClassValue<Handles>() {
                override fun computeValue(type: Class<*>): Handles {
                    val lookup = MethodHandles.publicLookup()
                    val builder = type.getMethod("hydrationBuilder")
                    val read = type.getMethod("grt").returnType.getMethod("getUsername")
                    require(read.returnType == String::class.java)
                    return Handles(
                        lookup.unreflect(read),
                        lookup.unreflect(builder),
                        lookup.unreflect(builder.returnType.getMethod("username", String::class.java)),
                    )
                }
            }
    }
}

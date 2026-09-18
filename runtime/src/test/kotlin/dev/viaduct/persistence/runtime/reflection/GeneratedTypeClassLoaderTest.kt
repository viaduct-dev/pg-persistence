package dev.viaduct.persistence.runtime.reflection

import dev.viaduct.persistence.runtime.db.AbstractSubject
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import kotlin.test.assertSame

class GeneratedTypeClassLoaderTest {
    @Test
    fun `concrete types use the same loader as the declared GRT and its metadata`() {
        val loader =
            IsolatedGrtLoader(
                AbstractSubject::class.java,
                listOf(
                    "AbstractSubject",
                    "AbstractActor",
                    "AbstractPerson",
                    "AbstractGroup",
                    "AbstractFixtureType",
                    "AbstractFixtureField",
                ),
            )
        val declared =
            loader.loadClass(AbstractSubject::class.java.name + "\$Reflection").getField("INSTANCE").get(null)
                as Type<*>
        val concrete = GeneratedTypeReflection().concreteType(declared, "AbstractPerson")
        assertSame(loader, concrete.kcls.java.classLoader)
    }
}

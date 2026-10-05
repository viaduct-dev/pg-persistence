package dev.viaduct.persistence.runtime.reflection

import dev.viaduct.persistence.runtime.db.AbstractSubject
import dev.viaduct.persistence.runtime.db.SubjectPayload
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GeneratedTypeClassLoaderTest {
    @Test
    fun `metadata is reused within a defining class and isolated across loaders`() {
        fun payloadType(): Type<*> {
            val loader =
                IsolatedGrtLoader(
                    SubjectPayload::class.java,
                    listOf(
                        "SubjectPayload",
                        "AbstractSubject",
                        "AbstractActor",
                        "AbstractPerson",
                        "AbstractGroup",
                        "AbstractFixtureType",
                        "AbstractFixtureField",
                    ),
                )
            return loader.loadClass(SubjectPayload::class.java.name + "\$Reflection").getField("INSTANCE").get(null)
                as Type<*>
        }
        val first = payloadType()
        val second = payloadType()
        val reflection = GeneratedTypeReflection()
        val schema = reflection.translationSchema(first)
        val fields = reflection.fieldReflection.allFields(first)
        assertEquals(
            List(6) { true },
            listOf(
                schema === reflection.translationSchema(first),
                schema !== reflection.translationSchema(second),
                fields === reflection.fieldReflection.allFields(first),
                fields !== reflection.fieldReflection.allFields(second),
                first.kcls.java.classLoader ===
                    fields
                        .single()
                        .containingType.kcls.java.classLoader,
                second.kcls.java.classLoader ===
                    reflection.fieldReflection
                        .allFields(second)
                        .single()
                        .containingType.kcls.java.classLoader,
            ),
            "Cache hits must reuse identity; definitions from distinct loaders must stay isolated",
        )
    }

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

@file:OptIn(viaduct.apiannotations.InternalApi::class)

package dev.viaduct.persistence.postgresql

import javassist.ClassPool
import javassist.CtConstructor
import javassist.LoaderClassPath
import viaduct.api.context.MutationFieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.types.CompositeOutput

/**
 * Gives the runtime-generated payload a concrete mutation context signature, just as a generated
 * resolver context does. Node references and builders use Viaduct's test context implementation;
 * no payload, database response, or mutation result is stubbed.
 */
@Suppress("UNCHECKED_CAST")
internal fun typedMutationContext(
    payload: Class<*>,
    context: InternalContext,
): MutationFieldExecutionContext<*, *, *, CompositeOutput> {
    val pool = ClassPool(true).apply { insertClassPath(LoaderClassPath(payload.classLoader)) }
    val base = "viaduct.api.mocks.MockResolverExecutionContext"
    val api = "viaduct.api.context.MutationFieldExecutionContext"
    val generated = pool.makeClass(payload.name + "Context", pool.get(base))
    try {
        generated.addInterface(pool.get(api))
        val types = "viaduct/api/types/"
        generated.genericSignature =
            "L${base.replace('.', '/')}<L${types}Query;>;" +
            "L${api.replace('.', '/')}<L${types}Query;L${types}Mutation;L${types}Arguments;" +
            "L${payload.name.replace('.', '/')};>;"
        generated.addConstructor(
            CtConstructor(arrayOf(pool.get(InternalContext::class.java.name)), generated).apply {
                setBody("{ super(\$1, null, null, null, 14, null); }")
            },
        )
        val bytes = generated.toBytecode()
        val defined =
            object : ClassLoader(payload.classLoader) {
                fun define(): Class<*> = defineClass(generated.name, bytes, 0, bytes.size)
            }.define()
        return defined.getConstructor(InternalContext::class.java).newInstance(context)
            as MutationFieldExecutionContext<*, *, *, CompositeOutput>
    } finally {
        generated.detach()
    }
}

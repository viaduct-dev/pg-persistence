package dev.viaduct.persistence.runtime.reflection

/** Loads only the named fixture GRTs separately; Viaduct and persistence APIs stay in the parent. */
internal class IsolatedGrtLoader(
    anchor: Class<*>,
    fixtureNames: List<String>,
) : ClassLoader(anchor.classLoader) {
    private val fixturePackage = anchor.packageName
    private val fixtures = java.util.List.copyOf(fixtureNames)

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            if (fixtures.none { name == "$fixturePackage.$it" || name.startsWith("$fixturePackage.$it\$") }) {
                super.loadClass(name, resolve)
            } else {
                val loaded =
                    findLoadedClass(name) ?: parent
                        .getResourceAsStream(name.replace('.', '/') + ".class")
                        .use { stream ->
                            val bytes = requireNotNull(stream).readBytes()
                            defineClass(name, bytes, 0, bytes.size)
                        }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
}

@file:OptIn(
    viaduct.apiannotations.ExperimentalApi::class,
    viaduct.apiannotations.InternalApi::class,
)

package dev.viaduct.persistence.runtime.reflection
import dev.viaduct.persistence.pggraphql.translation.PgGraphqlTranslationSchema
import dev.viaduct.persistence.runtime.connection.ConnectionShape
import dev.viaduct.persistence.runtime.connection.ConnectionShapeFactory
import dev.viaduct.persistence.runtime.connection.ConnectionStorageClassifier
import viaduct.api.internal.ConnectionBuilder
import viaduct.api.reflect.Type

/** Reflection helpers for the generated Viaduct types used by the persistence runtime. */
internal class GeneratedTypeReflection {
    internal val fieldReflection = GeneratedFieldReflection()
    private val storageClassifier =
        ConnectionStorageClassifier(fieldReflection, ::legacyCollectionNodeType)
    private val connectionShapeFactory = ConnectionShapeFactory(fieldReflection, storageClassifier)
    private val translationSchemaFactory =
        GeneratedTranslationSchemaFactory(this, fieldReflection, storageClassifier)

    /**
     * Returns the element type of the compatibility `nodes` field on a generated Viaduct
     * connection. A normal domain object can also have a field named `nodes`; the generated
     * connection builder is the convention that distinguishes the two.
     */
    fun legacyCollectionNodeType(type: Type<*>): Type<*>? {
        if (!isConnectionBuilder(type) || fieldReflection.field(type, "edges") != null) return null
        return fieldReflection.field(type, "nodes")?.type
    }

    fun collectionNodeType(type: Type<*>): Type<*>? = resolveCollectionNodeType(type)

    private fun resolveCollectionNodeType(type: Type<*>): Type<*>? {
        val connectionNodeType = connection(type)?.nodeField?.type
        return connectionNodeType ?: legacyCollectionNodeType(type)
    }

    /**
     * Builds the translator's type map from generated Viaduct reflection, supplemented by the
     * generated persistence mapping for unions, interfaces, and their stored relationships.
     */
    fun translationSchema(rootType: Type<*>): PgGraphqlTranslationSchema = translationSchemaFactory.build(rootType)

    /**
     * Finds the conventional Viaduct connection shape: `edges` containing an object with a
     * `node` field. The shape is deliberately structural so the runtime does not need schema
     * metadata to recognize connections.
     */
    fun connection(
        type: Type<*>,
        requestedSelections: viaduct.api.select.SelectionSet<*>? = null,
        ownerType: Type<*>? = null,
    ): ConnectionShape? = connectionShapeFactory.create(type, requestedSelections, ownerType)

    private fun isConnectionBuilder(type: Type<*>): Boolean =
        runCatching {
            ConnectionBuilder::class.java.isAssignableFrom(builderClass(type))
        }.getOrDefault(false)

    fun builderClass(type: Type<*>): Class<*> {
        val grtClass = type.kcls.java
        return Class.forName("${grtClass.name}\$Builder", true, grtClass.classLoader)
    }

    /** Resolves a response's __typename, requiring a concrete member of the declared type. */
    fun concreteType(
        declared: Type<*>,
        typeName: String?,
    ): Type<*> {
        val name = typeName ?: declared.name
        val mappings = AbstractTypeMappings.load(declared.kcls.java.classLoader)
        require(mappings.accepts(declared.name, name)) { "$name is not a possible type of ${declared.name}" }
        val concrete = if (name == declared.name) declared else reflectedType(declared, name)
        require(!concrete.kcls.java.isInterface) { "Missing concrete __typename for ${declared.name}" }
        return concrete
    }

    fun reflectedType(
        collectionType: Type<*>,
        elementTypeName: String,
    ): Type<*> {
        val reflectionClass =
            Class.forName(
                "${collectionType.kcls.java.packageName}.$elementTypeName\$Reflection",
                true,
                collectionType.kcls.java.classLoader,
            )
        return reflectionClass.getField("INSTANCE").get(null) as Type<*>
    }
}

package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.io.ensureDirectory
import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceModel
import dev.viaduct.persistence.model.PersistenceToManyAttribute
import dev.viaduct.persistence.model.PersistenceToOneAttribute
import graphql.language.ListType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

/** Generates typed Hibernate delegates using only public GRT getters and builders. */
class GrtDelegateGenerator {
    fun write(
        model: PersistenceModel,
        grtPackage: String,
        schemaFiles: List<File>,
        output: File,
    ) {
        require(grtPackage.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Invalid GRT package" }
        val registry = TypeDefinitionRegistry()
        schemaFiles.forEach { registry.merge(SchemaParser().parse(it)) }
        validateDelegateModel(model, registry)
        val shapes = delegateShapes(model, registry).associateBy { it.name }
        val mappings = PersistenceModelToHbmMapper.map(model).entities.associateBy { it.entityName }
        val connections = GrtConnectionGenerator().sources(model, registry, grtPackage)
        // Render everything before replacing outputs; a failed build leaves no partial generation.
        val sources =
            shapes.values.associate { shape ->
                "${shape.className}.kt" to
                    if (shape.grtName == null) {
                        storageSource(shape, mappings.getValue(shape.name), shapes, grtPackage)
                    } else {
                        render(shape, model, registry, mappings.getValue(shape.name), shapes, grtPackage)
                    }
            }
        val entry = registrySource(shapes.values.toList(), grtPackage, connections.keys.toList())
        val registryEntry = "PersistenceDelegates.kt" to entry
        output.deleteRecursively()
        val directory = output.resolve(grtPackage.replace('.', '/') + "/persistence").apply { ensureDirectory() }
        (sources + connections + registryEntry).forEach { (name, source) -> directory.resolve(name).writeText(source) }
    }

    @Suppress("LongMethod", "LongParameterList") // Complete class template; relationship rendering is separate.
    private fun render(
        shape: GrtDelegateShape,
        model: PersistenceModel,
        registry: TypeDefinitionRegistry,
        mapping: HbmEntityMapping,
        shapes: Map<String, GrtDelegateShape>,
        grtPackage: String,
    ): String {
        val name = requireNotNull(shape.grtName)
        val entity = shape.entity
        val schema = schemaFields(registry, name).associateBy { it.name }
        val basics =
            entity.attributes
                .filterIsInstance<PersistenceBasicAttribute>()
                .filter { it.name !in setOf("id", "internalId") }
        val ones = entity.attributes.filterIsInstance<PersistenceToOneAttribute>()
        val many = entity.attributes.filterIsInstance<PersistenceToManyAttribute>()
        val lists =
            schema.values
                .filter { it.type.unwrapNonNull() is ListType }
                .map { it.name }
                .toSet()
        val aliases = idAliases(entity, registry)
        val concrete = ones.filter { it.name in schema }
        val fields =
            basics.map { it.name } + concrete.map { it.name } + aliases.values +
                shape.abstractReferences.map { it.fieldName } + many.filter { it.name in lists }.map { it.name } +
                listOfNotNull("id".takeIf { it in schema })
        val references =
            concrete.filterNot { it.idOfDirected }.map { it.name } + shape.abstractReferences.map { it.fieldName } +
                many.filter { it.name in lists }.map { it.name }
        val relationships = GrtDelegateRelationships(shapes)
        val owning =
            mapping.attributes.filterIsInstance<HbmToOneMapping>().filter { mapped ->
                entity.attributes.none {
                    it.name ==
                        mapped.name
                }
            }
        val properties =
            basics.map { scalarProperty(it, grtPackage) } + ones.map(relationships::property) +
                many.map { toMany(it, shapes) } +
                owning.map { "    open var `${it.name}`: ${shapes.getValue(it.targetEntityName).className}? = null" }
        val snapshot =
            concrete.map { relationships.snapshot(it, aliases[it.name]) } +
                shape.abstractReferences.map {
                    "        __result.`${it.fieldName}`(${relationships.abstractValue(it, "executionContext()")})"
                }
        val selected =
            basics.map {
                val read = required(grtGetter(it.name), it.nullable)
                "        if (\"${it.name}\" in fields) result.`${it.name}`($read)"
            } +
                concrete.flatMap {
                    listOf(relationships.selected(it)) +
                        listOfNotNull(aliases[it.name]?.let { alias -> relationships.selected(it, alias) })
                } +
                shape.abstractReferences.map {
                    val read = relationships.abstractValue(it, "context", true)
                    "        if (\"${it.fieldName}\" in fields) result.`${it.fieldName}`($read)"
                } +
                many.filter { it.name in lists }.map {
                    relationships.collection(
                        shape,
                        it,
                        model.abstractTypes.relationship(shape.name, it.name),
                    )
                }
        val binding = if (shape.node) "NodeGrtBinding" else "GrtBinding"
        val identity = identitySource(shape, name, "id" in schema)
        val readIdentity = identityReader(shape, "id" in schema)
        val idSelect =
            if ("id" in schema) {
                "        if (\"id\" in fields) result.id(requireNotNull(grt().getId()))"
            } else {
                ""
            }
        val resolutions =
            concrete.map { relationships.resolve(it) + validateAlias(it, aliases[it.name]) } +
                shape.abstractReferences.map(relationships::resolveAbstract)
        val assignmentFields =
            concrete.map { it.name } +
                shape.abstractReferences.flatMap { reference ->
                    reference.targets.map(reference::targetField)
                }
        return """
package $grtPackage.persistence

import dev.viaduct.persistence.orm.grt.*
import org.hibernate.Session
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.select.SelectionSet
import viaduct.api.types.Query
import $grtPackage.*
import $grtPackage.$name
import $grtPackage.$name as GRT

/** Scalars live in the concrete GRT; Hibernate owns identity and associations. */
open class ${shape.className}() : ${if (shape.node) "NodeGrtEntity" else "GrtEntity"}<$name>() {
    override fun grtBinding() = BINDING
    private var __current: $name? = null
    private var __pending: $name.Builder? = null
    private var __relationshipsChanged = false
    private val __builder get() = __pending ?: (__current?.toBuilder() ?: $name.Builder(executionContext())).also { __pending = it }

    override fun grt(): $name {
        if (__pending == null && !__relationshipsChanged) return checkNotNull(__current)
        val __result = __builder
${snapshot.joinToString("\n")}
        return __result.build().also { __current = it; __pending = null; __relationshipsChanged = false }
    }

$identity

${properties.joinToString("\n\n")}

    override fun assign(value: $name, session: Session) {
        validateIdentity(value)
${many.filter { it.name in schema }.joinToString(
            "\n",
        ) {
            "        require(!isGrtFieldSet { ${grtGetter(
                it.name,
                "value",
            )} }) { \"Update ${it.name} through native Hibernate ownership\" }"
        }}
${basics.joinToString(
            "\n",
        ) {
            "        " +
                if (it.nullable) {
                    scalarRead(
                        it,
                        "value",
                    )
                } else {
                    "require(${scalarRead(it, "value")} != null) { \"${it.name} cannot be null\" }"
                }
        }}
${resolutions.joinToString("\n")}
        acceptIdentity(value)
        __current = value
        __pending = null
${assignmentFields.joinToString("\n") { "        `$it` = resolved_$it" }}
    }

    override fun selected(context: ResolverExecutionContext<out Query>, session: Session, fields: Set<String>, selections: SelectionSet<$name>?): $name {
        val result = $name.Builder(context)
$idSelect
${selected.joinToString("\n")}
        return result.build()
    }

    companion object {
        val BINDING = $binding(
            type = $name.Reflection,
            entityClass = ${shape.className}::class.java,
            newEntity = ::${shape.className},
            $readIdentity,
            fields = listOf(${fields.distinct().joinToString { "$name.Fields.`$it`" }}),
            references = setOf<String>(${references.distinct().joinToString { "\"$it\"" }}),
            ${if (shape.node) "" else "entityName = \"${shape.name}\","}
            ${if (shape.node || "id" in schema) "" else "hasGraphqlIdentity = false,"}
        )
    }
}
""".trimStart()
    }

    private fun registrySource(
        shapes: List<GrtDelegateShape>,
        grtPackage: String,
        connections: List<String>,
    ): String =
        """
package $grtPackage.persistence

import dev.viaduct.persistence.orm.grt.GrtBindings

/** Apply to runtime metadata before building a SessionFactory; schema generation stays unchanged. */
object PersistenceDelegates {
    val bindings = GrtBindings(
        listOf(${shapes.filter { it.grtName != null }.joinToString { "${it.className}.BINDING" }}),
        listOf(${connections.joinToString { "${it.removeSuffix(".kt")}.binding" }}),
        listOf(${shapes.filter { it.grtName == null }.joinToString { "${it.className}.BINDING" }}),
    )
}
""".trimStart()
}

private fun toMany(
    field: PersistenceToManyAttribute,
    shapes: Map<String, GrtDelegateShape>,
): String {
    val target = shapes.getValue(field.targetTypeName).className
    return "    open var `${field.name}`: MutableList<$target> = arrayListOf()"
}

private fun identitySource(
    shape: GrtDelegateShape,
    name: String,
    schemaIdentity: Boolean,
): String =
    when {
        shape.node -> "    override fun setGlobalId(id: GlobalID<$name>) { __builder.id(id) }"
        schemaIdentity ->
            """
    override fun setIdentity(id: java.util.UUID) { __builder.id(id.toString()) }
    open var id: java.util.UUID?
        get() = internalId
        set(value) { internalId = value }
""".trimEnd()
        else ->
            "    override fun setIdentity(id: java.util.UUID) = Unit // Storage identity only." +
                if (shape.entity.attributes.any { it.name == "id" }) "\n    open var id: String? = null" else ""
    }

private fun identityReader(
    shape: GrtDelegateShape,
    schemaIdentity: Boolean,
): String =
    when {
        shape.node -> "readId = { if (isGrtFieldSet { it.getId() }) it.getId() else null }"
        schemaIdentity ->
            "readIdentity = { if (isGrtFieldSet { it.getId() }) " +
                "it.getId()?.let(java.util.UUID::fromString) else null }"
        else -> "readIdentity = { null }"
    }

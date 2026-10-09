package dev.viaduct.persistence.hibernate

import dev.viaduct.persistence.io.ensureDirectory
import dev.viaduct.persistence.model.PersistenceBasicAttribute
import dev.viaduct.persistence.model.PersistenceEntity
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
        val mappings = PersistenceModelToHbmMapper.map(model).entities.associateBy { it.entityName }
        val connections = GrtConnectionGenerator().sources(model, registry, grtPackage)
        // Validate/render everything before replacing outputs; unsupported shapes leave no partial build.
        val sources =
            model.entities.associate { entity ->
                val lists = listFields(registry, entity)
                val owning =
                    mappings
                        .getValue(entity.graphqlName)
                        .attributes
                        .filterIsInstance<HbmToOneMapping>()
                        .filter { mapped -> entity.attributes.none { it.name == mapped.name } }
                val aliases = idAliases(entity, registry)
                "${entity.graphqlName}Entity.kt" to render(entity, grtPackage, lists, owning, aliases)
            }
        val registryEntry = "PersistenceDelegates.kt" to registrySource(model, grtPackage, connections.keys.toList())
        val allSources = sources + connections + registryEntry
        output.deleteRecursively()
        val directory = output.resolve(grtPackage.replace('.', '/') + "/persistence").apply { ensureDirectory() }
        allSources.forEach { (name, source) -> directory.resolve(name).writeText(source) }
    }

    @Suppress("LongMethod") // The complete generated class template stays together; methods are small.
    private fun render(
        entity: PersistenceEntity,
        grtPackage: String,
        lists: Set<String>,
        owning: List<HbmToOneMapping>,
        aliases: Map<String, String>,
    ): String {
        val name = entity.graphqlName
        val basics =
            entity.attributes
                .filterIsInstance<PersistenceBasicAttribute>()
                .filter { it.name !in setOf("id", "internalId") }
        val ones = entity.attributes.filterIsInstance<PersistenceToOneAttribute>()
        val many = entity.attributes.filterIsInstance<PersistenceToManyAttribute>()
        val fields =
            entity.attributes
                .filter {
                    it.name != "internalId" && (it !is PersistenceToManyAttribute || it.name in lists)
                }.map { it.name } + aliases.values
        val references =
            ones.filterNot { it.idOfDirected }.map { it.name } +
                many.filter { it.name in lists }.map { it.name }
        val properties =
            basics.map { scalarProperty(it, grtPackage) } + ones.map { toOne(it, aliases[it.name]) } +
                many.map(::toMany) + owning.map(::syntheticOwner)
        val selected =
            basics.map(::selectedScalar) +
                ones.flatMap {
                    listOf(selectedReference(it, it.name, it.idOfDirected)) +
                        listOfNotNull(aliases[it.name]?.let { alias -> selectedReference(it, alias, true) })
                } + many.filter { it.name in lists }.map { selectedCollection(name, it) }
        val fieldCoordinates = fields.distinct().joinToString { "$name.Fields.${quote(it)}" }
        return """
package $grtPackage.persistence

import dev.viaduct.persistence.orm.grt.GrtBinding
import dev.viaduct.persistence.orm.grt.GrtEntity
import dev.viaduct.persistence.orm.grt.isGrtFieldSet
import org.hibernate.Session
import viaduct.api.context.ResolverExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.types.Query
import $grtPackage.$name

/** Scalars live in the GRT; only native identity and Hibernate relationships are sidecars. */
open class ${name}Entity() : GrtEntity<$name>() {
    override val binding get() = BINDING
    private var current: $name? = null
    private var pending: $name.Builder? = null
    private val builder get() = pending ?: (current?.toBuilder() ?: $name.Builder(context)).also { pending = it }

    override val value: $name
        get() {
            val staged = pending ?: return checkNotNull(current)
            return staged.build().also { current = it; pending = null }
        }

    override fun setGlobalId(id: GlobalID<$name>) { builder.id(id) }

${properties.joinToString("\n\n")}

    override fun assign(value: $name, session: Session) {
        validateIdentity(value)
${many.joinToString("\n") { rejectCollection(it.name) }}
${basics.joinToString("\n") { "        " + validateScalar(it) }}
${ones.joinToString("\n") { resolveAssociation(it) + validateAlias(it, aliases[it.name]) }}
        acceptIdentity(value)
        current = value
        pending = null
${ones.joinToString("\n") { "        ${quote(it.name)} = resolved_${it.name}" }}
    }

    override fun selected(context: ResolverExecutionContext<out Query>, session: Session, fields: Set<String>): $name {
        val result = $name.Builder(context)
        if ("id" in fields) result.id(requireNotNull(value.getId()))
${selected.joinToString("\n")}
        return result.build()
    }

    companion object {
        val BINDING = GrtBinding(
            type = $name.Reflection,
            entityClass = ${name}Entity::class.java,
            newEntity = ::${name}Entity,
            readId = { if (isGrtFieldSet { it.getId() }) it.getId() else null },
            fields = listOf($fieldCoordinates),
            references = ${set(references)},
        )
    }
}
""".trimStart()
    }

    private fun toOne(
        field: PersistenceToOneAttribute,
        alias: String?,
    ): String {
        val target = "${field.targetTypeName}Entity"
        val id = "globalId($target.BINDING, it)"
        val ref = if (field.idOfDirected) id else "context.ref($id)"
        val aliasValue = required("value?.let { $id }", field.nullable)
        val aliasWrite = alias?.let { "\n            builder.${quote(it)}($aliasValue)" }.orEmpty()
        return """
    private var native_${field.name}: $target? = null
    open var ${quote(field.name)}: $target?
        get() = native_${field.name}
        set(value) {
            builder.${quote(field.name)}(${required("value?.let { $ref }", field.nullable)})$aliasWrite
            native_${field.name} = value
        }
""".trimEnd()
    }

    private fun syntheticOwner(field: HbmToOneMapping): String =
        """
    // Owning FK for an unidirectional schema collection; this is not a GRT field.
    open var ${quote(field.name)}: ${field.targetEntityName}Entity? = null
""".trimEnd()

    private fun resolveAssociation(field: PersistenceToOneAttribute): String {
        val read = getter(field.name)
        val id = if (field.idOfDirected) read else "$read?.let { requireNotNull(it.getId()) }"
        val check = if (field.nullable) "" else "\n        require(resolved_${field.name} != null)"
        return "        val resolved_${field.name} = " +
            "association(session, ${field.targetTypeName}Entity.BINDING, $id) " +
            "as ${field.targetTypeName}Entity?$check"
    }

    private fun validateScalar(field: PersistenceBasicAttribute): String {
        val read = scalarRead(field)
        return if (field.nullable) read else "require($read != null) { \"${field.name} cannot be null\" }"
    }

    private fun selectedReference(
        field: PersistenceToOneAttribute,
        name: String,
        idOnly: Boolean,
    ): String {
        val id = "globalId(${field.targetTypeName}Entity.BINDING, it)"
        val ref = if (idOnly) id else "context.ref($id)"
        return "        if (\"$name\" in fields) result.${quote(name)}(" +
            required("${quote(field.name)}?.let { $ref }", field.nullable) + ")"
    }

    private fun registrySource(
        model: PersistenceModel,
        grtPackage: String,
        connections: List<String>,
    ): String =
        """
package $grtPackage.persistence

import dev.viaduct.persistence.orm.grt.GrtBindings

/** Apply to runtime metadata before building a SessionFactory; schema generation stays unchanged. */
object PersistenceDelegates {
    val bindings = GrtBindings(
        listOf(${model.entities.joinToString { "${it.graphqlName}Entity.BINDING" }}),
        listOf(${connections.joinToString { "${it.removeSuffix(".kt")}.binding" }}),
    )
}
""".trimStart()
}

private fun getter(name: String): String = grtGetter(name)

private fun required(
    value: String,
    nullable: Boolean,
): String = if (nullable) value else "requireNotNull($value)"

private fun quote(name: String): String = "`$name`"

private fun set(names: List<String>): String = "setOf<String>(${names.joinToString { "\"$it\"" }})"

private fun listFields(
    registry: TypeDefinitionRegistry,
    entity: PersistenceEntity,
): Set<String> =
    schemaFields(registry, entity.graphqlName)
        .filter { it.type.unwrapNonNull() is ListType }
        .map { it.name }
        .toSet()

private fun toMany(field: PersistenceToManyAttribute): String =
    """
private var native_${field.name}: MutableList<${field.targetTypeName}Entity> = arrayListOf()
open var ${quote(field.name)}: MutableList<${field.targetTypeName}Entity>
    get() = native_${field.name}
    set(value) { native_${field.name} = value }
""".trimEnd()

private fun selectedScalar(field: PersistenceBasicAttribute): String =
    "        if (\"${field.name}\" in fields) result.${quote(field.name)}(" +
        required(getter(field.name), field.nullable) + ")"

private fun selectedCollection(
    owner: String,
    field: PersistenceToManyAttribute,
): String =
    """
        if ("${field.name}" in fields) {
            val ids = session.createSelectionQuery(
                "select child.internalId from $owner parent join parent.${field.name} child where parent.internalId = :id",
                java.util.UUID::class.java,
            ).setParameter("id", internalId).resultList
            result.${quote(
        field.name,
    )}(ids.map { context.ref(context.globalIDFor(${field.targetTypeName}Entity.BINDING.type, it.toString())) })
        }
""".trimEnd()

private fun rejectCollection(name: String): String =
    "        require(!isGrtFieldSet { ${getter(name)} }) { \"Update $name through native Hibernate ownership\" }"

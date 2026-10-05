package dev.viaduct.persistence.runtime.reflection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.WeakHashMap

/** Generated persistence mapping for application unions, interfaces, and their stored references. */
@Serializable(with = AbstractTypeMappingsSerializer::class)
class AbstractTypeMappings(
    val version: Int = 1,
    possibleTypes: Map<String, Set<String>> = emptyMap(),
    relationships: List<AbstractRelationship> = emptyList(),
) {
    val possibleTypes: Map<String, Set<String>> =
        java.util.Collections.unmodifiableMap(
            possibleTypes.mapValues { (_, types) ->
                java.util.Collections.unmodifiableSet(LinkedHashSet(types))
            },
        )
    val relationships: List<AbstractRelationship> = java.util.List.copyOf(relationships)

    init {
        require(version == 1) { "Unsupported abstract persistence mapping version: $version" }
    }

    private val relationshipsByCoordinate =
        buildMap {
            this@AbstractTypeMappings.relationships.forEach { relationship ->
                val coordinate = relationship.ownerType to relationship.fieldName
                require(put(coordinate, relationship) == null) {
                    "Duplicate abstract relationship ${relationship.ownerType}.${relationship.fieldName}"
                }
                require(relationship.targets.isNotEmpty()) {
                    "Abstract relationship ${relationship.ownerType}.${relationship.fieldName} has no concrete targets"
                }
                require(this@AbstractTypeMappings.possibleTypes[relationship.abstractType] == relationship.targets) {
                    "Abstract relationship ${relationship.ownerType}.${relationship.fieldName} targets do not match " +
                        "the possible types of ${relationship.abstractType}"
                }
                require(
                    (relationship.connectionType == null) == (relationship.edgeType == null) &&
                        (relationship.connectionType == null || relationship.collection),
                ) { "Invalid connection mapping for ${relationship.ownerType}.${relationship.fieldName}" }
            }
        }

    fun accepts(
        declared: String,
        concrete: String,
    ): Boolean = declared == concrete || concrete in possibleTypes[declared].orEmpty()

    fun relationship(
        owner: String,
        field: String,
    ): AbstractRelationship? = relationshipsByCoordinate[owner to field]

    fun encode(): String = Json.encodeToString(serializer(), this)

    fun copy(
        version: Int = this.version,
        possibleTypes: Map<String, Set<String>> = this.possibleTypes,
        relationships: List<AbstractRelationship> = this.relationships,
    ): AbstractTypeMappings = AbstractTypeMappings(version, possibleTypes, relationships)

    operator fun component1(): Int = version

    operator fun component2(): Map<String, Set<String>> = possibleTypes

    operator fun component3(): List<AbstractRelationship> = relationships

    override fun equals(other: Any?): Boolean =
        other is AbstractTypeMappings &&
            version == other.version &&
            possibleTypes == other.possibleTypes &&
            relationships == other.relationships

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + possibleTypes.hashCode()
        result = 31 * result + relationships.hashCode()
        return result
    }

    override fun toString(): String =
        "AbstractTypeMappings(" +
            "version=$version, " +
            "possibleTypes=$possibleTypes, " +
            "relationships=$relationships)"

    companion object {
        const val RESOURCE = "META-INF/viaduct-persistence-abstract-types.json"
        private val cache = WeakHashMap<ClassLoader, AbstractTypeMappings>()

        fun load(loader: ClassLoader): AbstractTypeMappings =
            synchronized(cache) {
                cache.getOrPut(loader) {
                    val mappings =
                        loader
                            .getResources(RESOURCE)
                            .toList()
                            .map { url ->
                                Json.decodeFromString(serializer(), url.readText())
                            }.distinct()
                    require(mappings.size <= 1) {
                        "Conflicting abstract persistence mappings; use a classloader scoped to one persistence module"
                    }
                    mappings.singleOrNull() ?: AbstractTypeMappings()
                }
            }
    }
}

/** A reference is stored as one nullable foreign key for each allowed concrete target. */
@Serializable(with = AbstractRelationshipSerializer::class)
@Suppress("TooManyFunctions", "LongParameterList") // Preserve the data-class copy and destructuring API.
class AbstractRelationship(
    val ownerType: String,
    val fieldName: String,
    val abstractType: String,
    targets: Set<String>,
    val nullable: Boolean,
    val collection: Boolean = false,
    val connectionType: String? = null,
    val edgeType: String? = null,
) {
    val targets: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(targets))

    fun copy(
        ownerType: String = this.ownerType,
        fieldName: String = this.fieldName,
        abstractType: String = this.abstractType,
        targets: Set<String> = this.targets,
        nullable: Boolean = this.nullable,
        collection: Boolean = this.collection,
        connectionType: String? = this.connectionType,
        edgeType: String? = this.edgeType,
    ): AbstractRelationship =
        AbstractRelationship(
            ownerType,
            fieldName,
            abstractType,
            targets,
            nullable,
            collection,
            connectionType,
            edgeType,
        )

    operator fun component1(): String = ownerType

    operator fun component2(): String = fieldName

    operator fun component3(): String = abstractType

    operator fun component4(): Set<String> = targets

    operator fun component5(): Boolean = nullable

    operator fun component6(): Boolean = collection

    operator fun component7(): String? = connectionType

    operator fun component8(): String? = edgeType

    override fun equals(other: Any?): Boolean =
        other is AbstractRelationship &&
            ownerType == other.ownerType &&
            fieldName == other.fieldName &&
            abstractType == other.abstractType &&
            targets == other.targets &&
            nullable == other.nullable &&
            collection == other.collection &&
            connectionType == other.connectionType &&
            edgeType == other.edgeType

    override fun hashCode(): Int {
        var result = ownerType.hashCode()
        result = 31 * result + fieldName.hashCode()
        result = 31 * result + abstractType.hashCode()
        result = 31 * result + targets.hashCode()
        result = 31 * result + nullable.hashCode()
        result = 31 * result + collection.hashCode()
        result = 31 * result + (connectionType?.hashCode() ?: 0)
        result = 31 * result + (edgeType?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AbstractRelationship(" +
            "ownerType=$ownerType, " +
            "fieldName=$fieldName, " +
            "abstractType=$abstractType, " +
            "targets=$targets, " +
            "nullable=$nullable, " +
            "collection=$collection, " +
            "connectionType=$connectionType, " +
            "edgeType=$edgeType)"

    val rowType: String get() = ownerType + fieldName.replaceFirstChar(Char::uppercaseChar) + "Reference"
    val storageOwner: String get() = if (collection) rowType else ownerType

    fun targetField(type: String): String = (if (collection) "node" else fieldName) + type

    fun targetIdField(type: String): String = targetField(type) + "Id"
}

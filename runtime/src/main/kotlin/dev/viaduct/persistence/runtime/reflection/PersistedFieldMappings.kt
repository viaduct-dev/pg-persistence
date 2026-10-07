package dev.viaduct.persistence.runtime.reflection

import org.w3c.dom.Element
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import java.util.WeakHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Optional lookup validation against the existing generated Hibernate mapping. */
internal object PersistedFieldMappings {
    private const val RESOURCE = "META-INF/viaduct-persistence.hbm.xml"
    private val cache = WeakHashMap<ClassLoader, Map<String, Set<String>>>()

    fun requireType(type: Type<*>) {
        val stored = load(type) ?: return
        require(type.name in stored) { "Lookup type ${type.name} is not persisted" }
    }

    fun requireField(field: Field<*>) {
        val stored = load(field.containingType) ?: return
        require(field.name in stored[field.containingType.name].orEmpty()) {
            "Lookup field ${field.containingType.name}.${field.name} is not persisted"
        }
    }

    private fun load(type: Type<*>): Map<String, Set<String>>? {
        val loader = type.kcls.java.classLoader
        val cached = synchronized(cache) { cache[loader] }
        if (cached != null) return cached
        // Keep resource parsing outside the lock; values retain no classloader references.
        val stored = read(loader)
        return if (stored == null) null else synchronized(cache) { cache.getOrPut(loader) { stored } }
    }

    private fun read(loader: ClassLoader): Map<String, Set<String>>? {
        val resources = loader.getResources(RESOURCE).toList()
        if (resources.isEmpty()) return null
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
        return buildMap {
            resources.forEach { resource ->
                val document = resource.openStream().use { factory.newDocumentBuilder().parse(it) }
                val entities = document.getElementsByTagNameNS("*", "class")
                repeat(entities.length) { index ->
                    val entity = entities.item(index) as Element
                    val name = entity.getAttribute("entity-name")
                    val fields = fieldNames(entity)
                    val previous = put(name, fields)
                    require(previous == null || previous == fields) { "Conflicting persistence mappings for $name" }
                }
            }
        }
    }

    private fun fieldNames(entity: Element): Set<String> =
        buildSet {
            val children = entity.childNodes
            repeat(children.length) { index ->
                val field = children.item(index) as? Element ?: return@repeat
                val name = field.getAttribute("name")
                if (name.isNotEmpty()) add(name)
                if (field.localName == "id" && name == "internalId") add("id")
                if (field.localName == "many-to-one") {
                    val columns = field.getElementsByTagNameNS("*", "column")
                    if (columns.length == 1 && (columns.item(0) as Element).getAttribute("name") == "${name}Id") {
                        add("${name}Id")
                    }
                }
            }
        }
}

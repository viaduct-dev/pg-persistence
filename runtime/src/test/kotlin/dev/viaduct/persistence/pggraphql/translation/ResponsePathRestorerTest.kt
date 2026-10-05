package dev.viaduct.persistence.pggraphql.translation

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsePathRestorerTest {
    @Test
    fun `internal alias precedence and truncated paths preserve public paths`() {
        fun path(vararg segments: Any) =
            segments.map {
                if (it is Int) JsonPrimitive(it) else JsonPrimitive(it.toString())
            }
        val list = ABSTRACT_LIST_PREFIX + "items"
        val nodes = ABSTRACT_NODES_PREFIX + "items"
        val page = ABSTRACT_LIST_PREFIX + ABSTRACT_LIST_PAGE_PREFIX + "items"
        val person = abstractAlias("person", "Person")
        val cases =
            listOf(
                path(nodes) to path("items"),
                path(nodes, 0, "node") to path("items", 0),
                path(list) to path("items"),
                path(list, "edges") to path("items"),
                path(list, "edges", 0, "node", person, "name") to path("items", 0, "name"),
                path(page, "edges", 0, "node", person, "name") to path("items", 0, "name"),
                path(person, "name") to path("person", "name"),
                path(typeAlias("kind", "Person")) to path("kind"),
                path(VIADUCT_NODES_RESPONSE_ALIAS, 0, "node", "name") to path("nodes", 0, "name"),
                path(
                    VIADUCT_ASSOCIATION_CONNECTION_ALIAS_PREFIX + "items",
                    VIADUCT_ASSOCIATION_EDGES_ALIAS_PREFIX + "rows",
                    0,
                    VIADUCT_ASSOCIATION_ROW_ALIAS,
                    VIADUCT_ASSOCIATION_NODE_ALIAS_PREFIX + "person",
                    "name",
                ) to path("items", "rows", 0, "person", "name"),
            )
        assertEquals(cases.map { it.second }, cases.map { ResponseShapeRestorer().restorePath(it.first) })
    }
}

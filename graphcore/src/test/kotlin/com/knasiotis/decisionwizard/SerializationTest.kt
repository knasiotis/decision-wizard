package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.model.CURRENT_SCHEMA_VERSION
import com.knasiotis.decisionwizard.model.parseGraph
import com.knasiotis.decisionwizard.model.toJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SerializationTest {

    @Test
    fun `sample parses`() {
        val graph = Fixtures.example()
        assertEquals("Internet down", graph.name)
        assertEquals("n-power", graph.rootNodeId)
        assertEquals(11, graph.nodes.size)
        assertEquals(CURRENT_SCHEMA_VERSION, graph.schemaVersion)
    }

    /**
     * The whole import story keys off schemaVersion and revision. If they are
     * omitted from the encoded form because they happen to equal a default, a
     * v1 export is indistinguishable from a file with no version at all.
     */
    @Test
    fun `schemaVersion and revision always survive a round trip`() {
        val minimal = Fixtures.graph("a", Fixtures.node("a")).copy(revision = 1)
        val json = minimal.toJson()

        assertContains(json, "\"schemaVersion\"")
        assertContains(json, "\"revision\"")
        assertEquals(1, parseGraph(json).revision)
        assertEquals(CURRENT_SCHEMA_VERSION, parseGraph(json).schemaVersion)
    }

    @Test
    fun `round trip preserves the sample exactly`() {
        val once = Fixtures.example()
        val twice = parseGraph(once.toJson())
        assertEquals(once, twice)
    }

    /** Nulls stay out of the file; a dangling answer just omits targetNodeId. */
    @Test
    fun `dangling answer encodes without an explicit null`() {
        val g = Fixtures.graph("a", Fixtures.node("a", Fixtures.answer("e1", "Yes", null)))
        val json = g.toJson()

        assertFalse(json.contains("null"), "expected no explicit nulls in:\n$json")
        assertNull(parseGraph(json).byId.getValue("a").answers.single().targetNodeId)
    }
}

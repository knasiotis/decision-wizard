package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.library.GraphSummary
import com.knasiotis.decisionwizard.library.ImportPlan
import com.knasiotis.decisionwizard.library.ImportPlanner
import com.knasiotis.decisionwizard.model.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportPlannerTest {

    private fun incoming(revision: Int = 1, graphId: String = "g-1", name: String = "Flow") =
        Fixtures.graph("a", Fixtures.node("a")).copy(
            graphId = graphId, name = name, revision = revision
        )

    private fun library(vararg s: GraphSummary) = s.toList()

    @Test
    fun `an unknown graphId is simply added`() {
        val plan = ImportPlanner.plan(incoming(), library(GraphSummary("other", "Other", 3)))
        val add = assertIs<ImportPlan.AddNew>(plan)
        assertEquals("g-1", add.incoming.graphId)
    }

    @Test
    fun `an empty library takes anything`() {
        assertIs<ImportPlan.AddNew>(ImportPlanner.plan(incoming(), emptyList()))
    }

    /** Same lineage, newer revision: updating in place is the point of the format. */
    @Test
    fun `a higher revision offers an update`() {
        val plan = ImportPlanner.plan(incoming(revision = 8), library(GraphSummary("g-1", "Flow", 7)))
        val c = assertIs<ImportPlan.Conflict>(plan)
        assertTrue(c.canUpdate)
        assertEquals(7, c.existing.revision)
        assertEquals(8, c.incoming.revision)
    }

    /** Overwriting with an older copy would silently discard work. */
    @Test
    fun `an equal or lower revision does not offer an update`() {
        val same = ImportPlanner.plan(incoming(revision = 7), library(GraphSummary("g-1", "Flow", 7)))
        assertTrue(!assertIs<ImportPlan.Conflict>(same).canUpdate)

        val older = ImportPlanner.plan(incoming(revision = 2), library(GraphSummary("g-1", "Flow", 7)))
        assertTrue(!assertIs<ImportPlan.Conflict>(older).canUpdate)
    }

    /**
     * Duplicate ids make a file impossible to load unambiguously. This is the
     * only place the app refuses the user outright.
     */
    @Test
    fun `duplicate node ids are rejected`() {
        val broken = Fixtures.graph("a", Fixtures.node("a"), Fixtures.node("a"))
        val plan = ImportPlanner.plan(broken, emptyList())
        val rejected = assertIs<ImportPlan.Rejected>(plan)
        assertTrue(rejected.fatal.any { it.code == "duplicate_node_id" })
    }

    @Test
    fun `duplicate answer ids are rejected`() {
        val broken = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", "b"), Fixtures.answer("e1", "No", "b")),
            Fixtures.node("b")
        )
        assertIs<ImportPlan.Rejected>(ImportPlanner.plan(broken, emptyList()))
    }

    /** Warnings inform, they never block — the file still imports. */
    @Test
    fun `warnings are carried through rather than blocking`() {
        val withOrphan = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", null)),
            Node(id = "orphan", title = "Orphan")
        )
        val add = assertIs<ImportPlan.AddNew>(ImportPlanner.plan(withOrphan, emptyList()))
        assertTrue(add.warnings.any { it.code == "orphan" })
    }

    /**
     * An unbuilt branch is the normal state of a new question, not a fault, so
     * it must not be reported as one.
     */
    @Test
    fun `an answer with no target is not a warning`() {
        val unfinished = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", null), Fixtures.answer("e2", "No", null))
        )
        val add = assertIs<ImportPlan.AddNew>(ImportPlanner.plan(unfinished, emptyList()))
        assertTrue(add.warnings.isEmpty(), "unexpected warnings: ${add.warnings.map { it.code }}")
    }

    @Test
    fun `the cyclic sample imports cleanly`() {
        assertIs<ImportPlan.AddNew>(ImportPlanner.plan(Fixtures.example(), emptyList()))
    }

    // --- duplication ---

    @Test
    fun `a duplicate gets a new id and restarts its revision`() {
        val dup = ImportPlanner.duplicate(incoming(revision = 7), "g-new", emptySet())

        assertEquals("g-new", dup.graphId, "a new id, or it collides on the next import")
        assertEquals(1, dup.revision, "a duplicate is a new lineage")
        assertEquals("Flow (copy)", dup.name)
    }

    /** Node ids are scoped to one graph, so copying them across cannot conflict. */
    @Test
    fun `a duplicate keeps its node and answer ids`() {
        val source = Fixtures.example()
        val dup = ImportPlanner.duplicate(source, "g-new", emptySet())
        assertEquals(source.nodes, dup.nodes)
        assertEquals(source.rootNodeId, dup.rootNodeId)
    }

    @Test
    fun `duplicate names step around what is already taken`() {
        val taken = setOf("Flow (copy)", "Flow (copy) (2)")
        val dup = ImportPlanner.duplicate(incoming(), "g-new", taken)
        assertEquals("Flow (copy) (3)", dup.name)
    }

    @Test
    fun `uniqueName leaves a free name alone`() {
        assertEquals("Flow", ImportPlanner.uniqueName("Flow", setOf("Other")))
        assertEquals("Flow (2)", ImportPlanner.uniqueName("Flow", setOf("Flow")))
    }
}

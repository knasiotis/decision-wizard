package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.model.Answer
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Node
import com.knasiotis.decisionwizard.model.parseGraph

/**
 * The sample graph doubles as the app's bundled asset and as the fixture here,
 * so a change that breaks one is caught by the other. It deliberately contains a
 * cycle: n-cable -> n-restart -> n-recheck -> n-cable.
 */
object Fixtures {

    fun exampleJson(): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/graph-schema-example.json")) {
            "graph-schema-example.json missing from test resources"
        }.bufferedReader().readText()

    fun example(): Graph = parseGraph(exampleJson())

    /** Minimal builder so structural tests do not depend on the sample's shape. */
    fun graph(rootId: String?, vararg nodes: Node): Graph =
        Graph(graphId = "g-test", name = "test", rootNodeId = rootId, nodes = nodes.toList())

    fun node(id: String, vararg answers: Answer): Node =
        Node(id = id, title = id, answers = answers.toList())

    fun answer(id: String, label: String, target: String?): Answer =
        Answer(id = id, label = label, targetNodeId = target)
}

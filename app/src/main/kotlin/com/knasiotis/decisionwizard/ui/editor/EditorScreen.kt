package com.knasiotis.decisionwizard.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knasiotis.decisionwizard.layout.EdgeKind
import com.knasiotis.decisionwizard.layout.GraphLayout
import com.knasiotis.decisionwizard.layout.NODE_WIDTH
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Issue
import com.knasiotis.decisionwizard.ui.common.NameDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    // Leaving saves. Graphs are hand-authored work, and losing an edit silently
    // because the user did not spot a Save button is worse than an extra
    // revision. Undo is the escape hatch, per the project's preference for undo
    // over confirmation dialogs.
    fun leave() {
        viewModel.save()
        onBack()
    }

    BackHandler { leave() }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var renamingGraph by remember { mutableStateOf(false) }

    // Pan and zoom are never committed to the undo stack. Camera position is
    // not a document change.
    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        // Zoom about the pinch centroid so the canvas grows under the fingers
        // rather than away from them. A screen point is world * scale + pan, so
        // holding the point under the centroid fixed gives the new pan directly.
        val next = (scale * zoomChange).coerceIn(0.25f, 3f)
        pan = centroid - (centroid - pan) * (next / scale) + panChange
        scale = next
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    // Tapping the name renames the graph, matching the chat screen.
                    Text(
                        text = ui.graph?.name ?: "Editor",
                        modifier = Modifier.clickable(enabled = ui.graph != null) {
                            renamingGraph = true
                        }
                    )
                },
                navigationIcon = { TextButton(onClick = { leave() }) { Text("Back") } },
                actions = {
                    TextButton(onClick = viewModel::undo, enabled = ui.canUndo) { Text("Undo") }
                    TextButton(onClick = viewModel::redo, enabled = ui.canRedo) { Text("Redo") }
                    TextButton(onClick = viewModel::save, enabled = ui.dirty) { Text("Save") }
                }
            )
        }
    ) { insets ->
        val graph = ui.graph
        val layout = ui.layout

        when {
            ui.loading -> Unit
            graph == null || layout == null -> MissingGraph(Modifier.padding(insets))
            else -> Box(
                modifier = Modifier
                    .padding(insets)
                    .fillMaxSize()
                    .clipToBounds()
                    .transformable(transform)
            ) {
                Canvas(
                    graph = graph,
                    layout = layout,
                    issuesByNode = ui.issuesByNode,
                    selected = selected,
                    onSelect = { selected = it },
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                        // Anchor at the top-left so zoom does not fight panning.
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                )

                // Selecting a bubble opens the actions for it.
                val node = selected?.let { graph.byId[it] }
                if (node != null) {
                    NodeSheet(
                        graph = graph,
                        node = node,
                        viewModel = viewModel,
                        onDismiss = { selected = null }
                    )
                }
            }
        }
    }

    if (renamingGraph) {
        NameDialog(
            dialogTitle = "Rename this graph",
            fieldLabel = "Name",
            initial = ui.graph?.name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.renameGraph(it)
                renamingGraph = false
            },
            onDismiss = { renamingGraph = false }
        )
    }
}

@Composable
private fun Canvas(
    graph: Graph,
    layout: GraphLayout,
    issuesByNode: Map<String, List<Issue>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val edgeColour = MaterialTheme.colorScheme.outline
    val nodeIds = remember(layout) { layout.positions.keys.toList() }

    Layout(
        modifier = modifier.drawBehind {
            // Only edges are painted. Bubbles are real composables, so they stay
            // hit-testable and accessible at any zoom.
            layout.edges.forEach { edge ->
                if (edge.kind != EdgeKind.ARROW) return@forEach
                val from = layout.positions[edge.sourceId] ?: return@forEach
                val to = layout.positions[edge.targetId] ?: return@forEach

                val start = Offset(
                    (from.x + NODE_WIDTH / 2).dp.toPx(),
                    (from.y + NODE_HEIGHT).dp.toPx()
                )
                val end = Offset(
                    (to.x + NODE_WIDTH / 2).dp.toPx(),
                    to.y.dp.toPx()
                )
                drawLine(edgeColour, start, end, strokeWidth = 2.dp.toPx())
            }
        },
        content = {
            nodeIds.forEach { id ->
                val node = graph.byId[id] ?: return@forEach
                NodeBubble(
                    title = node.title,
                    isRoot = id == graph.rootNodeId,
                    isEndpoint = node.isEndpoint,
                    isOrphan = id in layout.orphans,
                    warnings = issuesByNode[id].orEmpty().size,
                    chips = layout.chips.filter { it.onNodeId == id }.map { it.text },
                    selected = id == selected,
                    onClick = { onSelect(if (id == selected) null else id) },
                    modifier = Modifier.width(NODE_WIDTH.dp)
                )
            }
        }
    ) { measurables, _ ->
        // Children are measured loose: a bubble is as tall as its content, and
        // LayoutEngine only ever dictates x and the layer's y.
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = layout.width.dp.roundToPx()
        val height = (layout.height + NODE_HEIGHT).dp.roundToPx()

        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val position = layout.positions[nodeIds[index]] ?: return@forEachIndexed
                placeable.place(position.x.dp.roundToPx(), position.y.dp.roundToPx())
            }
        }
    }
}

/** Matches the height LayoutEngine assumes, so edges land on the bubbles. */
private const val NODE_HEIGHT = 64f

@Composable
private fun NodeBubble(
    title: String,
    isRoot: Boolean,
    isEndpoint: Boolean,
    isOrphan: Boolean,
    warnings: Int,
    chips: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colours = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        // Orphans grey out rather than being hidden or refused; an unreachable
        // node is a legal state, not an error.
        color = when {
            selected -> colours.primaryContainer
            isOrphan -> colours.surfaceVariant.copy(alpha = 0.5f)
            else -> colours.surfaceVariant
        },
        tonalElevation = if (selected) 6.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (isRoot) {
                Text("Start", style = MaterialTheme.typography.labelSmall, color = colours.primary)
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            if (isEndpoint) {
                Text(
                    "Endpoint",
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.onSurfaceVariant
                )
            }
            if (warnings > 0) {
                Text(
                    if (warnings == 1) "1 warning" else "$warnings warnings",
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.error
                )
            }
            // Both ends of every stub. The inbound chip is what tells a user
            // something points here at all.
            chips.forEach { chip ->
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.tertiary,
                    maxLines = 1,
                    modifier = Modifier
                        .background(colours.tertiaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun MissingGraph(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("That graph is gone", style = MaterialTheme.typography.titleMedium)
        Text(
            "It may have been deleted while this screen was open.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

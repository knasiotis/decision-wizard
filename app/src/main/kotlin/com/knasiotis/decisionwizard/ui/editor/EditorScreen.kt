package com.knasiotis.decisionwizard.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knasiotis.decisionwizard.R
import com.knasiotis.decisionwizard.layout.EdgeKind
import com.knasiotis.decisionwizard.layout.GraphLayout
import com.knasiotis.decisionwizard.layout.LayoutEngine
import com.knasiotis.decisionwizard.layout.NODE_WIDTH
import com.knasiotis.decisionwizard.layout.Point
import com.knasiotis.decisionwizard.layout.StubChip
import com.knasiotis.decisionwizard.layout.midpointOf
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

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    // The node to draw attention to, and a key that changes every time so the
    // same node can be pointed at twice running.
    var focused by remember { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableLongStateOf(0L) }

    // Where a jump came from, so a second tap on a stub chip comes back.
    val cameFrom = remember { mutableStateListOf<Offset>() }

    // Where the canvas is gliding to, if anywhere.
    var target by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current.density

    // Measured bubble heights, in dp. LayoutEngine assumes 64 for every node
    // unless it is told otherwise, which is what let a tall bubble overlap the
    // layer below it and an edge start inside its own bubble. Feeding the real
    // numbers back settles after one extra frame: a bubble's width is fixed, so
    // its height does not depend on where it was placed.
    val heights = remember { mutableStateMapOf<String, Float>() }
    val graph = ui.graph
    val layout = remember(graph, heights.toMap()) {
        graph?.let { subject ->
            LayoutEngine.layout(subject) { id -> heights[id] ?: NODE_HEIGHT }
        }
    }

    val snackbars = remember { SnackbarHostState() }
    var confirmingExit by rememberSaveable { mutableStateOf(false) }
    var renamingGraph by rememberSaveable { mutableStateOf(false) }

    // Leaving with unsaved work asks rather than guessing. Silently saving
    // would make experiments permanent; silently discarding would lose
    // hand-authored work. Only ask when there is actually something at stake.
    fun leave() {
        // A jump is somewhere the user was taken, so back should undo that
        // first rather than leaving the screen outright.
        if (cameFrom.isNotEmpty()) {
            target = cameFrom.removeAt(cameFrom.lastIndex)
            return
        }
        if (ui.dirty) confirmingExit = true else onBack()
    }

    BackHandler { leave() }

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

    fun lookAt(nodeId: String, remember: Boolean) {
        val current = layout ?: return
        val position = current.positions[nodeId] ?: return
        // The measured height, so a tall bubble is centred on itself rather
        // than on where its first 64dp happen to be.
        val height = heights[nodeId] ?: NODE_HEIGHT
        if (remember) cameFrom.add(pan)
        // Centre the node: a point on screen is content * scale + pan, so this
        // is that solved for pan.
        target = Offset(
            viewport.width / 2f - (position.x + NODE_WIDTH / 2) * scale * density,
            viewport.height / 2f - (position.y + height / 2) * scale * density
        )
        focused = nodeId
        focusKey++
    }

    // Glide rather than jump, so it is obvious the canvas moved rather than
    // redrew somewhere else.
    LaunchedEffect(target) {
        val destination = target ?: return@LaunchedEffect
        val from = pan
        animate(0f, 1f, animationSpec = tween(280)) { t, _ ->
            pan = lerp(from, destination, t)
        }
        target = null
    }

    LaunchedEffect(ui.announcement?.id) {
        val announcement = ui.announcement ?: return@LaunchedEffect
        announcement.focusNodeId?.let { lookAt(it, remember = false) }

        val result = snackbars.showSnackbar(
            message = announcement.message,
            actionLabel = if (announcement.undoable) "UNDO" else null,
            withDismissAction = true
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        viewModel.clearAnnouncement()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        // Bottom left, so it does not sit under the hand reaching for the
        // canvas, and away from the undo pair it is not part of.
        floatingActionButtonPosition = FabPosition.Start,
        floatingActionButton = {
            // Only offered when there is something to save; a button that does
            // nothing is worse than no button.
            if (ui.dirty) {
                FloatingActionButton(onClick = viewModel::save) {
                    Icon(
                        painter = painterResource(R.drawable.ic_save),
                        contentDescription = "Save this graph"
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    // Tapping the name renames the graph, matching the chat
                    // screen. The pencil is what makes that discoverable.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable(enabled = ui.graph != null) {
                            renamingGraph = true
                        }
                    ) {
                        Text(text = ui.graph?.name ?: "Editor")
                        if (ui.graph != null) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pencil),
                                contentDescription = "Rename this graph",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            // Named for a screen reader even though the glyph
                            // needs no label sighted.
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::undo, enabled = ui.canUndo) {
                        Icon(
                            painter = painterResource(R.drawable.ic_undo),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Undo")
                    }
                    TextButton(onClick = viewModel::redo, enabled = ui.canRedo) {
                        Icon(
                            painter = painterResource(R.drawable.ic_redo),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Redo")
                    }
                }
            )
        }
    ) { insets ->
        when {
            ui.loading -> Unit
            graph == null || layout == null -> MissingGraph(Modifier.padding(insets))
            else -> Box(
                modifier = Modifier
                    .padding(insets)
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { viewport = it }
                    .transformable(transform)
            ) {
                Canvas(
                    graph = graph,
                    layout = layout,
                    issuesByNode = ui.issuesByNode,
                    selected = selected,
                    focused = focused,
                    focusKey = focusKey,
                    onSelect = { selected = it },
                    onJump = { lookAt(it, remember = true) },
                    onHeight = { id, measured ->
                        // Guarded: an unguarded write would re-lay-out for ever
                        // on a sub-pixel difference.
                        if (kotlin.math.abs((heights[id] ?: -1f) - measured) > 0.5f) {
                            heights[id] = measured
                        }
                    },
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
                        issues = ui.issuesByNode[node.id].orEmpty(),
                        viewModel = viewModel,
                        onDismiss = { selected = null }
                    )
                }
            }
        }
    }

    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text("Keep your changes?") },
            text = { Text("This graph has edits that have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.save()
                    confirmingExit = false
                    onBack()
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmingExit = false
                    onBack()
                }) { Text("Discard") }
            }
        )
    }

    if (renamingGraph) {
        NameDialog(
            dialogTitle = "Rename",
            fieldLabel = "Graph name",
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
    focused: String?,
    focusKey: Long,
    onSelect: (String?) -> Unit,
    onJump: (String) -> Unit,
    /** Reports a bubble's measured height in dp, which the layout is redone on. */
    onHeight: (String, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val edgeColour = MaterialTheme.colorScheme.outline
    val labelColour = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBackground = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current.density
    // Filtered against the graph, because placement below pairs this list with
    // the measured children by index and a skipped bubble would shift every
    // node after it onto the wrong position.
    val nodeIds = remember(layout, graph) {
        layout.positions.keys.filter { graph.byId.containsKey(it) }
    }

    // Which answer an edge represents is the whole point of the branch, so the
    // label is drawn on the line rather than left to be inferred from position.
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColour)
    val painted = remember(layout, labelStyle) {
        layout.edges.mapNotNull { edge ->
            // Nothing is drawn for an answer with no child yet: a stub would put
            // "Yes" and "No" under a brand-new question before either branch
            // exists, which reads as structure that is not there. A STUB is a
            // pair of chips on the two bubbles and no line at all.
            if (edge.kind != EdgeKind.ARROW) return@mapNotNull null
            PaintedEdge(
                route = edge.route,
                text = measurer.measure(edge.label, labelStyle),
                labelAt = midpointOf(edge.route) ?: return@mapNotNull null
            )
        }
    }

    Layout(
        modifier = modifier.drawBehind {
            // Only edges and their labels are painted. Bubbles are real
            // composables, so they stay hit-testable at any zoom.
            painted.forEach { edge ->
                // Right angles, corner to corner. A straight line to a
                // grandchild runs behind whichever node stands between them and
                // reads as an edge into it.
                val corners = edge.route.map { Offset(it.x.dp.toPx(), it.y.dp.toPx()) }
                corners.zipWithNext { from, to ->
                    drawLine(
                        edgeColour, from, to,
                        strokeWidth = 2.dp.toPx(),
                        // Rounds the corners, so two segments meet as one line
                        // rather than as a notch.
                        cap = StrokeCap.Round
                    )
                }

                val mid = Offset(edge.labelAt.x.dp.toPx(), edge.labelAt.y.dp.toPx())
                val size = edge.text.size
                val topLeft = Offset(mid.x - size.width / 2f, mid.y - size.height / 2f)
                val pad = 3.dp.toPx()

                // A plate behind the text, or the line reads straight through it.
                drawRoundRect(
                    color = labelBackground,
                    topLeft = Offset(topLeft.x - pad, topLeft.y - pad),
                    size = Size(size.width + pad * 2, size.height + pad * 2),
                    cornerRadius = CornerRadius(pad)
                )
                drawText(edge.text, topLeft = topLeft)
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
                    chips = layout.chips.filter { it.onNodeId == id },
                    selected = id == selected,
                    focused = id == focused,
                    focusKey = focusKey,
                    onClick = { onSelect(if (id == selected) null else id) },
                    onChipClick = onJump,
                    modifier = Modifier
                        .width(NODE_WIDTH.dp)
                        .onSizeChanged { onHeight(id, it.height / density) }
                )
            }
        }
    ) { measurables, _ ->
        // Children are measured loose: a bubble is as tall as its content, and
        // LayoutEngine only ever dictates x and the layer's y.
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = layout.width.dp.roundToPx()
        // layout.height already reaches the bottom of the lowest bubble, since
        // it is measured from the same heights these children were measured at.
        val height = (layout.height + CANVAS_MARGIN).dp.roundToPx()

        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val position = layout.positions[nodeIds[index]] ?: return@forEachIndexed
                placeable.place(position.x.dp.roundToPx(), position.y.dp.roundToPx())
            }
        }
    }
}

/**
 * What LayoutEngine is told a bubble is tall before one has been measured. Only
 * the first frame uses it; after that the real measurements are fed back.
 */
private const val NODE_HEIGHT = 64f

/** Breathing room under the last layer, so it is not flush with the edge. */
private const val CANVAS_MARGIN = 48f

private data class PaintedEdge(
    /** Right-angled corners from the source's bottom to the target's top. */
    val route: List<Point>,
    val text: TextLayoutResult,
    /** Half way along the route by length, so the label sits on the line. */
    val labelAt: Point
)

@Composable
private fun NodeBubble(
    title: String,
    isRoot: Boolean,
    isEndpoint: Boolean,
    isOrphan: Boolean,
    warnings: Int,
    chips: List<StubChip>,
    selected: Boolean,
    focused: Boolean,
    focusKey: Long,
    onClick: () -> Unit,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colours = MaterialTheme.colorScheme

    // Swells and settles once when pointed at. Arriving somewhere on a large
    // canvas is otherwise indistinguishable from having been there all along.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(focusKey, focused) {
        if (!focused) return@LaunchedEffect
        pulse.animateTo(1.14f, tween(160))
        pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .clickable(onClick = onClick),
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
                    if (warnings == 1) "1 warning — tap" else "$warnings warnings — tap",
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.error
                )
            }
            // Both ends of every stub. The inbound chip is what tells a user
            // something points here at all.
            chips.forEach { chip ->
                // Tapping either end of a stub travels to the other. Without
                // this the chip only says a link exists, which is the least
                // useful thing it could say.
                Text(
                    chip.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = colours.tertiary,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onChipClick(chip.otherNodeId) }
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

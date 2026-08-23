package com.knasiotis.decisionwizard.editor

import com.knasiotis.decisionwizard.model.Graph

/**
 * [graph] is an immutable tree of data classes, so a snapshot is a new list of
 * mostly-shared references — a few kilobytes even for a large graph. There is
 * no need to diff or serialize anything.
 *
 * [focusNodeId] is what the canvas pans to after an undo, so the user can see
 * what changed even if it happened off-screen.
 */
data class Snapshot(
    val graph: Graph,
    val description: String,
    val focusNodeId: String?
)

class UndoStack(initial: Graph, private val limit: Int = 50) {

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    init {
        undoStack.addLast(Snapshot(initial, "Opened", null))
    }

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val current: Snapshot get() = undoStack.last()

    /**
     * The snapshot an undo would take back — the action itself.
     *
     * Not the same as what [undo] returns, which is the state being *returned
     * to* and therefore describes the action before it. Announcing that one says
     * "Opened" after undoing the first edit.
     */
    val undoSnapshot: Snapshot? get() = if (canUndo) undoStack.last() else null

    /** The snapshot a redo would re-apply. After an undo, the action taken back. */
    val redoSnapshot: Snapshot? get() = redoStack.lastOrNull()

    /** The label for the button tooltip and the snackbar after an undo. */
    val undoDescription: String? get() = undoSnapshot?.description
    val redoDescription: String? get() = redoSnapshot?.description

    fun commit(graph: Graph, description: String, focusNodeId: String? = null) {
        undoStack.addLast(Snapshot(graph, description, focusNodeId))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Snapshot? {
        if (!canUndo) return null
        redoStack.addLast(undoStack.removeLast())
        return undoStack.last()
    }

    fun redo(): Snapshot? {
        if (!canRedo) return null
        val snapshot = redoStack.removeLast()
        undoStack.addLast(snapshot)
        return snapshot
    }
}

/**
 * Coalescing lives here rather than in the stack.
 *
 * Structural edits call [applyStructural] and become one undo step immediately.
 * Text editing calls [stageDraft] on every keystroke and [commitDraft] once,
 * when the node sheet closes — the whole typing session collapses into one step.
 */
class GraphEditor(initial: Graph) {

    private val stack = UndoStack(initial)

    var graph: Graph = initial
        private set

    private var draftBase: Graph? = null

    val canUndo: Boolean get() = stack.canUndo
    val canRedo: Boolean get() = stack.canRedo
    val undoLabel: String? get() = stack.undoDescription
    val redoLabel: String? get() = stack.redoDescription

    /** The edit an undo would take back, for saying what was undone. */
    val undoSnapshot: Snapshot? get() = stack.undoSnapshot

    fun applyStructural(next: Graph, description: String, focusNodeId: String?) {
        draftBase = null
        graph = next
        stack.commit(next, description, focusNodeId)
    }

    /** Per-keystroke. Updates live state without touching history. */
    fun stageDraft(next: Graph) {
        if (draftBase == null) draftBase = graph
        graph = next
    }

    /** Called when the editing sheet closes. No-op if nothing actually changed. */
    fun commitDraft(description: String, focusNodeId: String?) {
        val base = draftBase ?: return
        draftBase = null
        if (base == graph) return
        stack.commit(graph, description, focusNodeId)
    }

    /** Called when the sheet is dismissed without saving. */
    fun discardDraft() {
        draftBase?.let { graph = it }
        draftBase = null
    }

    fun undo(): Snapshot? = stack.undo()?.also { graph = it.graph }

    fun redo(): Snapshot? = stack.redo()?.also { graph = it.graph }
}

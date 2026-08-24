package com.knasiotis.decisionwizard.layout

/**
 * Where the canvas may sit, given where it is being asked to sit.
 *
 * Pure arithmetic on one axis, kept here rather than in the editor so it can be
 * tested without a device. The editor calls it once per axis.
 */
object Viewport {

    /**
     * The pan closest to [want] that still keeps the canvas on screen.
     *
     * A point is drawn at `content * scale + pan`, so panning is what decides
     * which part of the canvas is visible. [content] and [extent] are both in
     * the same units — pixels, in the editor's case — and [margin] is the strip
     * of breathing room left at the edge.
     *
     * Content smaller than the viewport is held inside it; content larger is
     * held covering it. Either way the answer is a **range**, and [want]
     * survives as far as that range allows.
     *
     * That last part is the whole point. Staying on screen is a constraint on
     * the answer, not the answer itself: an earlier version returned the pan
     * that centred the whole canvas whenever the canvas fitted, which threw
     * [want] away entirely. Tapping a stub chip then slid the canvas sideways
     * and stopped somewhere that was not the node it had just flashed —
     * reliably so once zoomed out far enough for the width to fit.
     */
    fun clampPan(want: Float, content: Float, extent: Float, margin: Float): Float {
        val low: Float
        val high: Float
        if (content + margin * 2 <= extent) {
            low = margin
            high = extent - content - margin
        } else {
            low = extent - content - margin
            high = margin
        }
        return want.coerceIn(low, high)
    }
}

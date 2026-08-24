package com.knasiotis.decisionwizard.layout

/**
 * Where the canvas has to sit to put something in the middle of the screen.
 *
 * Pure arithmetic, kept here rather than in the editor so it can be tested
 * without a device. The editor calls it once per axis.
 */
object Viewport {

    /**
     * The pan that puts [contentCentre] at the middle of a viewport [extent]
     * across.
     *
     * A point is drawn at `content * scale * density + pan`, so this is that
     * solved for the pan which lands [contentCentre] on `extent / 2`.
     *
     * **Nothing is clamped, and that is deliberate.** Two earlier versions tried
     * to keep the whole canvas on screen at the same time — first by centring
     * the canvas whenever it fitted, then by clamping the pan into the range
     * that kept it covering the viewport. Both fight this: a node near the edge
     * of a large graph cannot be in the middle of the screen *and* have the rest
     * of the graph on screen, so any containment rule wins the argument and the
     * jump stops short of the node it just flashed.
     *
     * A centred node is on screen by construction, which is the only thing the
     * jump actually has to guarantee. The rest of the canvas running off the
     * edges is what panning is for.
     */
    fun centreOn(contentCentre: Float, extent: Float, scale: Float, density: Float): Float =
        extent / 2f - contentCentre * scale * density

    /** Where a content coordinate lands on screen under [pan]. */
    fun onScreen(content: Float, pan: Float, scale: Float, density: Float): Float =
        content * scale * density + pan
}

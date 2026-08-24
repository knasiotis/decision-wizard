package com.knasiotis.decisionwizard.layout

/**
 * Where the canvas has to sit to put something in the middle of the screen.
 *
 * Pure arithmetic, kept here rather than in the editor so it can be tested
 * without a device. The editor calls it once per axis.
 */
object Viewport {

    /**
     * The pan that brings a node to the middle, worked out from **where it
     * actually is on screen** rather than from where the layout says it ought to
     * be.
     *
     * [nodeCentre] and [viewportCentre] are measured positions in the same
     * space, and [panAtMeasure] is the pan that was in force when they were
     * measured. Panning moves the canvas one-for-one — a point is drawn at
     * `content * scale + pan`, so adding `d` to the pan moves everything on
     * screen by exactly `d`, whatever the zoom — which is what makes this a
     * plain subtraction with no scale or density in it at all.
     *
     * **That absence is the point.** Three releases tried to compute this
     * position from the layout instead: `viewportExtent / 2 - centre * scale *
     * density`. That formula is correct — a probe over the real demo graph puts
     * the node dead centre at 1x, 0.5x and 0.25x — and it still did not work on
     * a device, because being correct depends on the layout coordinates, the
     * density, the zoom and the container's own offset all being what the caller
     * believes at that instant. Any one of them being different by so much as a
     * factor moves the canvas somewhere absurd.
     *
     * Measuring has no such assumptions to get wrong. If the node is 900px left
     * of the middle, the answer is 900px, and it does not matter why it was
     * there.
     */
    fun centreBy(nodeCentre: Float, viewportCentre: Float, panAtMeasure: Float): Float =
        panAtMeasure + (viewportCentre - nodeCentre)

    /** Where a measured point ends up once [pan] replaces [panAtMeasure]. */
    fun movedTo(nodeCentre: Float, panAtMeasure: Float, pan: Float): Float =
        nodeCentre + (pan - panAtMeasure)
}

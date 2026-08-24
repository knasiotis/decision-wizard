package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.layout.Viewport.centreBy
import com.knasiotis.decisionwizard.layout.Viewport.movedTo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {

    /** Ask for the centring pan, apply it, and see where the node ends up. */
    private fun assertLandsInMiddle(nodeCentre: Float, viewportCentre: Float, panAtMeasure: Float) {
        val pan = centreBy(nodeCentre, viewportCentre, panAtMeasure)
        val landed = movedTo(nodeCentre, panAtMeasure, pan)
        assertTrue(
            abs(landed - viewportCentre) < 0.01f,
            "node measured at $nodeCentre landed on $landed, wanted $viewportCentre"
        )
    }

    @Test
    fun `a node left of the middle comes back to it`() {
        assertLandsInMiddle(nodeCentre = 120f, viewportCentre = 540f, panAtMeasure = -300f)
    }

    @Test
    fun `a node right of the middle comes back to it`() {
        assertLandsInMiddle(nodeCentre = 1900f, viewportCentre = 540f, panAtMeasure = -300f)
    }

    @Test
    fun `a node already in the middle does not move`() {
        assertEquals(
            -300f,
            centreBy(nodeCentre = 540f, viewportCentre = 540f, panAtMeasure = -300f)
        )
    }

    /**
     * A node far off screen is exactly the case the calculated versions got
     * wrong. Here the distance is whatever was measured, so how far away it is
     * changes nothing about the method.
     */
    @Test
    fun `a node far off screen is brought back in one move`() {
        listOf(-9000f, -2508f, 0f, 3000f, 12000f).forEach { measured ->
            assertLandsInMiddle(nodeCentre = measured, viewportCentre = 540f, panAtMeasure = -300f)
        }
    }

    /**
     * Whatever pan was in force when the node was measured, the answer still
     * lands it in the middle — so a stale measurement cannot send the canvas
     * somewhere absurd, as long as the pan it was taken under is remembered
     * alongside it.
     */
    @Test
    fun `the pan in force at measuring time is what makes it exact`() {
        listOf(-5000f, -300f, 0f, 640f).forEach { panAtMeasure ->
            assertLandsInMiddle(
                nodeCentre = 1900f,
                viewportCentre = 540f,
                panAtMeasure = panAtMeasure
            )
        }
    }

    /**
     * No zoom or density appears anywhere in this, which is deliberate: panning
     * shifts the screen one-for-one at any zoom. **Do not reintroduce a scale
     * term.** Three releases went that way and none of them worked on a device.
     */
    @Test
    fun `the answer is a plain difference, with no scale term to get wrong`() {
        val pan = centreBy(nodeCentre = 1900f, viewportCentre = 540f, panAtMeasure = -300f)
        assertEquals(-300f + (540f - 1900f), pan)
    }
}

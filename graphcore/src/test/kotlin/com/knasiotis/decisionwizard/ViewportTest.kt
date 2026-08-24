package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.layout.Viewport.centreOn
import com.knasiotis.decisionwizard.layout.Viewport.onScreen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewportTest {

    private fun assertCentred(centre: Float, extent: Float, scale: Float, density: Float) {
        val pan = centreOn(centre, extent, scale, density)
        val landed = onScreen(centre, pan, scale, density)
        assertTrue(
            abs(landed - extent / 2f) < 0.01f,
            "centre $centre at scale $scale landed on $landed, wanted ${extent / 2f}"
        )
    }

    @Test
    fun `the node lands in the middle whatever the zoom`() {
        listOf(0.25f, 0.5f, 1f, 1.7f, 3f).forEach { scale ->
            assertCentred(centre = 912f, extent = 1080f, scale = scale, density = 2.75f)
        }
    }

    @Test
    fun `the node lands in the middle whatever the screen density`() {
        listOf(1f, 2f, 2.75f, 3.5f).forEach { density ->
            assertCentred(centre = 912f, extent = 1080f, scale = 1f, density = density)
        }
    }

    /**
     * The regression that keeps coming back. Two earlier versions clamped the
     * pan to keep the whole canvas on screen, and a node near the edge of a
     * large graph cannot be centred *and* leave the graph visible — so the
     * clamp won and the jump stopped short of the node it had just flashed.
     *
     * Being off centre is the failure. The canvas hanging off the edges is not.
     */
    @Test
    fun `a node at the far edge of a big canvas is still centred`() {
        val extent = 1080f
        val density = 2.75f

        // Far right of a canvas much wider than the screen.
        assertCentred(centre = 3000f, extent = extent, scale = 1f, density = density)
        // Far left, where the canvas runs off the right instead.
        assertCentred(centre = 20f, extent = extent, scale = 1f, density = density)

        // And the pan is allowed to put the canvas origin off screen either way.
        val farRight = centreOn(3000f, extent, 1f, density)
        val farLeft = centreOn(20f, extent, 1f, density)
        assertTrue(farRight < 0f, "a far node must be reachable by panning past 0")
        assertTrue(farLeft > 0f, "a near node must be reachable by panning past 0 the other way")
    }

    /**
     * A canvas smaller than the screen is the case the first version special
     * cased, centring the whole canvas and ignoring which node had been asked
     * for. Every jump then produced the same movement.
     */
    @Test
    fun `a canvas smaller than the screen still moves to the node asked for`() {
        val extent = 1080f
        val left = centreOn(50f, extent, 0.3f, 2.75f)
        val right = centreOn(350f, extent, 0.3f, 2.75f)

        assertNotEquals(left, right, "the answer has to depend on which node was asked for")
        assertTrue(left > right, "a node further right needs a smaller pan")
    }

    /**
     * A viewport of zero has no middle, and [centreOn] cannot say so — it
     * returns the pan that puts the node at screen x = 0, which drags the whole
     * canvas off to the left. That is precisely what a jump taken before the
     * canvas has been measured looks like, so the editor checks the size first
     * and holds the request rather than moving on a guess.
     */
    @Test
    fun `an unmeasured viewport has no middle to centre on`() {
        val pan = centreOn(912f, extent = 0f, scale = 1f, density = 2.75f)
        assertTrue(pan < 0f, "the pan runs negative, dragging the canvas left")
        assertTrue(
            abs(onScreen(912f, pan, 1f, 2.75f)) < 0.01f,
            "the node lands on the left edge rather than in any middle"
        )
    }

    /** Zoom is an input, never something this changes on the caller's behalf. */
    @Test
    fun `two zoom levels centre the same node at different pans`() {
        val a = centreOn(912f, 1080f, 1f, 2.75f)
        val b = centreOn(912f, 1080f, 0.5f, 2.75f)
        assertNotEquals(a, b)
        assertCentred(912f, 1080f, 1f, 2.75f)
        assertCentred(912f, 1080f, 0.5f, 2.75f)
    }
}

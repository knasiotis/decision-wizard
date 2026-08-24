package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.layout.Viewport.clampPan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewportTest {

    private val margin = 24f

    /**
     * The regression that matters. A stub chip asks to centre a node; the reply
     * has to depend on which node was asked for. The version this replaced
     * returned the pan that centred the whole canvas whenever the canvas fitted
     * the viewport, so every jump on a zoomed-out graph produced the same
     * sideways slide and never arrived at the node it had flashed.
     */
    @Test
    fun `a canvas that fits still moves towards the node it was asked for`() {
        val content = 400f
        val extent = 1000f

        val left = clampPan(want = -50f, content = content, extent = extent, margin = margin)
        val right = clampPan(want = 500f, content = content, extent = extent, margin = margin)

        assertNotEquals(left, right, "the answer has to depend on what was asked for")
        assertTrue(left < right, "asking to move right must not move left")
    }

    @Test
    fun `a wanted pan inside the range is left alone`() {
        assertEquals(
            300f,
            clampPan(want = 300f, content = 400f, extent = 1000f, margin = margin)
        )
    }

    @Test
    fun `a canvas smaller than the viewport is kept fully inside it`() {
        val content = 400f
        val extent = 1000f

        listOf(-9000f, -1f, 0f, 500f, 9000f).forEach { want ->
            val pan = clampPan(want, content, extent, margin)
            assertTrue(pan >= margin, "left edge left the screen at want=$want (pan=$pan)")
            assertTrue(
                pan + content <= extent - margin,
                "right edge left the screen at want=$want (pan=$pan)"
            )
        }
    }

    @Test
    fun `a canvas larger than the viewport is kept covering it`() {
        val content = 5000f
        val extent = 1000f

        listOf(-9000f, -2500f, 0f, 9000f).forEach { want ->
            val pan = clampPan(want, content, extent, margin)
            assertTrue(pan <= margin, "a gap opened on the left at want=$want (pan=$pan)")
            assertTrue(
                pan + content >= extent - margin,
                "a gap opened on the right at want=$want (pan=$pan)"
            )
        }
    }

    /**
     * Zooming out shrinks the content, which is exactly when the old bug showed
     * itself: the axis flips from "larger than the viewport" to "smaller", and
     * that is where the wanted pan used to stop being consulted.
     */
    @Test
    fun `crossing from larger than the viewport to smaller keeps honouring the want`() {
        val extent = 1000f
        val want = 120f

        val zoomedIn = clampPan(want, content = 5000f, extent = extent, margin = margin)
        val zoomedOut = clampPan(want, content = 400f, extent = extent, margin = margin)

        assertEquals(margin, zoomedIn, "clamped to the left bound while overflowing")
        assertEquals(want, zoomedOut, "inside the range once it fits, so untouched")
    }

    /** The bounds meet rather than cross when content is exactly viewport-sized. */
    @Test
    fun `content the same size as the viewport does not invert its bounds`() {
        val pan = clampPan(want = 999f, content = 1000f, extent = 1000f, margin = margin)
        assertEquals(margin, pan)
    }
}

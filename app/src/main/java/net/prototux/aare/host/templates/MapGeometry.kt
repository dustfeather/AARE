package net.prototux.aare.host.templates

/**
 * Pure geometry for SurfaceCallback.onVisibleAreaChanged: the largest axis-aligned rectangle of
 * the map left free by the floating cards and strips.
 */
internal object MapGeometry {
    /** A rectangle in pixels; [right]/[bottom] exclusive like android.graphics.Rect. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = if (width <= 0f || height <= 0f) 0f else width * height

        fun intersect(o: Box): Box? {
            val l = maxOf(left, o.left)
            val t = maxOf(top, o.top)
            val r = minOf(right, o.right)
            val b = minOf(bottom, o.bottom)
            return if (r > l && b > t) Box(l, t, r, b) else null
        }
    }

    /**
     * Greedy cut: for each obstacle (largest first) keep the biggest of the four slices of the
     * remaining area beside it. Cards hug the screen edges, so this finds the true free area for
     * the layouts we draw (start column + end strips, or top/bottom cards in portrait).
     */
    fun unobstructed(map: Box, obstacles: List<Box>): Box {
        var r = map
        val sorted = obstacles.mapNotNull { it.intersect(map) }.sortedByDescending { it.area }
        for (o in sorted) {
            val i = o.intersect(r) ?: continue
            r = listOf(
                Box(i.right, r.top, r.right, r.bottom), // keep what is right of it
                Box(r.left, r.top, i.left, r.bottom), // left of it
                Box(r.left, i.bottom, r.right, r.bottom), // below it
                Box(r.left, r.top, r.right, i.top), // above it
            ).maxBy { it.area }
        }
        return r
    }
}

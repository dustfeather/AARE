package net.prototux.aare.headunit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A position on the simulated drive. */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** metres per second */
    val speed: Double,
    /** degrees from north, clockwise */
    val bearing: Double,
    val travelledMeters: Double,
    val totalMeters: Double,
) {
    val progress: Double get() = if (totalMeters > 0) travelledMeters / totalMeters else 0.0
}

/**
 * Drives along a polyline at a cruising speed, slowing for sharp bends, with optional time
 * acceleration. Pure logic: the caller asks for [fixAt] some elapsed driving time.
 */
class RouteDrive(private val points: List<Pair<Double, Double>>) {
    private val cumulative = DoubleArray(points.size)
    val totalMeters: Double

    init {
        require(points.size >= 2) { "a route needs at least two points" }
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
        totalMeters = cumulative.last()
    }

    /** Position after [meters] along the route (clamped to the ends). */
    fun at(meters: Double, speed: Double): Fix {
        val m = meters.coerceIn(0.0, totalMeters)
        var lo = 0
        var hi = cumulative.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= m) lo = mid else hi = mid
        }
        val segLen = cumulative[hi] - cumulative[lo]
        val t = if (segLen > 0) (m - cumulative[lo]) / segLen else 0.0
        val (aLat, aLon) = points[lo]
        val (bLat, bLon) = points[hi]
        return Fix(
            lat = aLat + (bLat - aLat) * t,
            lon = aLon + (bLon - aLon) * t,
            speed = if (m >= totalMeters) 0.0 else speed,
            bearing = bearing(points[lo], points[hi]),
            travelledMeters = m,
            totalMeters = totalMeters,
        )
    }

    /** Cruising speed for a stretch: slower in town (dense points) than on the motorway. */
    fun speedAt(meters: Double, cruise: Double): Double {
        // Point density is a good proxy: OSRM geometries have many points where roads bend.
        val window = 400.0
        val a = indexAt(meters)
        val b = indexAt(meters + window)
        val density = (b - a) / window * 1000 // points per km
        val factor = when {
            density > 25 -> 0.35 // city streets
            density > 12 -> 0.6
            density > 6 -> 0.85
            else -> 1.0
        }
        return cruise * factor
    }

    private fun indexAt(meters: Double): Int {
        var i = java.util.Arrays.binarySearch(cumulative, meters.coerceIn(0.0, totalMeters))
        if (i < 0) i = -i - 1
        return i.coerceIn(0, cumulative.size - 1)
    }

    companion object {
        /** Loads "lat,lon" lines (comments start with #) from a classpath resource or file. */
        fun load(name: String): RouteDrive {
            val text = RouteDrive::class.java.getResource("/routes/$name.txt")?.readText()
                ?: java.io.File(name).readText()
            val pts = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { l -> l.split(',').let { it[0].toDouble() to it[1].toDouble() } }.toList()
            return RouteDrive(pts)
        }

        fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val r = 6_371_000.0
            val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first)
            val x = Math.toRadians(b.second - a.second) * cos((la1 + la2) / 2)
            val y = la2 - la1
            return r * hypot(x, y)
        }

        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first)
            val dl = Math.toRadians(b.second - a.second)
            val y = sin(dl) * cos(la2)
            val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}

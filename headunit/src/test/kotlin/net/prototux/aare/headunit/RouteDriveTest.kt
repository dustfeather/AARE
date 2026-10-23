package net.prototux.aare.headunit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDriveTest {
    private val route = RouteDrive.load("paris-marseille")

    @Test
    fun startsInParisEndsInMarseille() {
        val start = route.at(0.0, 0.0)
        assertEquals(48.85, start.lat, 0.05)
        assertEquals(2.35, start.lon, 0.05)
        val end = route.at(route.totalMeters, 0.0)
        assertEquals(43.30, end.lat, 0.05)
        assertEquals(5.37, end.lon, 0.05)
        assertTrue("about 775 km, got ${route.totalMeters / 1000}", route.totalMeters in 740_000.0..800_000.0)
    }

    @Test
    fun interpolatesAndHeadsSouth() {
        val mid = route.at(route.totalMeters / 2, 30.0)
        assertTrue(mid.lat < 48.0 && mid.lat > 44.0)
        assertEquals(0.5, mid.progress, 0.001)
        // the drive is mostly southward: headings around south (90°..270°) dominate
        val southish = (1..50).count { val f = route.at(route.totalMeters * it / 51, 30.0); f.bearing in 90.0..270.0 }
        assertTrue("southish=$southish", southish > 30)
    }

    @Test
    fun slowerInTownThanOnTheMotorway() {
        val cruise = 36.0
        val city = route.speedAt(500.0, cruise) // central Paris
        val motorway = (0..20).map { route.speedAt(route.totalMeters * (0.3 + it * 0.02), cruise) }.max()
        assertTrue("city=$city motorway=$motorway", city < motorway)
    }
}

package net.prototux.aare.host.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CarFormatTest {
    @Test
    fun distances() {
        val l = Locale.US
        assertEquals("300 m", CarFormat.distance(300.4, CarFormat.UNIT_METERS, l))
        assertEquals("12 km", CarFormat.distance(12.4, CarFormat.UNIT_KILOMETERS, l))
        assertEquals("2.5 km", CarFormat.distance(2.46, CarFormat.UNIT_KILOMETERS_P1, l))
        assertEquals("0.3 mi", CarFormat.distance(0.3, CarFormat.UNIT_MILES_P1, l))
        assertEquals("4 mi", CarFormat.distance(3.6, CarFormat.UNIT_MILES, l))
        assertEquals("500 ft", CarFormat.distance(500.0, CarFormat.UNIT_FEET, l))
        assertEquals("80 yd", CarFormat.distance(80.0, CarFormat.UNIT_YARDS, l))
        assertEquals("2,5 km", CarFormat.distance(2.5, CarFormat.UNIT_KILOMETERS_P1, Locale.FRANCE))
        assertEquals("0 m", CarFormat.distance(-3.0, CarFormat.UNIT_METERS, l))
    }

    @Test
    fun durations() {
        assertNull(CarFormat.duration(-1))
        assertEquals("< 1 min", CarFormat.duration(10))
        assertEquals("1 min", CarFormat.duration(45))
        assertEquals("45 min", CarFormat.duration(45 * 60))
        assertEquals("1 h", CarFormat.duration(3600))
        assertEquals("1 h 5 min", CarFormat.duration(3600 + 5 * 60))
        assertEquals("2 d 3 h", CarFormat.duration(2 * 86400 + 3 * 3600 + 120))
        assertEquals("1 d", CarFormat.duration(86400))
    }

    @Test
    fun timers() {
        assertEquals("0:00", CarFormat.timer(0))
        assertEquals("1:05", CarFormat.timer(65_000))
        assertEquals("1:00:01", CarFormat.timer(3_601_000))
    }
}

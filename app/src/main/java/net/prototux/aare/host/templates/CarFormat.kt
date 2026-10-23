package net.prototux.aare.host.templates

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure formatting of the library's distance/duration values (DistanceSpan, DurationSpan,
 * RoutingInfo, TravelEstimate). No Android or Compose dependencies, so it is unit tested.
 */
internal object CarFormat {
    // androidx.car.app.model.Distance.UNIT_*: duplicated so this stays a plain JVM object.
    const val UNIT_METERS = 1
    const val UNIT_KILOMETERS = 2
    const val UNIT_KILOMETERS_P1 = 3
    const val UNIT_MILES = 4
    const val UNIT_MILES_P1 = 5
    const val UNIT_FEET = 6
    const val UNIT_YARDS = 7

    /**
     * "300 m", "12 km", "2.5 km", "0.3 mi", "500 ft". The app already picked the display unit
     * and value (Distance.displayDistance/displayUnit); the _P1 units ask for one decimal.
     */
    fun distance(value: Double, unit: Int, locale: Locale = Locale.getDefault()): String {
        val v = if (value.isFinite()) maxOf(value, 0.0) else 0.0
        return when (unit) {
            UNIT_KILOMETERS_P1 -> String.format(locale, "%.1f km", v)
            UNIT_MILES_P1 -> String.format(locale, "%.1f mi", v)
            UNIT_KILOMETERS -> "${v.roundToLong()} km"
            UNIT_MILES -> "${v.roundToLong()} mi"
            UNIT_FEET -> "${v.roundToLong()} ft"
            UNIT_YARDS -> "${v.roundToLong()} yd"
            else -> "${v.roundToLong()} m"
        }
    }

    /**
     * Compact duration in the style of in-car UIs: "< 1 min", "45 min", "1 h 5 min", "2 d 3 h".
     * Rounded to the nearest minute; null for negative (unknown) values.
     */
    fun duration(seconds: Long): String? {
        if (seconds < 0) return null
        val minutes = (seconds + 30) / 60
        if (minutes == 0L) return "< 1 min"
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> if (hours > 0) "$days d $hours h" else "$days d"
            hours > 0 -> if (mins > 0) "$hours h $mins min" else "$hours h"
            else -> "$mins min"
        }
    }

    /** Running timer text for TimerSpan: "m:ss" or "h:mm:ss". */
    fun timer(elapsedMillis: Long): String {
        val total = maxOf(elapsedMillis, 0L) / 1000
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

package net.prototux.aare.video

import net.prototux.aare.video.BitrateController.Companion.congested
import net.prototux.aare.video.BitrateController.Companion.decide
import net.prototux.aare.video.BitrateController.Companion.floorFor
import net.prototux.aare.video.BitrateController.State
import net.prototux.aare.video.BitrateController.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateControllerTest {
    private val start = 6_000_000
    private val floor = floorFor(start)

    private fun run(s: State, ticks: Int, congested: Boolean, overflowed: Boolean = false): State =
        (1..ticks).fold(s) { acc, _ -> decide(acc, Tick(250, congested, overflowed), start, floor) }

    @Test
    fun floorIsAQuarterAtLeastOneMegabit() {
        assertEquals(1_500_000, floorFor(6_000_000))
        assertEquals(1_000_000, floorFor(2_000_000))
        assertEquals(800_000, floorFor(800_000))
    }

    @Test
    fun congestionSignals() {
        assertFalse(congested(0.3, 40.0, 5.0))
        assertTrue(congested(2.5, 40.0, 5.0))
        assertTrue(congested(0.0, 400.0, 5.0))
        assertTrue(congested(0.0, 40.0, 150.0))
    }

    @Test
    fun stepsDownAfterSustainedCongestion() {
        val s = run(State(start), 5, congested = true)
        assertEquals(start, s.bitrate) // 1.25 s: not yet
        val down = run(s, 1, congested = true)
        assertEquals(4_500_000, down.bitrate)
        assertEquals(0L, down.congestedMs)
    }

    @Test
    fun overflowStepsDownSooner() {
        val s = run(State(start), 3, congested = true, overflowed = true)
        assertEquals(4_500_000, s.bitrate)
    }

    @Test
    fun briefCongestionIsForgotten() {
        var s = State(start)
        repeat(20) {
            s = run(s, 2, congested = true)
            s = run(s, 6, congested = false)
        }
        assertEquals(start, s.bitrate)
    }

    @Test
    fun onAndOffCongestionStillCounts() {
        var s = State(start)
        repeat(12) {
            s = run(s, 1, congested = true)
            s = run(s, 1, congested = false)
        }
        assertTrue(s.bitrate < start)
    }

    @Test
    fun neverBelowTheFloor() {
        val s = run(State(start), 200, congested = true)
        assertEquals(floor, s.bitrate)
    }

    @Test
    fun stepsUpSlowlyAndNeverAboveTheStart() {
        val low = State(3_000_000)
        assertEquals(3_000_000, run(low, 39, congested = false).bitrate) // 9.75 s
        assertEquals(3_300_000, run(low, 40, congested = false).bitrate) // 10 s
        assertEquals(start, run(low, 2_000, congested = false).bitrate)
    }

    @Test
    fun congestionResetsTheCalmTimer() {
        var s = run(State(3_000_000), 39, congested = false)
        s = run(s, 1, congested = true)
        assertEquals(0L, s.calmMs)
        assertEquals(3_000_000, run(s, 39, congested = false).bitrate)
    }

    @Test
    fun controllerAppliesChangesAndPausesWhenHidden() {
        val applied = mutableListOf<Int>()
        val c = BitrateController(start) { applied += it }
        var t = 0L
        fun frames(n: Int, pending: Int, active: Boolean = true) = repeat(n) {
            c.onFrame(t, active, pending, congestionDrops = 0, ackLatencyMs = 30, sendLatencyMs = 2); t += 33
        }
        frames(30, pending = 4) // 1 s congested
        assertTrue(applied.isEmpty())
        frames(10, pending = 0, active = false) // hidden: forgets the congestion
        frames(30, pending = 4)
        assertTrue(applied.isEmpty())
        frames(30, pending = 4)
        assertEquals(listOf(4_500_000), applied)
        assertEquals(4_500_000, c.bitrate)
    }

    @Test
    fun ackless() {
        // Head units that never ack: the channel reports it inactive, nothing ever changes.
        val applied = mutableListOf<Int>()
        val c = BitrateController(start) { applied += it }
        for (i in 0 until 300) c.onFrame(i * 33L, active = false, pending = 8, congestionDrops = i.toLong(), ackLatencyMs = 999, sendLatencyMs = 999)
        assertTrue(applied.isEmpty())
        assertEquals(start, c.bitrate)
    }
}

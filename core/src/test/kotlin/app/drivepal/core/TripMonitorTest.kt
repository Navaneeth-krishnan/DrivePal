package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TripMonitorTest {

    @Test
    fun `crawling in a traffic jam for three minutes is a standstill`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 60)
            .driveAt(kmh = 2.0, fromSec = 65, toSec = 245)

        assertEquals(TripState.Standstill, monitor.state)
    }

    @Test
    fun `waiting ninety seconds at a red light is not a standstill`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 60)
            .driveAt(kmh = 0.0, fromSec = 65, toSec = 150)

        assertEquals(TripState.Driving, monitor.state)
    }

    @Test
    fun `moving off again after a jam ends the standstill`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 2.0, fromSec = 0, toSec = 240)
            .driveAt(kmh = 40.0, fromSec = 245, toSec = 255)

        assertEquals(TripState.Driving, monitor.state)
    }

    @Test
    fun `walking around town for thirty minutes after parking asks if the driver is still on the road`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 600)
            .driveAt(kmh = 4.0, fromSec = 605, toSec = 2400)

        assertEquals(TripState.AskingStillOnTheRoad, monitor.state)
    }

    @Test
    fun `pressing play after the question keeps the trip going for another thirty minutes`() {
        val asked = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 600)
            .driveAt(kmh = 4.0, fromSec = 605, toSec = 2400)
        val playPressedAtSec = 2405

        val twentyNineMinutesLater = asked
            .withPlayPressed(timeMillis = playPressedAtSec * 1000L)
            .driveAt(kmh = 4.0, fromSec = playPressedAtSec + 5, toSec = playPressedAtSec + 29 * 60)
        val thirtyOneMinutesLater = twentyNineMinutesLater
            .driveAt(kmh = 4.0, fromSec = playPressedAtSec + 29 * 60 + 5, toSec = playPressedAtSec + 31 * 60)

        assertEquals(TripState.Standstill, twentyNineMinutesLater.state)
        assertEquals(TripState.AskingStillOnTheRoad, thirtyOneMinutesLater.state)
    }

    @Test
    fun `staying silent for two minutes after the question ends the trip`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 600)
            .driveAt(kmh = 4.0, fromSec = 605, toSec = 2400)
            .driveAt(kmh = 4.0, fromSec = 2405, toSec = 2400 + 125)

        assertEquals(TripState.Ended, monitor.state)
    }

    @Test
    fun `a tunnel without GPS while crawling does not count as standing still`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 30)
            .driveAt(kmh = 3.0, fromSec = 35, toSec = 95)
            // no fixes between 95 s and 185 s
            .driveAt(kmh = 3.0, fromSec = 185, toSec = 190)

        assertEquals(TripState.Driving, monitor.state)
    }

    @Test
    fun `driving off while the question is pending cancels the check`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 600)
            .driveAt(kmh = 4.0, fromSec = 605, toSec = 2400)
            .driveAt(kmh = 60.0, fromSec = 2405, toSec = 2420)
            .driveAt(kmh = 4.0, fromSec = 2425, toSec = 2420 + 25 * 60)

        assertEquals(TripState.Standstill, monitor.state)
    }

    @Test
    fun `losing GPS after parking indoors still asks and then ends the trip`() {
        val parked = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 100.0, fromSec = 0, toSec = 600)
        val thirtyMinutesLater = parked.withTime(nowMillis = (600 + 30 * 60) * 1000L)
        val thirtyTwoMinutesLater = thirtyMinutesLater.withTime(nowMillis = (600 + 32 * 60) * 1000L)

        assertEquals(TripState.AskingStillOnTheRoad, thirtyMinutesLater.state)
        assertEquals(TripState.Ended, thirtyTwoMinutesLater.state)
    }

    @Test
    fun `an ended trip ignores later movement`() {
        val ended = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 4.0, fromSec = 5, toSec = 2530)
            .driveAt(kmh = 100.0, fromSec = 2535, toSec = 2600)

        assertEquals(TripState.Ended, ended.state)
    }

    @Test
    fun `pressing play while standing still in a jam changes nothing`() {
        val standing = TripMonitor.start(startMillis = 0).driveAt(kmh = 2.0, fromSec = 0, toSec = 240)

        assertEquals(TripState.Standstill, standing.withPlayPressed(timeMillis = 245_000).state)
    }

    @Test
    fun `exactly two minutes at a red light is not yet a standstill`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 0.0, fromSec = 0, toSec = 120)

        assertEquals(TripState.Driving, monitor.state)
    }

    @Test
    fun `creeping along at ten km per hour while asked does not cancel the question`() {
        val monitor = TripMonitor.start(startMillis = 0)
            .driveAt(kmh = 4.0, fromSec = 5, toSec = 1800)
            .driveAt(kmh = 10.0, fromSec = 1805, toSec = 1830)

        assertEquals(TripState.AskingStillOnTheRoad, monitor.state)
    }
}

/** Feeds one fix every [everySec] seconds at a constant speed. */
private fun TripMonitor.driveAt(
    kmh: Double,
    fromSec: Int,
    toSec: Int,
    everySec: Int = 5,
): TripMonitor =
    (fromSec..toSec step everySec).fold(this) { monitor, sec ->
        monitor.withFix(Fix(timeMillis = sec * 1000L, speedKmh = kmh))
    }

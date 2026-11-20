package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AreaTrackerTest {

    private val start = Position(52.0, 4.0)
    private val sevenHundredMetresOn = Position(52.0, 4.01)
    private val threeAndAHalfKilometresOn = Position(52.0, 4.05)

    private val southHolland = Area(country = "Netherlands", province = "South Holland")

    @Test
    fun `the place a trip starts in is known but is not an entry`() {
        val tracker = AreaTracker.starting(nominatim = { SOUTH_HOLLAND }, clockMillis = { 0 })

        val update = tracker.withPosition(start)

        assertEquals(emptyList<AreaEvent>(), update.events)
        assertEquals(southHolland, update.area)
    }

    @Test
    fun `driving within a province asks Nominatim only after two kilometres and reports nothing new`() {
        var requests = 0
        val tracker = AreaTracker.starting(nominatim = { requests++; SOUTH_HOLLAND }, clockMillis = { 0 })

        val afterStart = tracker.withPosition(start)
        val afterShortDrive = afterStart.tracker.withPosition(sevenHundredMetresOn)
        val requestsAfterShortDrive = requests
        val afterLongerDrive = afterShortDrive.tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(1, requestsAfterShortDrive)
        assertEquals(2, requests)
        assertEquals(emptyList<AreaEvent>(), afterShortDrive.events + afterLongerDrive.events)
    }

    @Test
    fun `crossing into a neighbouring province enters only the province`() {
        val tracker = AreaTracker.starting(
            nominatim = { position -> if (position.longitude < 4.03) SOUTH_HOLLAND else UTRECHT },
            clockMillis = { 0 },
        )

        val events = tracker.withPosition(start).tracker.withPosition(threeAndAHalfKilometresOn).events

        assertEquals(listOf<AreaEvent>(AreaEvent.EnteredProvince("Utrecht")), events)
    }

    @Test
    fun `crossing the border enters the new country and then its province`() {
        val tracker = AreaTracker.starting(
            nominatim = { position -> if (position.longitude < 4.03) SOUTH_HOLLAND else ANTWERP },
            clockMillis = { 0 },
        )

        val events = tracker.withPosition(start).tracker.withPosition(threeAndAHalfKilometresOn).events

        assertEquals(
            listOf<AreaEvent>(AreaEvent.EnteredCountry("Belgium"), AreaEvent.EnteredProvince("Antwerp")),
            events,
        )
    }

    @Test
    fun `a failed request is not repeated for thirty seconds and then tried again`() {
        var requests = 0
        var nowMillis = 0L
        var nominatimIsDown = true
        val tracker = AreaTracker.starting(
            nominatim = {
                requests++
                if (nominatimIsDown) throw java.io.IOException("offline") else SOUTH_HOLLAND
            },
            clockMillis = { nowMillis },
        )

        val whileDown = tracker.withPosition(start)
        nowMillis = 10_000
        val tenSecondsLater = whileDown.tracker.withPosition(threeAndAHalfKilometresOn)
        val requestsAfterTenSeconds = requests
        nominatimIsDown = false
        nowMillis = 31_000
        val afterRecovery = tenSecondsLater.tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(null, whileDown.area)
        assertEquals(1, requestsAfterTenSeconds)
        // The first place it manages to learn is where the trip started, so that is still no entry.
        assertEquals(emptyList<AreaEvent>(), afterRecovery.events)
        assertEquals(southHolland, afterRecovery.area)
    }

    @Test
    fun `a real Nominatim response near Kinderdijk places the trip in the Netherlands and South Holland`() {
        val recorded = AreaTrackerTest::class.java.getResource("/real/nominatim-reverse.json")!!.readText()
        val tracker = AreaTracker.starting(nominatim = { recorded }, clockMillis = { 0 })

        assertEquals(southHolland, tracker.withPosition(Position(51.8826, 4.6403)).area)
    }

    @Test
    fun `a failed request says why, and a later success clears the problem`() {
        var nominatimIsDown = true
        var nowMillis = 0L
        val tracker = AreaTracker.starting(
            nominatim = { if (nominatimIsDown) throw java.io.IOException("HTTP 503 from nominatim.openstreetmap.org") else SOUTH_HOLLAND },
            clockMillis = { nowMillis },
        )

        val whileDown = tracker.withPosition(start)
        nominatimIsDown = false
        nowMillis = 31_000
        val afterwards = whileDown.tracker.withPosition(start)

        assertEquals("HTTP 503 from nominatim.openstreetmap.org", whileDown.problem)
        assertEquals(null, afterwards.problem)
    }

    private fun realPhoton() = AreaTrackerTest::class.java.getResource("/real/photon-reverse-kinderdijk.json")!!.readText()

    @Test
    fun `when Nominatim is down Photon names the country and province instead`() {
        val tracker = AreaTracker.starting(
            nominatim = { throw java.io.IOException("HTTP 503 from nominatim.openstreetmap.org") },
            clockMillis = { 0 },
            fallback = { realPhoton() },
        )

        val update = tracker.withPosition(start)

        assertEquals(southHolland, update.area)
        assertEquals(null, update.problem)
    }

    @Test
    fun `when Nominatim cannot place the position Photon is asked`() {
        val tracker = AreaTracker.starting(
            nominatim = { """{"error":"Unable to geocode"}""" },
            clockMillis = { 0 },
            fallback = { realPhoton() },
        )

        assertEquals(southHolland, tracker.withPosition(start).area)
    }

    @Test
    fun `Photon is left alone while Nominatim works`() {
        var photonRequests = 0
        val tracker = AreaTracker.starting(
            nominatim = { SOUTH_HOLLAND },
            clockMillis = { 0 },
            fallback = { photonRequests++; realPhoton() },
        )

        tracker.withPosition(start)

        assertEquals(0, photonRequests)
    }

    @Test
    fun `when both are down one problem says why for each, and neither is asked again for thirty seconds`() {
        var requests = 0
        var nowMillis = 0L
        val tracker = AreaTracker.starting(
            nominatim = { requests++; throw java.io.IOException("HTTP 503 from nominatim.openstreetmap.org") },
            clockMillis = { nowMillis },
            fallback = { requests++; throw java.io.IOException("HTTP 429 from photon.komoot.io") },
        )

        val first = tracker.withPosition(start)
        nowMillis = 10_000
        first.tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(emptyList<AreaEvent>(), first.events)
        assertEquals("HTTP 503 from nominatim.openstreetmap.org; Photon: HTTP 429 from photon.komoot.io", first.problem)
        assertEquals(2, requests)
    }

    @Test
    fun `entering a province from next door carries its own Wikipedia and Wikidata pages`() {
        val recorded = AreaTrackerTest::class.java.getResource("/real/nominatim-reverse-extratags.json")!!.readText()
        val tracker = AreaTracker.starting(
            nominatim = { position -> if (position.longitude < 4.03) UTRECHT else recorded },
            clockMillis = { 0 },
        )

        val update = tracker.withPosition(start).tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(
            listOf<AreaEvent>(AreaEvent.EnteredProvince("South Holland", wikipediaTag = "nl:Zuid-Holland", wikidataId = "Q694")),
            update.events,
        )
        assertEquals(Area("Netherlands", "South Holland", "nl:Zuid-Holland", "Q694"), update.area)
    }

    @Test
    fun `after crossing a border the current area is the new one`() {
        val tracker = AreaTracker.starting(
            nominatim = { position -> if (position.longitude < 4.03) SOUTH_HOLLAND else ANTWERP },
            clockMillis = { 0 },
        )

        val update = tracker.withPosition(start).tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(Area("Belgium", "Antwerp"), update.area)
    }

    private val photonFlanders = """{"features":[{"properties":{"country":"Belgium","state":"Flanders"}}]}"""

    @Test
    fun `Photon naming the area differently from Nominatim is not a border crossing`() {
        var nominatimIsDown = false
        val tracker = AreaTracker.starting(
            nominatim = { if (nominatimIsDown) throw java.io.IOException("down") else ANTWERP },
            clockMillis = { 0 },
            fallback = { photonFlanders },
        )
        val twoKilometresOn = Position(52.0, 4.05)
        val fourKilometresOn = Position(52.0, 4.10)

        val atTheStart = tracker.withPosition(start)
        nominatimIsDown = true
        val photonAnswers = atTheStart.tracker.withPosition(twoKilometresOn)
        nominatimIsDown = false
        val nominatimBack = photonAnswers.tracker.withPosition(fourKilometresOn)

        assertEquals(emptyList<AreaEvent>(), photonAnswers.events)
        assertEquals(Area("Belgium", "Flanders"), photonAnswers.area)
        assertEquals(emptyList<AreaEvent>(), nominatimBack.events)
        assertEquals(Area("Belgium", "Antwerp"), nominatimBack.area)
    }

    @Test
    fun `learning the province after only the country was known is not an entry`() {
        val tracker = AreaTracker.starting(
            nominatim = { position -> if (position.longitude < 4.03) """{"address":{"country":"Belgium"}}""" else ANTWERP },
            clockMillis = { 0 },
        )

        val update = tracker.withPosition(start).tracker.withPosition(threeAndAHalfKilometresOn)

        assertEquals(emptyList<AreaEvent>(), update.events)
        assertEquals(Area("Belgium", "Antwerp"), update.area)
    }
}

/** Shaped like a real Nominatim `reverse?format=jsonv2&addressdetails=1&zoom=5` response. */
private const val SOUTH_HOLLAND = """
{"place_id":1,"licence":"Data © OpenStreetMap contributors","osm_type":"relation","osm_id":47772,
 "lat":"52.0","lon":"4.0","category":"boundary","type":"administrative",
 "display_name":"South Holland, Netherlands",
 "address":{"state":"South Holland","ISO3166-2-lvl4":"NL-ZH","country":"Netherlands","country_code":"nl"}}
"""

private const val UTRECHT = """{"address":{"state":"Utrecht","country":"Netherlands","country_code":"nl"}}"""
private const val ANTWERP = """{"address":{"province":"Antwerp","country":"Belgium","country_code":"be"}}"""

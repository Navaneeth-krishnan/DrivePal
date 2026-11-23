package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectFinderTest {

    private fun SubjectFinder.around(position: Position) = subjectsAround(position).subjects

    /** A position well inside one 0.1-degree tile, so only that tile is needed. */
    private val insideTile = Position(latitude = 52.05, longitude = 4.05)

    @Test
    fun `entering an area makes its towns and landmarks with Wikipedia articles known`() {
        val finder = SubjectFinder(overpass = { OVERPASS_RESPONSE }, clockMillis = { 0 })

        val subjects = finder.around(insideTile)

        assertEquals(
            listOf(
                Subject(
                    id = "node/1001", name = "Example Castle", kind = SubjectKind.Landmark,
                    position = Position(52.0455, 4.0431),
                    wikipediaTag = "nl:Kasteel Voorbeeld", wikidataId = "Q111",
                ),
                Subject(
                    id = "way/2002", name = "Molen De Hoop", kind = SubjectKind.Landmark,
                    position = Position(52.01, 4.01),
                    wikipediaTag = "nl:De Hoop", wikidataId = null,
                ),
                Subject(
                    id = "node/3003", name = "Voorbeeldstad", kind = SubjectKind.Town,
                    position = Position(52.02, 4.02),
                    wikipediaTag = null, wikidataId = "Q333",
                ),
            ),
            subjects,
        )
    }

    @Test
    fun `a named junction and a village without an article are subjects that can only be announced`() {
        val finder = SubjectFinder(overpass = { ANNOUNCE_ONLY_RESPONSE }, clockMillis = { 0 })

        val subjects = finder.around(insideTile)

        assertEquals(
            listOf(
                Subject(id = "node/5005", name = "Kozhencherry", kind = SubjectKind.Junction, position = Position(52.03, 4.03)),
                Subject(id = "node/6006", name = "Eraviperoor", kind = SubjectKind.Town, position = Position(52.04, 4.04)),
            ),
            subjects,
        )
    }

    @Test
    fun `a suburb, a hamlet and a locality without an article are announced by name, as a Region pack would`() {
        val response = """
            {"elements":[
             {"type":"node","id":7007,"lat":52.041,"lon":4.041,"tags":{"place":"suburb","name":"Thottabhagam"}},
             {"type":"node","id":7008,"lat":52.042,"lon":4.042,"tags":{"place":"hamlet","name":"Muttumon"}},
             {"type":"way","id":7009,"center":{"lat":52.043,"lon":4.043},"tags":{"place":"locality","name":"Kattode"}},
             {"type":"node","id":7010,"lat":52.044,"lon":4.044,"tags":{"place":"isolated_dwelling","name":"Puthenveedu"}}
            ]}
        """
        val finder = SubjectFinder(overpass = { response }, clockMillis = { 0 })

        assertEquals(
            listOf(
                Subject(id = "node/7007", name = "Thottabhagam", kind = SubjectKind.Town, position = Position(52.041, 4.041)),
                Subject(id = "node/7008", name = "Muttumon", kind = SubjectKind.Town, position = Position(52.042, 4.042)),
                Subject(id = "way/7009", name = "Kattode", kind = SubjectKind.Town, position = Position(52.043, 4.043)),
            ),
            finder.around(insideTile),
        )
    }

    @Test
    fun `driving back through an area already seen does not ask Overpass again`() {
        var requests = 0
        val finder = SubjectFinder(overpass = { requests++; OVERPASS_RESPONSE }, clockMillis = { 0 })

        finder.around(insideTile)
        finder.around(Position(52.06, 4.05))

        assertEquals(1, requests)
    }

    @Test
    fun `driving into the next tile asks only for the new tile`() {
        var requests = 0
        val finder = SubjectFinder(overpass = { requests++; OVERPASS_RESPONSE }, clockMillis = { 0 })

        finder.around(insideTile)
        finder.around(Position(52.05, 4.15))

        assertEquals(2, requests)
    }

    @Test
    fun `a failed request is not repeated for thirty seconds and then tried again`() {
        var requests = 0
        var nowMillis = 0L
        var overpassIsDown = true
        val finder = SubjectFinder(
            overpass = {
                requests++
                if (overpassIsDown) throw java.io.IOException("Overpass is busy") else OVERPASS_RESPONSE
            },
            clockMillis = { nowMillis },
        )

        val whileDown = finder.around(insideTile)
        nowMillis = 10_000
        finder.around(insideTile)
        val requestsAfterTenSeconds = requests
        overpassIsDown = false
        nowMillis = 31_000
        val afterRecovery = finder.around(insideTile)

        assertEquals(emptyList<Subject>(), whileDown)
        assertEquals(1, requestsAfterTenSeconds)
        assertEquals(3, afterRecovery.size)
    }

    @Test
    fun `an unexpected response is treated like a failed request and does not crash`() {
        val finder = SubjectFinder(overpass = { """{"elements": 5}""" }, clockMillis = { 0 })

        assertEquals(emptyList<Subject>(), finder.around(insideTile))
    }

    @Test
    fun `a real Overpass tile around Kinderdijk yields 57 towns and landmarks`() {
        val recorded = SubjectFinderTest::class.java.getResource("/real/overpass-tile.json")!!.readText()
        val finder = SubjectFinder(overpass = { recorded }, clockMillis = { 0 })

        val subjects = finder.around(Position(51.85, 4.65))

        assertEquals(57, subjects.size)
        assertEquals(
            Subject(
                id = "node/1198516303", name = "Kinderdijk", kind = SubjectKind.Town,
                position = Position(51.8886955, 4.6356972), wikipediaTag = null, wikidataId = "Q740066",
            ),
            subjects.single { it.id == "node/1198516303" },
        )
        assertEquals(
            Subject(
                id = "node/285120714", name = "Kortlandse Molen", kind = SubjectKind.Landmark,
                position = Position(51.8728496, 4.6735427),
                wikipediaTag = "nl:Kortlandse Molen", wikidataId = "Q11721996",
            ),
            subjects.single { it.id == "node/285120714" },
        )
    }

    @Test
    fun `Overpass answering a timeout with HTTP 200 is a failure that gets retried, not an empty tile`() {
        var nowMillis = 0L
        var busy = true
        val finder = SubjectFinder(
            overpass = {
                if (busy) """{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 26 seconds."}"""
                else OVERPASS_RESPONSE
            },
            clockMillis = { nowMillis },
        )

        val whileBusy = finder.subjectsAround(insideTile)
        busy = false
        nowMillis = 31_000
        val afterwards = finder.subjectsAround(insideTile)

        assertEquals(emptyList<Subject>(), whileBusy.subjects)
        assertEquals("Overpass: runtime error: Query timed out in \"query\" at line 1 after 26 seconds.", whileBusy.problem)
        assertEquals(3, afterwards.subjects.size)
        assertEquals(null, afterwards.problem)
    }

    @Test
    fun `being near the North Pole does not ask for thousands of tiles`() {
        var requests = 0
        val finder = SubjectFinder(overpass = { requests++; """{"elements":[]}""" }, clockMillis = { 0 })

        finder.around(Position(latitude = 89.99, longitude = 10.0))

        assertEquals(true, requests <= 12)
    }

    private fun realGeoSearch() = SubjectFinderTest::class.java.getResource("/real/wikipedia-geosearch-en-kinderdijk.json")!!.readText()

    @Test
    fun `when Overpass is down the articles Wikipedia knows near the tile become the subjects`() {
        val finder = SubjectFinder(
            overpass = { throw java.io.IOException("HTTP 502 from overpass-api.de") },
            clockMillis = { 0 },
            geoSearch = { _, _ -> realGeoSearch() },
        )

        val found = finder.subjectsAround(insideTile)

        assertEquals(66, found.subjects.size)
        assertEquals(
            Subject(
                id = "en.wikipedia/74962141", name = "Nederwaard Molen No.1", kind = SubjectKind.Landmark,
                position = Position(51.884694, 4.639361), wikipediaTag = "en:Nederwaard Molen No.1", wikidataId = null,
            ),
            found.subjects.single { it.id == "en.wikipedia/74962141" },
        )
        assertEquals("Overpass failed (HTTP 502 from overpass-api.de); using Wikipedia's map search instead", found.problem)
    }

    @Test
    fun `a bracketed note in an article title is not spoken as part of the name`() {
        val finder = SubjectFinder(
            overpass = { throw java.io.IOException("down") },
            clockMillis = { 0 },
            geoSearch = { _, _ -> realGeoSearch() },
        )

        val lek = finder.subjectsAround(insideTile).subjects.single { it.id == "en.wikipedia/236578" }

        assertEquals("Lek", lek.name)
        assertEquals("en:Lek (river)", lek.wikipediaTag)
    }

    @Test
    fun `a tile served by the fallback is tried against Overpass again after a minute and the full data wins`() {
        var nowMillis = 0L
        var overpassIsDown = true
        val finder = SubjectFinder(
            overpass = { if (overpassIsDown) throw java.io.IOException("down") else OVERPASS_RESPONSE },
            clockMillis = { nowMillis },
            geoSearch = { _, _ -> GEOSEARCH_RESPONSE },
        )

        val whileDown = finder.subjectsAround(insideTile)
        overpassIsDown = false
        nowMillis = 59_000
        val stillFallback = finder.subjectsAround(insideTile)
        nowMillis = 60_001
        val afterwards = finder.subjectsAround(insideTile)

        val temple = "Parthasarathy Temple"
        assertEquals(listOf("Voorbeeldstad", "Example Castle", temple, "Molen De Hoop"), whileDown.subjects.map { it.name })
        assertEquals(whileDown.subjects, stillFallback.subjects)
        assertEquals(listOf("Example Castle", "Molen De Hoop", "Voorbeeldstad", temple), afterwards.subjects.map { it.name })
        assertEquals(null, afterwards.problem)
    }

    @Test
    fun `Wikipedia's map search adds the landmarks Overpass does not know, even while Overpass works`() {
        val finder = SubjectFinder(
            overpass = { OVERPASS_RESPONSE },
            clockMillis = { 0 },
            geoSearch = { _, _ -> GEOSEARCH_RESPONSE },
        )

        val found = finder.subjectsAround(insideTile)

        // Voorbeeldstad, Example Castle and Molen De Hoop are Overpass's already; the school and the constituency are no landmarks.
        assertEquals(listOf("Example Castle", "Molen De Hoop", "Voorbeeldstad", "Parthasarathy Temple"), found.subjects.map { it.name })
        assertEquals(
            Subject("en.wikipedia/3", "Parthasarathy Temple", SubjectKind.Landmark, Position(52.04, 4.04), wikipediaTag = "en:Parthasarathy Temple"),
            found.subjects.last(),
        )
        assertEquals(null, found.problem)
    }

    @Test
    fun `when only Wikipedia's map search fails the Overpass subjects are kept and it is asked again after a minute`() {
        var nowMillis = 0L
        var searchIsDown = true
        val finder = SubjectFinder(
            overpass = { OVERPASS_RESPONSE },
            clockMillis = { nowMillis },
            geoSearch = { _, _ -> if (searchIsDown) throw java.io.IOException("HTTP 429 from en.wikipedia.org") else GEOSEARCH_RESPONSE },
        )

        val whileDown = finder.subjectsAround(insideTile)
        searchIsDown = false
        nowMillis = 61_000
        val afterwards = finder.subjectsAround(insideTile)

        assertEquals(3, whileDown.subjects.size)
        assertEquals("Wikipedia geosearch: HTTP 429 from en.wikipedia.org", whileDown.problem)
        assertEquals(4, afterwards.subjects.size)
        assertEquals(null, afterwards.problem)
    }

    @Test
    fun `when both are down one problem says why for each`() {
        val finder = SubjectFinder(
            overpass = { throw java.io.IOException("HTTP 502 from overpass-api.de") },
            clockMillis = { 0 },
            geoSearch = { _, _ -> throw java.io.IOException("HTTP 429 from en.wikipedia.org") },
        )

        val found = finder.subjectsAround(insideTile)

        assertEquals(emptyList<Subject>(), found.subjects)
        assertEquals("HTTP 502 from overpass-api.de; Wikipedia geosearch: HTTP 429 from en.wikipedia.org", found.problem)
    }

    /** 4.4 km below the top edge of its tile: the next tile north is beyond the 3 km reach, but within 5 km. */
    private val belowTheNorthEdge = Position(latitude = 52.06, longitude = 4.05)

    @Test
    fun `the tile ahead is loaded before the driver gets there`() {
        var requests = 0
        val finder = SubjectFinder(overpass = { requests++; OVERPASS_RESPONSE }, clockMillis = { 0 })

        finder.subjectsAround(belowTheNorthEdge, headingDegrees = 0.0)
        val whileStillInTheFirstTile = requests
        finder.subjectsAround(Position(52.095, 4.05), headingDegrees = 0.0)

        assertEquals(2, whileStillInTheFirstTile)
        assertEquals(2, requests)
    }

    @Test
    fun `only the tile around the driver is loaded when there is no heading yet or the road leads away from the next tile`() {
        var requests = 0
        val finder = SubjectFinder(overpass = { requests++; OVERPASS_RESPONSE }, clockMillis = { 0 })

        finder.subjectsAround(belowTheNorthEdge)
        val withoutHeading = requests
        finder.subjectsAround(belowTheNorthEdge, headingDegrees = 180.0)

        assertEquals(1, withoutHeading)
        assertEquals(1, requests)
    }
}

/** A named junction, an unnamed one (ignored), a village with no Wikipedia link, and an unnamed village (ignored). */
private const val GEOSEARCH_RESPONSE = """
{"query":{"geosearch":[
 {"pageid":1,"ns":0,"title":"Voorbeeldstad","lat":52.02,"lon":4.02,"dist":100.0},
 {"pageid":2,"ns":0,"title":"Example Castle","lat":52.0455,"lon":4.0431,"dist":200.0},
 {"pageid":3,"ns":0,"title":"Parthasarathy Temple","lat":52.04,"lon":4.04,"dist":300.0},
 {"pageid":4,"ns":0,"title":"St. Mary's Girls High School, Kozhencherry","lat":52.05,"lon":4.05,"dist":400.0},
 {"pageid":5,"ns":0,"title":"Aranmula Assembly constituency","lat":52.06,"lon":4.06,"dist":500.0},
 {"pageid":6,"ns":0,"title":"Molen De Hoop (windmill)","lat":52.01,"lon":4.01,"dist":600.0}
]}}
"""

private const val ANNOUNCE_ONLY_RESPONSE = """
{"version":0.6,"elements":[
 {"type":"node","id":5005,"lat":52.03,"lon":4.03,"tags":{"highway":"motorway_junction","name":"Kozhencherry","ref":"12"}},
 {"type":"node","id":5006,"lat":52.031,"lon":4.031,"tags":{"highway":"motorway_junction","ref":"13"}},
 {"type":"node","id":6006,"lat":52.04,"lon":4.04,"tags":{"place":"village","name":"Eraviperoor"}},
 {"type":"node","id":6007,"lat":52.041,"lon":4.041,"tags":{"place":"village"}}
]}
"""

/** Shaped like a real Overpass `out center tags` response. The plaque has no Wikipedia link and is ignored. */
private const val OVERPASS_RESPONSE = """
{"version":0.6,"generator":"Overpass API","elements":[
 {"type":"node","id":1001,"lat":52.0455,"lon":4.0431,
  "tags":{"historic":"castle","name":"Kasteel Voorbeeld","name:en":"Example Castle","wikipedia":"nl:Kasteel Voorbeeld","wikidata":"Q111"}},
 {"type":"way","id":2002,"center":{"lat":52.01,"lon":4.01},
  "tags":{"man_made":"windmill","name":"Molen De Hoop","wikipedia":"nl:De Hoop"}},
 {"type":"node","id":3003,"lat":52.02,"lon":4.02,
  "tags":{"place":"town","name":"Voorbeeldstad","wikidata":"Q333"}},
 {"type":"node","id":4004,"lat":52.03,"lon":4.03,
  "tags":{"historic":"memorial","name":"Plaque"}}
]}
"""

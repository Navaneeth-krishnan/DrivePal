package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointsTest {

    private val kinderdijk = Position(51.8886955, 4.6356972)

    @Test
    fun `Nominatim is asked for the province's own Wikipedia and Wikidata tags`() {
        val url = Endpoints.nominatimReverse(kinderdijk)

        assertTrue("extratags=1" in url)
        assertTrue("zoom=5" in url)
        assertTrue("addressdetails=1" in url)
    }

    @Test
    fun `Nominatim is asked for the province's names in other languages`() {
        assertTrue("namedetails=1" in Endpoints.nominatimReverse(kinderdijk))
    }

    @Test
    fun `the position sent to Nominatim and Photon is blurred to about a kilometre`() {
        for (url in listOf(Endpoints.nominatimReverse(kinderdijk), Endpoints.photonReverse(kinderdijk))) {
            assertTrue("lat=51.89&lon=4.64" in url || "lon=4.64&lat=51.89" in url, url)
            assertFalse("51.8886" in url, url)
            assertFalse("4.6356" in url, url)
        }
    }

    @Test
    fun `positions south and west of zero are written with a decimal point, whatever the phone's language`() {
        val sydney = Position(-33.8688, 151.2093)

        assertTrue("lat=-33.87&lon=151.21" in Endpoints.nominatimReverse(sydney))
    }

    @Test
    fun `the Wikipedia geosearch asks for English articles around the tile centre`() {
        val url = Endpoints.wikipediaGeoSearch(Position(51.85, 4.65), radiusMeters = 8_000)

        assertTrue(url.startsWith("https://en.wikipedia.org/"))
        assertTrue("gscoord=51.850%7C4.650" in url)
        assertTrue("gsradius=8000" in url)
    }
}

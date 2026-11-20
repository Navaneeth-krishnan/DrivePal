package app.drivepal.core

import java.util.Locale

/**
 * The addresses of the public map services. Built here, not in the Android code, so that what is asked for
 * and how precisely the driver's position is revealed can be tested.
 */
object Endpoints {

    /**
     * Nominatim at zoom 5 names the province itself, and `extratags` adds that province's own Wikipedia and Wikidata
     * pages, which make an exact Source. The position is blurred to about a kilometre: only the province is needed.
     */
    fun nominatimReverse(position: Position): String =
        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=5&addressdetails=1&extratags=1&accept-language=en" +
            "&lat=${blurred(position.latitude)}&lon=${blurred(position.longitude)}"

    /** The fallback for Nominatim, with the same blurring. */
    fun photonReverse(position: Position): String =
        "https://photon.komoot.io/reverse?lang=en&lat=${blurred(position.latitude)}&lon=${blurred(position.longitude)}"

    /** English Wikipedia's articles about places near [center] (the middle of a map tile, not the driver's position). */
    fun wikipediaGeoSearch(center: Position, radiusMeters: Int): String =
        "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gslimit=100&format=json&formatversion=2" +
            "&gscoord=${decimals(center.latitude, 3)}%7C${decimals(center.longitude, 3)}&gsradius=$radiusMeters"

    private fun blurred(degrees: Double) = decimals(degrees, 2)

    private fun decimals(degrees: Double, places: Int) = String.format(Locale.US, "%.${places}f", degrees)
}

package app.drivepal.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos

/** A point on Earth, in degrees. */
data class Position(val latitude: Double, val longitude: Double)

/** Straight-line distance over the Earth's surface (haversine). */
internal fun distanceMeters(from: Position, to: Position): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

/**
 * Shortest distance from [point] to the straight path from [from] to [to]. Uses a flat approximation
 * around [from], which is accurate to well under a metre over the few kilometres between GPS fixes.
 */
internal fun distanceToPathMeters(point: Position, from: Position, to: Position): Double {
    val metersPerDegreeLongitude = METERS_PER_DEGREE * cos(Math.toRadians(from.latitude))
    fun x(p: Position) = (p.longitude - from.longitude) * metersPerDegreeLongitude
    fun y(p: Position) = (p.latitude - from.latitude) * METERS_PER_DEGREE
    val (px, py, dx, dy) = listOf(x(point), y(point), x(to), y(to))
    val lengthSquared = dx * dx + dy * dy
    val along = if (lengthSquared == 0.0) 0.0 else ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return sqrt((px - along * dx) * (px - along * dx) + (py - along * dy) * (py - along * dy))
}

/** Compass direction from one point to another, 0 = north, 90 = east, in [0, 360). */
internal fun bearingDegrees(from: Position, to: Position): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val METERS_PER_DEGREE = 111_195.0

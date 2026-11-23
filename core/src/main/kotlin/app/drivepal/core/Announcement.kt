package app.drivepal.core

/**
 * The line spoken before a Story, or on its own in Names mode: which place this is, with no description. [relation] says
 * whether the place is still ahead of the driver or already behind (Areas have none: they are entered). Null if the
 * kind is not announced (an Interlude is about a place the driver is already in). A Landmark, which is announced from
 * further away than other places, also says how far it is ([distanceMeters], when known).
 */
fun announcement(kind: StoryKind, name: String, relation: Relation?, distanceMeters: Double? = null): String? {
    val behind = relation == Relation.Behind
    return when (kind) {
        StoryKind.Country, StoryKind.Province -> "You are entering $name."
        StoryKind.Town -> if (behind) "You just passed $name." else "You are entering $name."
        StoryKind.Landmark -> {
            val away = distanceMeters?.let { ", about ${spokenDistance(it)} ${if (behind) "back" else "away"}" }.orEmpty()
            if (behind) "You just passed $name$away." else "Coming up: $name$away."
        }
        StoryKind.Junction -> if (behind) "You just passed $name junction." else "Approaching $name junction."
        StoryKind.Interlude -> null
    }
}

/** Under about a kilometre to the nearest 50 metres, from there to the nearest half kilometre. */
private fun spokenDistance(meters: Double): String {
    if (meters < METRES_UNTIL_KILOMETRES) return "${maxOf(50, Math.round(meters / 50.0).toInt() * 50)} metres"
    val halves = Math.round(meters / 500.0).toInt()
    return if (halves % 2 == 0) "${halves / 2} ${if (halves == 2) "kilometre" else "kilometres"}" else "${halves / 2}.5 kilometres"
}

private const val METRES_UNTIL_KILOMETRES = 975.0

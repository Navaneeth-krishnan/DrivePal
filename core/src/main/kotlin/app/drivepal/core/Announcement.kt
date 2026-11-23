package app.drivepal.core

/** The language DrivePal speaks its Announcements in. [code] is the language's tag, which also picks the voice. */
enum class SpokenLanguage(val code: String) {
    English("en"),
    Malayalam("ml"),
}

/**
 * The line spoken before a Story, or on its own in Names mode: which place this is, with no description. [relation] says
 * whether the place is still ahead of the driver or already behind (Areas have none: they are entered). Null if the
 * kind is not announced (an Interlude is about a place the driver is already in). A Landmark, which is announced from
 * further away than other places, also says how far it is ([distanceMeters], when known).
 *
 * In Malayalam the [name] stands alone after a short label ("അടുത്ത സ്ഥലം: തിരുവല്ല."), so that it needs no case
 * ending, which could not be added to a name written in English letters.
 */
fun announcement(
    kind: StoryKind,
    name: String,
    relation: Relation?,
    distanceMeters: Double? = null,
    language: SpokenLanguage = SpokenLanguage.English,
): String? = when (language) {
    SpokenLanguage.English -> english(kind, name, relation == Relation.Behind, distanceMeters)
    SpokenLanguage.Malayalam -> malayalam(kind, name, relation == Relation.Behind, distanceMeters)
}

private fun english(kind: StoryKind, name: String, behind: Boolean, distanceMeters: Double?): String? = when (kind) {
    StoryKind.Country, StoryKind.Province -> "You are entering $name."
    StoryKind.Town -> if (behind) "You just passed $name." else "You are entering $name."
    StoryKind.Landmark -> {
        val away = distanceMeters?.let { ", about ${englishDistance(it)} ${if (behind) "back" else "away"}" }.orEmpty()
        if (behind) "You just passed $name$away." else "Coming up: $name$away."
    }
    StoryKind.Junction -> if (behind) "You just passed $name junction." else "Approaching $name junction."
    StoryKind.Interlude -> null
}

private fun malayalam(kind: StoryKind, name: String, behind: Boolean, distanceMeters: Double?): String? = when (kind) {
    StoryKind.Country -> "പുതിയ രാജ്യം: $name."
    StoryKind.Province -> "പുതിയ സംസ്ഥാനം: $name."
    StoryKind.Town -> if (behind) "ഇപ്പോൾ കടന്നുപോയത്: $name." else "അടുത്ത സ്ഥലം: $name."
    StoryKind.Landmark -> {
        val away = distanceMeters?.let { ", ഏകദേശം ${malayalamDistance(it)} അകലെ" }.orEmpty()
        if (behind) "പിന്നിൽ: $name$away." else "മുന്നിൽ: $name$away."
    }
    StoryKind.Junction -> if (behind) "ഇപ്പോൾ കടന്നുപോയ ജംഗ്ഷൻ: $name." else "അടുത്ത ജംഗ്ഷൻ: $name."
    StoryKind.Interlude -> null
}

/** Under about a kilometre to the nearest 50 metres, from there to the nearest half kilometre. */
private fun englishDistance(meters: Double): String {
    if (meters < METRES_UNTIL_KILOMETRES) return "${nearestFiftyMetres(meters)} metres"
    val halves = halfKilometres(meters)
    return if (halves % 2 == 0) "${halves / 2} ${if (halves == 2) "kilometre" else "kilometres"}" else "${halves / 2}.5 kilometres"
}

/** The same rounding; kilometres in words ("ഒന്നര കിലോമീറ്റർ"), as far as Landmarks are announced and a little beyond. */
private fun malayalamDistance(meters: Double): String {
    if (meters < METRES_UNTIL_KILOMETRES) return "${nearestFiftyMetres(meters)} മീറ്റർ"
    val halves = halfKilometres(meters)
    val words = MALAYALAM_HALF_KILOMETRES[halves] ?: if (halves % 2 == 0) "${halves / 2}" else "${halves / 2}.5"
    return "$words കിലോമീറ്റർ"
}

private fun nearestFiftyMetres(meters: Double) = maxOf(50, Math.round(meters / 50.0).toInt() * 50)

private fun halfKilometres(meters: Double) = Math.round(meters / 500.0).toInt()

private const val METRES_UNTIL_KILOMETRES = 975.0

/** Kilometres in half steps, from one to three and a half. */
private val MALAYALAM_HALF_KILOMETRES = mapOf(
    2 to "ഒരു",
    3 to "ഒന്നര",
    4 to "രണ്ട്",
    5 to "രണ്ടര",
    6 to "മൂന്ന്",
    7 to "മൂന്നര",
)

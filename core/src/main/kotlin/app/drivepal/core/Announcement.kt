package app.drivepal.core

/**
 * The line spoken before a Story, or on its own in Names mode: which place this is, with no description. [relation] says
 * whether the place is still ahead of the driver or already behind (Areas have none: they are entered). Null if the
 * kind is not announced (an Interlude is about a place the driver is already in).
 */
fun announcement(kind: StoryKind, name: String, relation: Relation?): String? {
    val behind = relation == Relation.Behind
    return when (kind) {
        StoryKind.Country, StoryKind.Province -> "You are entering $name."
        StoryKind.Town -> if (behind) "You just passed $name." else "You are entering $name."
        StoryKind.Landmark -> if (behind) "You just passed $name." else "Coming up: $name."
        StoryKind.Junction -> if (behind) "You just passed $name junction." else "Approaching $name junction."
        StoryKind.Interlude -> null
    }
}

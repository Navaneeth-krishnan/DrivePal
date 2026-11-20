package app.drivepal

import app.drivepal.core.AreaEvent
import app.drivepal.core.StoryResult
import app.drivepal.core.Subject
import app.drivepal.core.SourceLookup

/** One line for the screen about something DrivePal noticed. */
fun describe(event: AreaEvent): String = when (event) {
    is AreaEvent.EnteredCountry -> "Entered country: ${event.name}"
    is AreaEvent.EnteredProvince -> "Entered province: ${event.name}"
}

fun describePassing(subject: Subject, lookup: SourceLookup): String {
    val what = "${subject.name} (${subject.kind.name.lowercase()})"
    return when (lookup) {
        is SourceLookup.Found ->
            "Passing $what: Story-worthy, ${lookup.source.notability.languageEditions} language editions, " +
                "${lookup.source.notability.words} words"
        SourceLookup.NotStoryWorthy -> "Passing $what: no Story (no article, or too thin)"
        is SourceLookup.Unavailable -> "Passing $what: Source lookup failed (${lookup.reason}), will retry"
    }
}

fun describeStory(subject: Subject, result: StoryResult): String = when (result) {
    is StoryResult.Written -> "${subject.name}: ${result.text}"
    is StoryResult.Fallback -> "${subject.name} (the article itself, because: ${result.reason}): ${result.text}"
}

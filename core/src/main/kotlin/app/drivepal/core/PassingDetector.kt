package app.drivepal.core

/** A Junction is announced by name only; it never has a Source. */
enum class SubjectKind { Town, Landmark, Province, Country, Junction }

/** A Town or Landmark that might get a Story. */
data class Subject(
    val id: String,
    val name: String,
    val kind: SubjectKind,
    val position: Position,
    /** The OpenStreetMap `wikipedia` tag, such as "nl:Kasteel Voorbeeld". Where the Source comes from. */
    val wikipediaTag: String? = null,
    /** The OpenStreetMap `wikidata` tag, such as "Q111". */
    val wikidataId: String? = null,
)

sealed interface PassingEvent {
    val subject: Subject

    /** The route has come near [subject]: within the Passing range. */
    data class Passing(override val subject: Subject) : PassingEvent
}

data class PassingUpdate(val detector: PassingDetector, val events: List<PassingEvent>)

/**
 * Follows the driver's position and reports when a Town or Landmark is Passed.
 * Immutable: every new position returns an updated detector and the events it caused.
 */
@ConsistentCopyVisibility
data class PassingDetector private constructor(
    private val subjects: List<Subject>,
    private val passedIds: Set<String>,
    private val position: Position?,
    /** Compass heading (0 = north) worked out from the last two positions; null until the driver has moved. */
    val headingDegrees: Double?,
    /** The last position the heading was measured from; GPS noise near it is ignored. */
    private val headingAnchor: Position?,
    /** Where the Trip began. Places within range of it are already around, so they are never announced. */
    private val startPosition: Position?,
) {
    /** Subjects still close enough to talk about: within the Passing range, ahead or behind. */
    val relevantSubjects: List<Subject>
        get() = position?.let { here -> subjects.filter { distanceMeters(here, it.position) <= rangeMeters(it) } }
            ?: emptyList()

    /** True if [subject] lies in front of the driver. With no heading yet, everything counts as ahead. */
    fun isAhead(subject: Subject): Boolean {
        val here = position ?: return true
        val heading = headingDegrees ?: return true
        val offTheNose = Math.abs(((bearingDegrees(here, subject.position) - heading + 540.0) % 360.0) - 180.0)
        return offTheNose <= AHEAD_WITHIN_DEGREES
    }

    /** Replaces the subjects being watched (new map tiles loaded). Subjects already Passed stay announced. */
    fun withSubjects(newSubjects: List<Subject>): PassingDetector = copy(subjects = newSubjects)

    /** True if [subject] was already within range where the Trip began (judged from the first position). */
    private fun aroundTheStart(subject: Subject, now: Position): Boolean =
        distanceMeters(startPosition ?: now, subject.position) <= rangeMeters(subject)

    /** How close the route came to [subject], counting the straight path since the last position. */
    private fun distanceFromRoute(subject: Subject, now: Position): Double =
        this.position?.let { distanceToPathMeters(subject.position, it, now) } ?: distanceMeters(now, subject.position)

    fun withPosition(position: Position): PassingUpdate {
        val newlyPassed = subjects.filter {
            it.id !in passedIds && !aroundTheStart(it, position) && distanceFromRoute(it, position) <= rangeMeters(it)
        }
        val anchor = headingAnchor
        val moved = anchor != null && distanceMeters(anchor, position) >= HEADING_MIN_MOVE_METERS
        return PassingUpdate(
            detector = copy(
                passedIds = passedIds + newlyPassed.map { it.id },
                position = position,
                headingDegrees = if (moved) bearingDegrees(anchor, position) else headingDegrees,
                headingAnchor = if (anchor == null || moved) position else anchor,
                startPosition = startPosition ?: position,
            ),
            events = newlyPassed.map { PassingEvent.Passing(it) },
        )
    }

    companion object {
        private const val PASSING_RANGE_METERS = 3_000.0
        private const val JUNCTION_RANGE_METERS = 300.0 // a junction is a point on the road, not a place in sight

        private fun rangeMeters(subject: Subject) =
            if (subject.kind == SubjectKind.Junction) JUNCTION_RANGE_METERS else PASSING_RANGE_METERS
        private const val HEADING_MIN_MOVE_METERS = 25.0 // above typical GPS noise
        private const val AHEAD_WITHIN_DEGREES = 90.0

        fun watching(subjects: List<Subject>): PassingDetector =
            PassingDetector(subjects, passedIds = emptySet(), position = null, headingDegrees = null, headingAnchor = null, startPosition = null)
    }
}

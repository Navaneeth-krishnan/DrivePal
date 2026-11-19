package app.drivepal.core

/**
 * One GPS reading. Only what the monitor needs: when, and how fast.
 * [timeMillis] must come from the same clock as every other time given to the monitor.
 */
data class Fix(val timeMillis: Long, val speedKmh: Double)

enum class TripState { Driving, Standstill, AskingStillOnTheRoad, Ended }

/**
 * Watches a Trip and reports whether the driver is driving, in a Standstill,
 * needs to be asked if they are still on the road, or the Trip has ended.
 * Immutable: every input returns a new monitor.
 *
 * Time moves forward with every fix and with [withTime], so the question and the end of the
 * Trip still happen when GPS goes quiet.
 */
@ConsistentCopyVisibility
data class TripMonitor private constructor(
    val state: TripState,
    private val lowSpeedSinceMillis: Long?,
    private val lastDrivingMillis: Long,
    private val askedAtMillis: Long?,
    private val lastFixMillis: Long?,
) {
    fun withFix(fix: Fix): TripMonitor {
        if (state == TripState.Ended) return this

        val afterGap = lastFixMillis != null && fix.timeMillis - lastFixMillis > GPS_GAP_MILLIS
        val seen = copy(
            lastFixMillis = fix.timeMillis,
            lowSpeedSinceMillis = if (afterGap) null else lowSpeedSinceMillis,
            lastDrivingMillis = if (fix.speedKmh >= DRIVING_FROM_KMH) fix.timeMillis else lastDrivingMillis,
        )
        return if (seen.questionIsDue(fix.timeMillis)) seen.withTime(fix.timeMillis) else seen.afterSpeedOf(fix)
    }

    /** Time has passed without a fix: apply the question and the end-of-Trip rules. */
    fun withTime(nowMillis: Long): TripMonitor {
        if (state == TripState.Ended || !questionIsDue(nowMillis)) return this
        val askedAt = askedAtMillis ?: nowMillis
        val unanswered = nowMillis - askedAt >= ANSWER_WITHIN_MILLIS
        return copy(
            state = if (unanswered) TripState.Ended else TripState.AskingStillOnTheRoad,
            askedAtMillis = askedAt,
        )
    }

    /** The driver pressed play. Answers the Still-on-the-road check; otherwise changes nothing here. */
    fun withPlayPressed(timeMillis: Long): TripMonitor =
        if (state == TripState.AskingStillOnTheRoad) {
            copy(
                state = TripState.Driving,
                lowSpeedSinceMillis = null,
                lastDrivingMillis = timeMillis,
                askedAtMillis = null,
            )
        } else {
            this
        }

    private fun questionIsDue(nowMillis: Long): Boolean = nowMillis - lastDrivingMillis >= ASK_AFTER_MILLIS

    private fun afterSpeedOf(fix: Fix): TripMonitor {
        if (fix.speedKmh >= STANDSTILL_BELOW_KMH) {
            return copy(state = TripState.Driving, lowSpeedSinceMillis = null, askedAtMillis = null)
        }
        val since = lowSpeedSinceMillis ?: fix.timeMillis
        val standing = fix.timeMillis - since > STANDSTILL_AFTER_MILLIS
        return copy(
            state = if (standing) TripState.Standstill else state,
            lowSpeedSinceMillis = since,
            askedAtMillis = null,
        )
    }

    companion object {
        private const val STANDSTILL_BELOW_KMH = 5.0
        private const val STANDSTILL_AFTER_MILLIS = 2 * 60_000L
        private const val DRIVING_FROM_KMH = 15.0
        private const val ASK_AFTER_MILLIS = 30 * 60_000L
        private const val ANSWER_WITHIN_MILLIS = 2 * 60_000L
        private const val GPS_GAP_MILLIS = 30_000L

        fun start(startMillis: Long): TripMonitor = TripMonitor(
            state = TripState.Driving,
            lowSpeedSinceMillis = null,
            lastDrivingMillis = startMillis,
            askedAtMillis = null,
            lastFixMillis = null,
        )
    }
}

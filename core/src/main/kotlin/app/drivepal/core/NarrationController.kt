package app.drivepal.core

/** Something to say aloud: a Story, or (when [story] is false) a system message such as the Still-on-the-road question. */
data class Utterance(
    val id: String,
    val text: String,
    val language: String,
    val story: Boolean = true,
    /** What to call it on screen, such as the name of the town. */
    val title: String = id,
    /** For the Trip log: what kind of Story it is, and the Wikipedia article it came from (language and title). */
    val kind: StoryKind? = null,
    val sourceLanguage: String? = null,
    val sourceTitle: String? = null,
    /** Said before the [text], such as "You are entering Eraviperoor." With an empty [text] the name is all that is said. */
    val announcement: String? = null,
) {
    /** Everything that is read aloud, in order. */
    val spokenText: String get() = listOfNotNull(announcement, text.takeIf { it.isNotBlank() }).joinToString(" ")
}

/** What became of a Story. Told and Skipped both mean the driver is done with it; the others can come back later. */
enum class StoryOutcome { Told, Skipped, Interrupted, Dropped }

sealed interface NarrationEvent {
    data class StoryReady(val utterance: Utterance) : NarrationEvent
    data class QuestionReady(val utterance: Utterance) : NarrationEvent
    data class SpeechProgress(val speakId: String, val spokenChars: Int) : NarrationEvent
    data class SpeechDone(val speakId: String) : NarrationEvent
    data class SpeechFailed(val speakId: String) : NarrationEvent

    /** The text is in a language no installed voice speaks, so it cannot be told at all. */
    data class SpeechUnavailable(val speakId: String) : NarrationEvent

    /** [permanent] is true when another app has taken the audio for good, false for a short interruption (a call, a navigation prompt). */
    data class FocusLost(val permanent: Boolean) : NarrationEvent
    data object FocusGained : NarrationEvent

    /** The system refused the audio request outright (a call in progress, say), and will not grant it later. */
    data object FocusDenied : NarrationEvent

    /** The Still-on-the-road question was answered, so it must not be asked (again). */
    data object QuestionCancelled : NarrationEvent
    data object Skip : NarrationEvent
    data object Replay : NarrationEvent
}

sealed interface NarrationCommand {
    data object RequestFocus : NarrationCommand
    data object AbandonFocus : NarrationCommand
    data object StopSpeaking : NarrationCommand
    data class Speak(val speakId: String, val text: String, val language: String) : NarrationCommand
    data class Outcome(val utterance: Utterance, val outcome: StoryOutcome) : NarrationCommand
}

data class NarrationStep(val controller: NarrationController, val commands: List<NarrationCommand>)

/**
 * Decides what the speech engine and the audio focus should do, and what became of each Story.
 * Knows nothing about Android: events go in, commands come out. Immutable.
 *
 * [isRelevant] says whether a Story is still worth telling (the driver may have driven on while it waited).
 */
@ConsistentCopyVisibility
data class NarrationController private constructor(
    private val isRelevant: (Utterance) -> Boolean,
    private val queue: List<Utterance>,
    private val current: Playing?,
    private val paused: Playing?,
    private val lastStory: Utterance?,
    private val holdsFocus: Boolean,
    /** An audio request is registered with the system (granted, or promised for later) and must be given back. */
    private val focusRequested: Boolean,
    private val speaks: Int,
) {
    /** [text] is what was handed to the speech engine this time (it may be the rest of a Story, after an interruption). */
    private data class Playing(
        val utterance: Utterance,
        val speakId: String,
        val text: String,
        val spokenChars: Int,
        val replay: Boolean,
    )

    /** True while something is being told, or was cut off and is waiting to continue. */
    val isActive: Boolean get() = current != null || paused != null

    fun handle(event: NarrationEvent): NarrationStep = when (event) {
        is NarrationEvent.StoryReady -> enqueued(event.utterance, atFront = false)
        is NarrationEvent.QuestionReady -> enqueued(event.utterance, atFront = true)
        is NarrationEvent.SpeechProgress -> progressed(event)
        is NarrationEvent.SpeechDone -> finished(event.speakId, StoryOutcome.Told)
        is NarrationEvent.SpeechFailed -> finished(event.speakId, StoryOutcome.Interrupted)
        is NarrationEvent.SpeechUnavailable -> finished(event.speakId, StoryOutcome.Dropped)
        is NarrationEvent.FocusLost -> focusLost(event.permanent)
        NarrationEvent.FocusGained -> focusGained()
        NarrationEvent.FocusDenied -> focusDenied()
        NarrationEvent.QuestionCancelled -> questionCancelled()
        NarrationEvent.Skip -> skipped()
        NarrationEvent.Replay -> replayed()
    }

    private fun enqueued(utterance: Utterance, atFront: Boolean): NarrationStep {
        val queued = copy(queue = if (atFront) listOf(utterance) + queue else queue + utterance)
        return if (current == null && paused == null) queued.playNext() else NarrationStep(queued, emptyList())
    }

    private fun progressed(event: NarrationEvent.SpeechProgress): NarrationStep =
        if (current?.speakId == event.speakId) {
            NarrationStep(copy(current = current.copy(spokenChars = event.spokenChars)), emptyList())
        } else {
            NarrationStep(this, emptyList())
        }

    /** The current utterance ended: [outcome] is Told for a normal finish and Interrupted for an engine failure. */
    private fun finished(speakId: String, outcome: StoryOutcome): NarrationStep {
        val playing = current?.takeIf { it.speakId == speakId } ?: return NarrationStep(this, emptyList()) // a late callback
        val outcomes = outcomeFor(playing, outcome)
        val next = copy(
            current = null,
            lastStory = if (playing.utterance.story && !playing.replay && outcome == StoryOutcome.Told) playing.utterance else lastStory,
        ).playNext()
        return next.copy(commands = outcomes + next.commands)
    }

    private fun skipped(): NarrationStep {
        val playing = current
        if (playing != null) {
            if (!playing.utterance.story) return NarrationStep(this, emptyList()) // the question cannot be skipped
            val next = copy(current = null, lastStory = if (playing.replay) lastStory else playing.utterance).playNext()
            return next.copy(commands = listOf(NarrationCommand.StopSpeaking) + outcomeFor(playing, StoryOutcome.Skipped) + next.commands)
        }
        val waiting = paused ?: return NarrationStep(this, emptyList()) // nothing is being told: not ours to skip
        val next = copy(paused = null, lastStory = if (waiting.replay) lastStory else waiting.utterance).playNext()
        return next.copy(commands = outcomeFor(waiting, StoryOutcome.Skipped) + next.commands)
    }

    private fun focusLost(permanent: Boolean): NarrationStep {
        val interrupted = current ?: paused ?: return NarrationStep(copy(holdsFocus = false), emptyList())
        val stop = if (current != null) listOf(NarrationCommand.StopSpeaking) else emptyList()
        if (!permanent) {
            return NarrationStep(copy(current = null, paused = interrupted, holdsFocus = false), stop)
        }
        val dropped = queue.filter { it.story }.map { NarrationCommand.Outcome(it, StoryOutcome.Dropped) }
        val commands = stop + outcomeFor(interrupted, StoryOutcome.Interrupted) + dropped
        return NarrationStep(copy(current = null, paused = null, queue = emptyList(), holdsFocus = false, focusRequested = false), commands)
    }

    private fun focusGained(): NarrationStep {
        // Audio promised for later arriving after the story was skipped or dropped: hand it straight back.
        val waiting = paused ?: return if (current == null) {
            NarrationStep(copy(holdsFocus = false, focusRequested = false), listOf(NarrationCommand.AbandonFocus))
        } else {
            NarrationStep(this, emptyList())
        }
        val regained = copy(paused = null, holdsFocus = true)
        if (waiting.utterance.story && !waiting.replay && !isRelevant(waiting.utterance)) {
            val next = regained.playNext()
            return next.copy(commands = outcomeFor(waiting, StoryOutcome.Interrupted) + next.commands)
        }
        val number = speaks + 1
        val rest = waiting.text.substring(sentenceStart(waiting.text, waiting.spokenChars))
        val speakId = "${waiting.utterance.id}#$number"
        return NarrationStep(
            regained.copy(speaks = number, current = waiting.copy(speakId = speakId, text = rest, spokenChars = 0)),
            listOf(NarrationCommand.Speak(speakId, rest, waiting.utterance.language)),
        )
    }

    /** The audio request was refused: whatever was about to be told is cut short, and nothing waits for audio that will not come. */
    private fun focusDenied(): NarrationStep {
        val refused = current ?: return NarrationStep(copy(holdsFocus = false, focusRequested = false), emptyList())
        val dropped = queue.filter { it.story }.map { NarrationCommand.Outcome(it, StoryOutcome.Dropped) }
        return NarrationStep(
            copy(current = null, paused = null, queue = emptyList(), holdsFocus = false, focusRequested = false),
            outcomeFor(refused, StoryOutcome.Interrupted) + dropped,
        )
    }

    private fun questionCancelled(): NarrationStep {
        val withoutQuestion = copy(queue = queue.filter { it.story })
        val playing = current
        if (playing == null || playing.utterance.story) return NarrationStep(withoutQuestion, emptyList())
        val next = withoutQuestion.copy(current = null).playNext()
        return next.copy(commands = listOf(NarrationCommand.StopSpeaking) + next.commands)
    }

    private fun replayed(): NarrationStep {
        val playing = current
        if (playing != null) {
            val number = speaks + 1
            val speakId = "${playing.utterance.id}#$number"
            return NarrationStep(
                copy(speaks = number, current = playing.copy(speakId = speakId, text = playing.utterance.spokenText, spokenChars = 0)),
                listOf(NarrationCommand.StopSpeaking, NarrationCommand.Speak(speakId, playing.utterance.spokenText, playing.utterance.language)),
            )
        }
        val story = lastStory?.takeIf { paused == null } ?: return NarrationStep(this, emptyList())
        val number = speaks + 1
        val speakId = "${story.id}#$number"
        val focus = if (holdsFocus) emptyList() else listOf(NarrationCommand.RequestFocus)
        return NarrationStep(
            copy(speaks = number, holdsFocus = true, focusRequested = true, current = Playing(story, speakId, story.spokenText, 0, replay = true)),
            focus + NarrationCommand.Speak(speakId, story.spokenText, story.language),
        )
    }

    /** Starts the next waiting utterance, skipping Stories that are no longer worth telling. Lets go of the audio when there is none. */
    private fun playNext(): NarrationStep {
        val commands = mutableListOf<NarrationCommand>()
        var waiting = queue
        while (waiting.isNotEmpty()) {
            val utterance = waiting.first()
            waiting = waiting.drop(1)
            if (utterance.story && !isRelevant(utterance)) {
                commands += NarrationCommand.Outcome(utterance, StoryOutcome.Dropped)
                continue
            }
            val number = speaks + 1
            val speakId = "${utterance.id}#$number"
            if (!holdsFocus) commands += NarrationCommand.RequestFocus
            commands += NarrationCommand.Speak(speakId, utterance.spokenText, utterance.language)
            return NarrationStep(
                copy(queue = waiting, speaks = number, holdsFocus = true, focusRequested = true, current = Playing(utterance, speakId, utterance.spokenText, 0, replay = false)),
                commands,
            )
        }
        if (holdsFocus || focusRequested) commands += NarrationCommand.AbandonFocus
        return NarrationStep(copy(queue = emptyList(), holdsFocus = false, focusRequested = false), commands)
    }

    /** Only Stories have outcomes, and a replay does not count again. */
    private fun outcomeFor(playing: Playing, outcome: StoryOutcome): List<NarrationCommand> =
        if (playing.utterance.story && !playing.replay) listOf(NarrationCommand.Outcome(playing.utterance, outcome)) else emptyList()

    private fun sentenceStart(text: String, chars: Int): Int =
        (listOf(0) + SENTENCE_GAP.findAll(text).map { it.range.last + 1 }.toList()).filter { it <= chars }.max()

    companion object {
        private val SENTENCE_GAP = Regex("(?<=[.!?])\\s+")

        operator fun invoke(isRelevant: (Utterance) -> Boolean): NarrationController =
            NarrationController(isRelevant, emptyList(), null, null, null, holdsFocus = false, focusRequested = false, speaks = 0)
    }
}

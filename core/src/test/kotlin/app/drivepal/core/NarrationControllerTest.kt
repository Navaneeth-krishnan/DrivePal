package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NarrationControllerTest {

    private val castle = Utterance("castle", "The castle is old. It has a moat. Kings lived there.", "en")
    private val mill = Utterance("mill", "The mill grinds grain.", "en")
    private val question = Utterance("question", "Still on the road? Press play to keep going.", "en", story = false)

    private var relevant: Set<String> = setOf("castle", "mill")

    private fun controller() = NarrationController(isRelevant = { it.id in relevant })

    /** Feeds events one after another and collects every command that came out. */
    private fun NarrationController.run(vararg events: NarrationEvent): Pair<NarrationController, List<NarrationCommand>> =
        events.fold(this to emptyList()) { (controller, commands), event ->
            val step = controller.handle(event)
            step.controller to commands + step.commands
        }

    private fun speak(id: String, text: String) = NarrationCommand.Speak(id, text, "en")
    private fun outcome(utterance: Utterance, outcome: StoryOutcome) = NarrationCommand.Outcome(utterance, outcome)

    @Test
    fun `a story that is read to the end is Told and the audio focus is given back`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.SpeechDone("castle#1"),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                outcome(castle, StoryOutcome.Told), NarrationCommand.AbandonFocus,
            ),
            commands,
        )
    }

    @Test
    fun `a second story waits for the first and the audio focus is held in between`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.StoryReady(mill),
            NarrationEvent.SpeechDone("castle#1"),
            NarrationEvent.SpeechDone("mill#2"),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                outcome(castle, StoryOutcome.Told), speak("mill#2", mill.text),
                outcome(mill, StoryOutcome.Told), NarrationCommand.AbandonFocus,
            ),
            commands,
        )
    }

    @Test
    fun `skipping a story counts it as Skipped and starts the next one`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.StoryReady(mill),
            NarrationEvent.Skip,
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                NarrationCommand.StopSpeaking, outcome(castle, StoryOutcome.Skipped), speak("mill#2", mill.text),
            ),
            commands,
        )
    }

    @Test
    fun `pressing skip when nothing is being told does nothing`() {
        assertEquals(emptyList(), controller().run(NarrationEvent.Skip).second)
    }

    @Test
    fun `a navigation prompt interrupts a story which picks up from its sentence when the audio returns`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.SpeechProgress("castle#1", spokenChars = 25), // part-way through the second sentence
            NarrationEvent.FocusLost(permanent = false),
            NarrationEvent.FocusGained,
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                NarrationCommand.StopSpeaking, speak("castle#2", "It has a moat. Kings lived there."),
            ),
            commands,
        )
    }

    @Test
    fun `a story that is no longer relevant when the audio returns is Interrupted, not Told`() {
        val interrupted = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.FocusLost(permanent = false),
        ).first
        relevant = emptySet()

        val (_, commands) = interrupted.run(NarrationEvent.FocusGained)

        assertEquals(listOf(outcome(castle, StoryOutcome.Interrupted), NarrationCommand.AbandonFocus), commands)
    }

    @Test
    fun `another app taking over for good interrupts the story and drops the ones waiting`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.StoryReady(mill),
            NarrationEvent.FocusLost(permanent = true),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                NarrationCommand.StopSpeaking,
                outcome(castle, StoryOutcome.Interrupted), outcome(mill, StoryOutcome.Dropped),
            ),
            commands,
        )
    }

    @Test
    fun `a story that is no longer relevant when its turn comes is dropped without a word`() {
        relevant = emptySet()

        val (_, commands) = controller().run(NarrationEvent.StoryReady(castle))

        assertEquals(listOf(outcome(castle, StoryOutcome.Dropped)), commands)
    }

    @Test
    fun `replay speaks the last story again without counting it as Told twice`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.SpeechDone("castle#1"),
            NarrationEvent.Replay,
            NarrationEvent.SpeechDone("castle#2"),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                outcome(castle, StoryOutcome.Told), NarrationCommand.AbandonFocus,
                NarrationCommand.RequestFocus, speak("castle#2", castle.text), NarrationCommand.AbandonFocus,
            ),
            commands,
        )
    }

    @Test
    fun `the spoken question goes first and is never counted as a story`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.QuestionReady(question),
            NarrationEvent.SpeechDone("castle#1"),
            NarrationEvent.SpeechDone("question#2"),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                outcome(castle, StoryOutcome.Told), speak("question#2", question.text),
                NarrationCommand.AbandonFocus,
            ),
            commands,
        )
    }

    @Test
    fun `a speech engine failure marks the story Interrupted and moves on`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.StoryReady(mill),
            NarrationEvent.SpeechFailed("castle#1"),
        )

        assertEquals(
            listOf(
                NarrationCommand.RequestFocus, speak("castle#1", castle.text),
                outcome(castle, StoryOutcome.Interrupted), speak("mill#2", mill.text),
            ),
            commands,
        )
    }

    @Test
    fun `narration counts as active from the moment a story starts until it has finished`() {
        val idle = controller()
        val telling = idle.run(NarrationEvent.StoryReady(castle)).first
        val interrupted = telling.run(NarrationEvent.FocusLost(permanent = false)).first
        val finished = interrupted.run(NarrationEvent.FocusGained, NarrationEvent.SpeechDone("castle#2")).first

        assertEquals(listOf(false, true, true, false), listOf(idle, telling, interrupted, finished).map { it.isActive })
    }

    @Test
    fun `a late callback from a story that was already skipped is ignored`() {
        val (_, commands) = controller().run(
            NarrationEvent.StoryReady(castle),
            NarrationEvent.StoryReady(mill),
            NarrationEvent.Skip,
            NarrationEvent.SpeechDone("castle#1"), // the engine reports the stopped story late
        )

        assertTrue(commands.none { it == outcome(castle, StoryOutcome.Told) })
        assertEquals(1, commands.count { it is NarrationCommand.Outcome && it.utterance == castle })
    }
}

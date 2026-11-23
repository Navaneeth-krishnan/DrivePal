package app.drivepal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.drivepal.core.NarrationCommand
import app.drivepal.core.NarrationController
import app.drivepal.core.NarrationEvent
import app.drivepal.core.StoryOutcome
import app.drivepal.core.Utterance
import app.drivepal.core.VoiceChoice
import java.util.Locale

/**
 * The Android side of narration: the speech engine, the audio focus, and a media session so the steering-wheel
 * and Bluetooth buttons reach us. All the decisions are made by [NarrationController]; this class only carries
 * them out and reports what happened back to it. Main thread only.
 *
 * While something is being told, a media session is active, so *next* skips the Story and *previous* replays it.
 * The same goes while the Still-on-the-road question is waiting for an answer, so *play* can answer it.
 */
class Narrator(
    context: Context,
    isRelevant: (Utterance) -> Boolean,
    private val onOutcome: (Utterance, StoryOutcome) -> Unit,
    private val onPlayPressed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var controller = NarrationController(isRelevant)
    private var questionPending = false
    private var released = false

    private var focusRequest: AudioFocusRequest? = null
    private var silentTrack: AudioTrack? = null
    private var voiceTags: Set<String> = emptySet()
    private var speechReady = false
    private var speechFailedToStart = false
    private val speech = TextToSpeech(context) { status ->
        handler.post { if (!released) onSpeechInitialised(status) }
    }

    private val session = MediaSession(context, "DrivePal").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() = post { dispatch(NarrationEvent.Skip) }
            override fun onPause() = post { dispatch(NarrationEvent.Skip) }
            override fun onSkipToPrevious() = post { dispatch(NarrationEvent.Replay) }
            override fun onPlay() = post { onPlayPressed() }
        }, handler)
    }

    /** True while something is being told, or was cut off and is waiting to continue. */
    val isTelling: Boolean get() = controller.isActive

    /** The speech engine is up and has voices. Until then nothing should be written for it to say. */
    val canSpeak: Boolean get() = speechReady

    /** The speech engine failed to start, or has no voices at all: there is no point writing Stories. */
    val speechBroken: Boolean get() = speechFailedToStart

    fun tell(utterance: Utterance) = dispatch(NarrationEvent.StoryReady(utterance))

    fun ask(utterance: Utterance) = dispatch(NarrationEvent.QuestionReady(utterance))

    fun replay() = dispatch(NarrationEvent.Replay)

    /** The Still-on-the-road question was answered: it must not be spoken now, or later. */
    fun cancelQuestion() = dispatch(NarrationEvent.QuestionCancelled)

    /** While true the media session stays active, so the *play* button can answer the question. */
    fun setQuestionPending(pending: Boolean) {
        questionPending = pending
        updateSession()
    }

    fun release() {
        released = true
        speech.stop()
        speech.shutdown()
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        holdMediaKeys(false)
        session.isActive = false
        session.release()
    }

    private fun onSpeechInitialised(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            speechFailedToStart = true
            return
        }
        speech.setAudioAttributes(AUDIO_ATTRIBUTES)
        voiceTags = speech.availableLanguages.orEmpty().map { it.toLanguageTag() }.toSet()
        if (voiceTags.isEmpty()) {
            speechFailedToStart = true
            return
        }
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = post { dispatch(NarrationEvent.SpeechDone(utteranceId)) }

            @Deprecated("Replaced by onError(utteranceId, errorCode)")
            override fun onError(utteranceId: String) = post { dispatch(NarrationEvent.SpeechFailed(utteranceId)) }
            override fun onError(utteranceId: String, errorCode: Int) = post { dispatch(NarrationEvent.SpeechFailed(utteranceId)) }
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) =
                post { dispatch(NarrationEvent.SpeechProgress(utteranceId, start)) }
        })
        speechReady = true
    }

    private fun dispatch(event: NarrationEvent) {
        if (released) return
        val step = controller.handle(event)
        controller = step.controller
        execute(step.commands)
    }

    private fun execute(commands: List<NarrationCommand>) {
        var focus = Focus.Granted
        for (command in commands) {
            when (command) {
                NarrationCommand.RequestFocus -> focus = requestFocus()
                NarrationCommand.AbandonFocus -> focusRequest?.let(audioManager::abandonAudioFocusRequest)
                NarrationCommand.StopSpeaking -> speech.stop()
                is NarrationCommand.Speak -> if (focus == Focus.Granted) speak(command)
                is NarrationCommand.Outcome -> onOutcome(command.utterance, command.outcome)
            }
        }
        updateSession()
        // Without the audio we wait, or give up: the controller knows what to do with the Story either way.
        when (focus) {
            Focus.Granted -> Unit
            Focus.Delayed -> dispatch(NarrationEvent.FocusLost(permanent = false)) // a GAIN will follow
            Focus.Denied -> dispatch(NarrationEvent.FocusDenied) // nothing will follow
        }
    }

    private fun speak(command: NarrationCommand.Speak) {
        if (!speechReady) {
            post { dispatch(NarrationEvent.SpeechFailed(command.speakId)) }
            return
        }
        // No voice for the language (a Malayalam article, say): reading it with an English voice would be noise.
        val tag = VoiceChoice.pick(command.language, voiceTags)
        if (tag == null || !VoiceChoice.hasVoice(command.language, voiceTags)) {
            post { dispatch(NarrationEvent.SpeechUnavailable(command.speakId)) }
            return
        }
        speech.language = Locale.forLanguageTag(tag)
        if (BuildConfig.DEBUG) Log.i("DrivePal", "Say: ${command.text}") // debug builds only: lets a simulated drive be checked word for word
        if (speech.speak(command.text, TextToSpeech.QUEUE_FLUSH, null, command.speakId) != TextToSpeech.SUCCESS) {
            post { dispatch(NarrationEvent.SpeechFailed(command.speakId)) }
        }
    }

    private enum class Focus { Granted, Delayed, Denied }

    private fun requestFocus(): Focus {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true) // a navigation prompt: we stop talking rather than talk over it
            .setOnAudioFocusChangeListener(::onFocusChange, handler)
            .build()
            .also { focusRequest = it }
        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Focus.Granted
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Focus.Delayed
            else -> Focus.Denied
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> dispatch(NarrationEvent.FocusGained)
            AudioManager.AUDIOFOCUS_LOSS -> dispatch(NarrationEvent.FocusLost(permanent = true))
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                dispatch(NarrationEvent.FocusLost(permanent = false))
        }
    }

    private fun updateSession() {
        if (released) return
        val telling = controller.isActive
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(if (telling) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1f)
                .build(),
        )
        // Inactive when idle, so the buttons go back to the music app.
        session.isActive = telling || questionPending
        holdMediaKeys(telling || questionPending)
    }

    /**
     * Android sends the media keys to the app that is playing sound. The speech engine is another app (and has no
     * session), so the keys would go nowhere. While narrating, this app plays a silent looping track so that it counts
     * as the one playing, and the keys reach our media session.
     */
    private fun holdMediaKeys(hold: Boolean) {
        if (!hold) {
            silentTrack?.run { stop(); release() }
            silentTrack = null
            return
        }
        if (silentTrack != null) return
        val silence = ByteArray(SILENCE_BYTES)
        // Without this the media keys may not reach us, but the Stories are still told.
        silentTrack = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SILENCE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.size)
                .build()
                .apply {
                    write(silence, 0, silence.size)
                    setLoopPoints(0, silence.size / 2, -1)
                    play()
                }
        }.getOrNull()
    }

    private fun post(block: () -> Unit) {
        handler.post { if (!released) block() }
    }

    private companion object {
        const val SILENCE_HZ = 8_000
        const val SILENCE_BYTES = SILENCE_HZ * 2 // one second of 16-bit mono
        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

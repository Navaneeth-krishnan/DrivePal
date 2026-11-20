package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceChoiceTest {

    @Test
    fun `a Dutch article is read by a Dutch voice when one is installed`() {
        assertEquals("nl-NL", VoiceChoice.pick(language = "nl", installedVoices = setOf("en-US", "nl-NL")))
    }

    @Test
    fun `any installed regional variant of the language will do`() {
        assertEquals("en-GB", VoiceChoice.pick(language = "en", installedVoices = setOf("en-GB", "de-DE")))
    }

    @Test
    fun `a language with no installed voice is read in English instead`() {
        assertEquals("en-US", VoiceChoice.pick(language = "de", installedVoices = setOf("en-US", "nl-NL")))
    }

    @Test
    fun `Min Nan is not mistaken for Mandarin`() {
        assertEquals("en-US", VoiceChoice.pick(language = "zh-min-nan", installedVoices = setOf("en-US", "zh-CN")))
    }

    @Test
    fun `Wikipedia's Norwegian is read by an installed Bokmal voice`() {
        assertEquals("nb-NO", VoiceChoice.pick(language = "no", installedVoices = setOf("en-US", "nb-NO")))
    }

    @Test
    fun `it is known whether a language has a voice of its own, as opposed to the English stand-in`() {
        assertEquals(true, VoiceChoice.hasVoice("nl", setOf("en-US", "nl-NL")))
        assertEquals(false, VoiceChoice.hasVoice("ml", setOf("en-US", "nl-NL")))
        assertEquals(true, VoiceChoice.hasVoice("no", setOf("nb-NO")))
    }

    @Test
    fun `with no English voice either there is nothing to choose`() {
        assertNull(VoiceChoice.pick(language = "de", installedVoices = setOf("nl-NL")))
    }
}

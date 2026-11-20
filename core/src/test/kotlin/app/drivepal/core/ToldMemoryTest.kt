package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToldMemoryTest {

    private class FakeStore(var saved: Map<String, Long> = emptyMap()) : ToldStore {
        override fun load(): Map<String, Long> = saved
        override fun save(entries: Map<String, Long>) { saved = entries }
    }

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    @Test
    fun `a story that was read to the end is remembered`() {
        val memory = ToldMemory.load(FakeStore()).recording(StoryOutcome.Told, "node/1", now)

        assertTrue(memory.isTold("node/1", now))
        assertFalse(memory.isTold("node/2", now))
    }

    @Test
    fun `a story the driver skipped counts as told`() {
        val memory = ToldMemory.load(FakeStore()).recording(StoryOutcome.Skipped, "node/1", now)

        assertTrue(memory.isTold("node/1", now))
    }

    @Test
    fun `a story that was interrupted or dropped can still come back`() {
        val memory = ToldMemory.load(FakeStore())
            .recording(StoryOutcome.Interrupted, "node/1", now)
            .recording(StoryOutcome.Dropped, "node/2", now)

        assertFalse(memory.isTold("node/1", now))
        assertFalse(memory.isTold("node/2", now))
    }

    @Test
    fun `a story is remembered for thirty days and may be told again after that`() {
        val memory = ToldMemory.load(FakeStore()).recording(StoryOutcome.Told, "node/1", now)

        assertTrue(memory.isTold("node/1", now + 29 * day))
        assertFalse(memory.isTold("node/1", now + 30 * day + 1))
    }

    @Test
    fun `what was told is still remembered after the app restarts`() {
        val store = FakeStore()
        ToldMemory.load(store).recording(StoryOutcome.Told, "node/1", now)

        assertTrue(ToldMemory.load(store).isTold("node/1", now + day))
    }

    @Test
    fun `entries older than thirty days are not kept when the memory is saved again`() {
        val store = FakeStore()
        ToldMemory.load(store)
            .recording(StoryOutcome.Told, "old", now)
            .recording(StoryOutcome.Told, "new", now + 31 * day)

        assertEquals(setOf("new"), store.saved.keys)
    }
}

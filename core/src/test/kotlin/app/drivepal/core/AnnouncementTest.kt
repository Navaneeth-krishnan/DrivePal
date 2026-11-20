package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnouncementTest {

    @Test
    fun `entering a province or a country is announced as entering it`() {
        assertEquals("You are entering Kerala.", announcement(StoryKind.Province, "Kerala", relation = null))
        assertEquals("You are entering India.", announcement(StoryKind.Country, "India", relation = null))
    }

    @Test
    fun `a town ahead is announced as entering it and a town already behind as just passed`() {
        assertEquals("You are entering Eraviperoor.", announcement(StoryKind.Town, "Eraviperoor", Relation.Ahead))
        assertEquals("You just passed Eraviperoor.", announcement(StoryKind.Town, "Eraviperoor", Relation.Behind))
    }

    @Test
    fun `a landmark is announced as coming up, or as just passed`() {
        assertEquals("Coming up: Kortlandse Molen.", announcement(StoryKind.Landmark, "Kortlandse Molen", Relation.Ahead))
        assertEquals("You just passed Kortlandse Molen.", announcement(StoryKind.Landmark, "Kortlandse Molen", Relation.Behind))
    }

    @Test
    fun `a junction is announced by its name, without a description`() {
        assertEquals("Approaching Kozhencherry junction.", announcement(StoryKind.Junction, "Kozhencherry", Relation.Ahead))
        assertEquals("You just passed Kozhencherry junction.", announcement(StoryKind.Junction, "Kozhencherry", Relation.Behind))
    }

    @Test
    fun `an interlude is not announced`() {
        assertNull(announcement(StoryKind.Interlude, "South Holland", relation = null))
    }
}

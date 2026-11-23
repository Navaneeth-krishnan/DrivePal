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

    @Test
    fun `a landmark says how far away it is, to the nearest fifty metres and under a kilometre`() {
        assertEquals("Coming up: Kaviyoor Temple, about 400 metres away.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Ahead, 410.0))
        assertEquals("Coming up: Kaviyoor Temple, about 450 metres away.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Ahead, 430.0))
        assertEquals("You just passed Kaviyoor Temple, about 50 metres back.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Behind, 20.0))
    }

    @Test
    fun `a landmark a kilometre or more away says it in half kilometres`() {
        assertEquals("Coming up: Kaviyoor Temple, about 2 kilometres away.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Ahead, 2_000.0))
        assertEquals("You just passed Kaviyoor Temple, about 1 kilometre back.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Behind, 1_200.0))
        assertEquals("Coming up: Kaviyoor Temple, about 1.5 kilometres away.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Ahead, 1_300.0))
        assertEquals("Coming up: Kaviyoor Temple, about 2.5 kilometres away.", announcement(StoryKind.Landmark, "Kaviyoor Temple", Relation.Ahead, 2_740.0))
    }

    @Test
    fun `only a landmark says its distance`() {
        assertEquals("You are entering Eraviperoor.", announcement(StoryKind.Town, "Eraviperoor", Relation.Ahead, 800.0))
        assertEquals("Approaching Kozhencherry junction.", announcement(StoryKind.Junction, "Kozhencherry", Relation.Ahead, 250.0))
    }
}

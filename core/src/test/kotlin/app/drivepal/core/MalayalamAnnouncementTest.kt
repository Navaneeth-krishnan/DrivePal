package app.drivepal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Malayalam Announcements agreed on 2026-11-23: the name stands alone after a short label, with no case ending. */
class MalayalamAnnouncementTest {

    private fun say(kind: StoryKind, name: String, relation: Relation?, distanceMeters: Double? = null) =
        announcement(kind, name, relation, distanceMeters, SpokenLanguage.Malayalam)

    @Test
    fun `entering a country or a state names it as the new one`() {
        assertEquals("പുതിയ രാജ്യം: ഇന്ത്യ.", say(StoryKind.Country, "ഇന്ത്യ", relation = null))
        assertEquals("പുതിയ സംസ്ഥാനം: കർണാടക.", say(StoryKind.Province, "കർണാടക", relation = null))
    }

    @Test
    fun `a town ahead is the next place and a town behind was just passed`() {
        assertEquals("അടുത്ത സ്ഥലം: തിരുവല്ല.", say(StoryKind.Town, "തിരുവല്ല", Relation.Ahead))
        assertEquals("ഇപ്പോൾ കടന്നുപോയത്: കുമ്പനാട്.", say(StoryKind.Town, "കുമ്പനാട്", Relation.Behind))
    }

    @Test
    fun `a name with no Malayalam spelling is said as it is written, in the same sentence`() {
        assertEquals("അടുത്ത സ്ഥലം: Muttumon.", say(StoryKind.Town, "Muttumon", Relation.Ahead))
    }

    @Test
    fun `a junction ahead is the next junction and one behind was just passed`() {
        assertEquals("അടുത്ത ജംഗ്ഷൻ: വള്ളംകുളം.", say(StoryKind.Junction, "വള്ളംകുളം", Relation.Ahead))
        assertEquals("ഇപ്പോൾ കടന്നുപോയ ജംഗ്ഷൻ: വള്ളംകുളം.", say(StoryKind.Junction, "വള്ളംകുളം", Relation.Behind))
    }

    @Test
    fun `a landmark ahead or behind says how far it is, in metres under a kilometre`() {
        assertEquals(
            "മുന്നിൽ: ആറന്മുള പാർത്ഥസാരഥി ക്ഷേത്രം, ഏകദേശം 500 മീറ്റർ അകലെ.",
            say(StoryKind.Landmark, "ആറന്മുള പാർത്ഥസാരഥി ക്ഷേത്രം", Relation.Ahead, 510.0),
        )
        assertEquals("പിന്നിൽ: Aranmula Kshethra Kadavu, ഏകദേശം 50 മീറ്റർ അകലെ.", say(StoryKind.Landmark, "Aranmula Kshethra Kadavu", Relation.Behind, 20.0))
    }

    @Test
    fun `from a kilometre a landmark's distance is said in words, to the nearest half kilometre`() {
        assertEquals("മുന്നിൽ: കാവിയൂർ, ഏകദേശം ഒരു കിലോമീറ്റർ അകലെ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Ahead, 980.0))
        assertEquals("മുന്നിൽ: കാവിയൂർ, ഏകദേശം ഒന്നര കിലോമീറ്റർ അകലെ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Ahead, 1_400.0))
        assertEquals("മുന്നിൽ: കാവിയൂർ, ഏകദേശം രണ്ട് കിലോമീറ്റർ അകലെ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Ahead, 2_100.0))
        assertEquals("പിന്നിൽ: കാവിയൂർ, ഏകദേശം രണ്ടര കിലോമീറ്റർ അകലെ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Behind, 2_600.0))
        assertEquals("മുന്നിൽ: കാവിയൂർ, ഏകദേശം മൂന്ന് കിലോമീറ്റർ അകലെ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Ahead, 3_000.0))
    }

    @Test
    fun `a landmark whose distance is not known is named without one`() {
        assertEquals("മുന്നിൽ: കാവിയൂർ.", say(StoryKind.Landmark, "കാവിയൂർ", Relation.Ahead))
    }

    @Test
    fun `an interlude is not announced in Malayalam either`() {
        assertNull(say(StoryKind.Interlude, "കേരളം", relation = null))
    }
}

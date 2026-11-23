# DrivePal: plan

**Goal:** a storyteller that tells you about the places you drive through. It runs hands-free, on your own phone, with AI-written Stories based on OpenStreetMap and Wikipedia.

- **Terms** such as Area, Town, Landmark, Story, Interlude, Told and Talkativeness are defined in [`CONTEXT.md`](../CONTEXT.md).
- **Decisions** are recorded in [`docs/adr/`](adr/), starting with [0001: no Google Play Services](adr/0001-no-google-play-services.md).

## Version 1

### What DrivePal tells Stories about

| Subject | Trigger | Source |
|---|---|---|
| **Country / Province** | Entering it | Its Wikipedia article. Later sections of the same article feed Interludes. |
| **Town** (the settlement, not the municipality) | Passing it: the route comes within about 3 km of it, through it or not | The Wikipedia article from its OSM `wikipedia`/`wikidata` tag |
| **Landmark** | Passing it: within about 3 km | The same, through its OSM tag |
| **Interlude** | About 5 minutes of silence (not in Quiet mode) | A section of the current Province's or Country's Source that hasn't been Told |

**Not in version 1:** Crossings (rivers, borders, passes).

### Rules agreed

**Choosing subjects**
- **Story-worthy:** only Towns and Landmarks whose Source has enough substance (about 150 words of extract or more) get a Story. A subject without a Source gets no Story.
- **Ranking:** when subjects compete, the more notable wins. Notability is judged from the number of Wikipedia language editions (Wikidata sitelinks) and the length of the Source.

**What a Story says**
- **Grounding:** every factual claim must come from the Source. Framing (atmosphere, the driver's situation, links to earlier Stories on the *same Trip*) is allowed. Legends appear only if the Source reports them, told as legends.
- **Language:** Stories are narrated in English by default, whatever the Source's language. The Source stays in the language of its article (usually the one in the OpenStreetMap tag) and the AI translates it; an English article is deliberately not preferred (decided: translation is fine).
- **Source:** it isn't mentioned in the Story itself. The trip log shows it, and in the later work you can ask for it by voice.

**When to talk**
- **Priority:** entering a Country or Province first, then Towns and Landmarks by Notability, then Interludes.
- **Talkativeness** sets the gap between Stories and their length:

  | | Gap | Landmark | Town | Country/Province | Interlude |
  |---|---|---|---|---|---|
  | **Names** | ~20 s | name only | name only | name only | none |
  | **Normal** (default) | ~5 min | ~20–30 s | ~30 s | ~45 s | none |
  | **Chatty** | ~1 min | ~30–45 s | ~45–60 s | ~60–90 s | ~60–90 s |

- **Announcements:** every place is announced before its Story, in every mode, in one line ("You are entering Eraviperoor", "Coming up: Kortlandse Molen", "Approaching Kozhencherry junction"; "You just passed…" once it is behind). In Names mode that line is all there is. Named motorway junctions and towns or villages without an article are announced too, by name only. A name said on its own is remembered apart from the Story, so Names mode never uses up a Story. An article that fell back to another language is read without the announcement. **Two gaps:** the quiet gap between Stories (5 min Normal, 1 min Chatty, 20 s Names) only holds back Stories; any place in range is still announced by name once 20 seconds have passed since anything was said, **Landmarks from Wikipedia (2026-11-23):** OpenStreetMap links few landmarks to an article (along the Pullad route none), so Wikipedia's map search is asked for every tile, not only when Overpass fails. Its articles with coordinates become Landmarks, minus schools, colleges, constituencies and the like, and minus anything Overpass already has by name. If one of the two sources fails the other serves the tile, which is tried again after a minute; the next tile ahead (about 5 km) is loaded before the driver gets there. **Start and thin articles:** only places within 1 km of where the Trip starts count as already there (so a town 2.5 km ahead is still announced when the road reaches it), and a place whose article is too thin for a Story is announced by name. **Closest first:** a name is said only once the place is within 1 km (junctions 300 m) and goes to the closest place, except a Landmark, which is announced from as far as 3 km and says how far it is (\"Coming up: Kaviyoor Temple, about 2 kilometres away\", \"You just passed X, about 1 kilometre back\"; to the nearest 50 m under a kilometre, then to the nearest half kilometre); a Story prefers places within 1 km, then the most notable, and only then those out to 3 km. Its Story, if the gap has passed by the time it is still in range, then starts without repeating the name.

- **Interludes:** after 3 in a row with nothing else in between, the Interlude gap doubles (5 → 10 → 20 min). When nothing untold is left, DrivePal stays silent.

**Relevance and memory**
- **Relevant:** a Town or Landmark Story is relevant within about 3 km, ahead or behind. A Story that starts after the subject is passed begins with a lead-in ("back there, behind us…"). Area Stories and Interludes are relevant while you're inside the Area. A Story that is no longer relevant is dropped.
- **Told:** a Story is Told when it plays to the end or is Skipped. A Told Story isn't repeated for about 30 days, across Trips.
- **Interrupted:** a Story cut off by navigation or a call resumes once the audio is free, if it's still Relevant. Otherwise it can come back on a later Trip.
- **Skipped** Stories count as Told for the memory. The distinction is kept in the Trip log (M6), which is where "learning your taste" will read it from.

**Trips**
- **Start and stop:** manual, with a Start/Stop button and a persistent notification.
- **Standstill:** below about 5 km/h for more than about 2 minutes, new Town and Landmark Stories wait, but Interludes continue.
- **Still-on-the-road check:** after about 30 minutes without driving speed (above about 15 km/h), DrivePal asks once, "Still on the road? Press play to keep going." With no answer within about 2 minutes, the Trip ends.

### Architecture

```
TripService (foreground service: GPS, the Trip, the notification)
  ├─ TripMonitor          driving / Standstill / the Still-on-the-road check (pure, in :core)
  ├─ Exploration          the threads and Android plumbing; hands the decisions to the director
  │    ├─ PassingDetector   Towns and Landmarks within ~3 km, never the ones already around at the start
  │    ├─ AreaTracker       entering a Country or Province (Nominatim, with Photon behind it)
  │    ├─ SubjectFinder     places per map tile (Overpass, with Wikipedia geosearch behind it)
  │    ├─ SourceLibrary     Wikipedia articles, story-worthiness, Notability (bounded cache)
  │    ├─ StoryDirector     the pool, the Areas, Interludes, Told memory and StoryScheduler (pure, in :core)
  │    └─ StoryWriter       an AI model writes a Story from the Source (ChatClient, any OpenAI-compatible server)
  └─ Narrator             text-to-speech, audio focus, MediaSession and a silent track so the media keys arrive;
                          the decisions are NarrationController's (pure, in :core)
ToldMemory (FileToldStore, a small JSON file) remembers what was told for 30 days
UI (Jetpack Compose): Start/Stop Trip, Talkativeness, the Stories and what happened to them; M6 adds the trip log and settings
```

- **No Google Play Services** ([ADR 0001](adr/0001-no-google-play-services.md)).
- To keep it simple, the parts are connected by hand in code rather than through a dependency-injection library such as Hilt.

### Data sources

| Job | Source | Usage rules |
|---|---|---|
| GPS position, speed, heading | Android `LocationManager` (GPS provider) | none |
| Country and Province you're in | Nominatim reverse geocoding (OpenStreetMap); Photon (photon.komoot.io) if Nominatim fails | At most 1 request per second, a User-Agent that identifies the app, cached results. Only query after moving a few km. |
| Towns and Landmarks nearby | Overpass API (fallback: Wikipedia geosearch, English, 8 km around the tile centre): `place=city/town/village`, `historic=*`, `tourism=attraction/viewpoint/museum`, `man_made=lighthouse/windmill` and the like, with a `wikipedia` or `wikidata` tag | One query per area tile (about 10 km), cached |
| Sources | Wikipedia Action API (extract and language links); Wikidata only for subjects without a Wikipedia tag | Polite User-Agent (add a contact with `-Pdrivepal.contact=`), requests spaced 300 ms apart, answers cached, failures retried after 30 s |
| Map on screen (after version 1) | MapLibre or osmdroid with OpenStreetMap tiles | Tile usage policy |

### Milestones

Each one ends with something you can run and check.

| # | Milestone | Done when |
|---|---|---|
| **M0** | **Project skeleton.** Gradle 9.9 / Android Gradle Plugin 9.5 / Kotlin 2.4 / Compose; minimum Android 10 (API 29), target Android 37 | The app builds, installs and opens on `DrivePal_API37` |
| **M1** | **Trip and location.** Foreground service; `LocationManager` GPS updates; permissions; live speed and heading on screen; Standstill detection and the Still-on-the-road check | Replaying a route in the emulator updates speed (and heading, where the device reports a bearing); tracking continues with the screen off; a parked replay triggers the check and ends the Trip |
| **M2** | **Finding subjects. Done.** Entering a Country or Province (Nominatim); Passing Towns and Landmarks within about 3 km (Overpass, one query per 0.1° tile, cached); heading derived from consecutive fixes; Sources from Wikipedia (language links give the number of language editions, Wikidata only when a subject has no Wikipedia tag); story-worthy means 150+ words; failed lookups are shown on screen and retried | On a replayed route past Kinderdijk the app entered the Netherlands and South Holland, then Passed towns and landmarks in order, with Source word counts and language editions, against the live services |
| **M2b** | **Fallbacks. Done.** Photon behind Nominatim for the country and province; Wikipedia's English map search (geosearch) behind Overpass for Towns and Landmarks (each result already names its Source; no Town or Landmark label, so they count as Landmarks). A tile served by the fallback is tried against Overpass again after 5 minutes; both failing is shown on screen with a reason for each | A drive along the real road through Kozhencherry, Kerala, on the phone: Nominatim gave Kerala and India, Overpass timed out, the screen said so, and Wikipedia's map search supplied the temple, the town, the Maramon Convention and more. Eight Stories were told over about 5 minutes |
| **M3** | **Writing Stories. Done for Towns and Landmarks; Areas and Interludes come with M5.** `ChatClient` for any OpenAI-compatible server (thinking switched off with `reasoning_effort: none`, which fixed empty answers); `StoryPrompt` (length by kind and Talkativeness, grounding rules, "Back there, behind us…" lead-in, Framing from earlier Stories, no left/right claims); `StoryWriter` (strips stage directions and markdown, cuts at a sentence, retries once when a number or legend is not in the article, then falls back to the start of the article) | Live on the emulator against real Wikipedia and local Ollama (`gemma4:e4b`): Stories in English from Dutch articles. **Known limit:** the small local model mistranslates Dutch ("built before 1808" became "built in 1808"), which no check can catch. Compared with Gemini (see the model comparison): Flash-Lite got it right every time at the same speed, so it is the default for real drives |
| **M4** | **Narration. Done.** `NarrationController` in `:core` (101 tests in all): Told, Skipped, Interrupted and Dropped outcomes; a navigation prompt or call pauses a Story and it resumes from its sentence if still Relevant; skip, replay; the spoken question jumps the queue and is never a Story; stale speech callbacks ignored. `VoiceChoice` picks an installed voice for the Source's language. Android shell: text-to-speech, audio focus (pauses for navigation prompts), a media session, and a debug-only simulated drive | On a real phone (LAVA LZG410, Android 14): the Stories are heard, *next* skips (3 s after the key) and *previous* restarts the Story. **Found on the way:** Android sends media keys to the app that is *playing sound*, and the speech engine is a different app with no session, so the keys went nowhere (a media-button receiver does not help: Android only records it for the chosen session). Fix: while narrating, DrivePal plays a silent looping track so it counts as the app playing. **Not yet tried live:** the spoken Still-on-the-road question and answering it with *play* (needs 30 idle minutes; unit-tested), interruption by a real call, and ducking other music |
| **M5a** | **Pacing, priority and memory. Done.** `StoryScheduler`: one Story at a time, just in time (the AI is asked only when a candidate is picked): Country, then Province, then Towns and Landmarks by Notability; a quiet gap after each Story (Quiet 5 min, Normal 1 min, Chatty 20 s); only Areas while standing still. `ToldMemory`: Told and Skipped are remembered 30 days across Trips in a small JSON file (`FileToldStore`; **deviation from the plan, which said Room:** a list of names and dates does not need a database, and it sits behind an interface so Room can replace it). Area Stories use the province's own Wikipedia page from Nominatim's `extratags`, so "Utrecht" cannot become the city. A three-way Talkativeness choice on the main screen | On the phone, Chatty mode along the real road through Kozhencherry: India first, then Kerala, then the town and the Maramon Convention; exactly 25 s between Stories; nothing queued or dropped. Restarting the app and driving the same route again repeated none of the four Told Stories and told new places instead |
| **M5b** | **Interludes, and nothing at the start. Done.** `InterludeSections` cuts an Area's article into History, Geography and the like (skipping the opening, boilerplate and anything under 80 words); the scheduler tells an Interlude only after 5 minutes of silence (counted from the last Story, or the start of the Trip), never in Quiet mode, also while standing still, after places and Areas, with the wait doubling after 3 in a row (5, 10, 20 minutes) and any other Story resetting it. Areas keep far more of their article. **Start rule (added during M5b):** whatever surrounds the driver when a Trip starts counts as already there: the Area it starts in and the places within 3 km of the start are never announced; only crossing into a new Area, or coming into range of a place later, tells a Story | On the phone, E19 across the Netherlands-Belgium border then parked, with a 60 s wait for testing: no Story for North Brabant or the Netherlands at the start (only the *History* Interlude after the wait), then Belgium and Antwerp after the border, then a further Interlude after the parked silence; the Told memory held the Belgium and Antwerp Stories and the Interlude, and nothing for the Area it started in. **Not tried live:** the doubling of the wait (unit-tested), and Quiet and Normal modes |
| **M6** | **Trip log and settings. Done (committed; the Settings and Trip log screens were not yet tried by hand on the phone).** Trip log in a JSON file (last 50 Trips, each Story with its Wikipedia Source link and outcome, "Clear trip log"); Settings for the AI provider (Ollama, Gemini, OpenAI, Groq, OpenRouter, Custom), address, model, reasoning effort and API key (encrypted with the Android Keystore, sent only over https), with "Try it" and "Forget what I've heard"; three state-based screens (Main, Trip log, Settings). Added with it: an Announcement before every Story, Talkativeness now Names / Normal / Chatty, and announce-only Junctions and villages without an article. Still open: the live check of announcements with Overpass answering, and the real-drive items for v1 | You can look back over past Trips and see each Story's Source; you can switch AI providers without rebuilding |
| **✅ v1** | **A real drive** | A 1-hour real drive with no crashes and Stories that land |

### Next stage: offline Names mode for one State

Goal: drive a whole State or Province with no network at all, in Names mode (no Stories, so no AI and no Wikipedia). Decisions agreed 2026-11-23; implementation plan agreed the same day.

| Topic | Decision |
|---|---|
| **Source** | A **Region pack** ([`CONTEXT.md`](../CONTEXT.md)): one SQLite file per State or Province. Places are found through a grid-cell column with an ordinary index, not R-tree (not sure Android's built-in SQLite has it; a grid is as fast for 10,000 to 30,000 places and works everywhere). |
| **Getting it onto the phone** | Built on the computer and copied with `adb push` or a USB file transfer into the app's external files folder, `Android/data/app.drivepal/files/packs/`. A later in-app download writes to the same folder. |
| **Integrity** | Each pack has a manifest beside it (`kerala.json`: SHA-256, size, region, format version), since a SQLite file cannot hold its own checksum. Checked at Trip start; a pack that fails, or has a format version the app does not know, is ignored with a notice. |
| **When it is used** | Inside an installed pack's region, every place and Area lookup comes from the pack, with no network calls. Outside it, or with no pack, the app goes online as today (Overpass, Nominatim and their fallbacks). No setting. Normal and Chatty inside a pack region give names only for now (no Wikipedia links, so no Stories); the pack already stores the Wikipedia and Wikidata tags so a later stage can bring Stories back when there is network. |
| **Contents** | Named places of type city, town, village, suburb, hamlet and locality (the English name when tagged, as online), and named motorway junctions, inside the region plus 3 km beyond its border; the boundaries of the region and its neighbouring provinces with their country, simplified to about 50 m and keeping holes (Mahé, Puducherry's enclave in Kerala). No Landmarks yet. |
| **Online too** | The Overpass query gets the same minor places (suburb, hamlet, locality), so online and offline sound alike. |
| **Code split** | `Exploration` asks a `SubjectSource`: the online `SubjectFinder`, or `PackFirst` (the pack inside its region, the online finder outside). `AreaTracker` gets the pack as its first provider; its rule that a change of provider is not a border crossing means leaving the pack is never a false "entering". An Area named by the pack is announced by name with no Source lookup. Point-in-polygon and every rule live in `:core`; the SQL text and the mapping from rows also live in `:core`, behind a tiny `PackDatabase` interface ("run this query"). `:app` implements it with Android's `SQLiteDatabase`, read-only. |
| **Tests** | Behaviour against an in-memory fake. The SQL on the JVM through `PackDatabase` backed by `sqlite-jdbc` (test dependency only), against a fixture pack that the builder makes from a small hand-written `.osm` file, so the builder and the reader share one contract. |
| **Builder** | `scripts/build_region_pack.py` with `pyosmium` and `shapely`: input a Geofabrik extract (Kerala: India's southern zone) and the region's Wikidata id (Kerala: Q1186); pass 1 builds the admin level 2 and 4 polygons and picks the region and its neighbours, pass 2 keeps the places and junctions (centroids for place areas); writes the pack and its manifest. |

| # | Milestone | Test seams (each confirmed before its tests are written) | Done when |
|---|---|---|---|
| **M7a** | **Pack logic in `:core`, against a fake. Done (not yet wired into the app).** `RegionPack` (rows only), `OfflineAreas` (point-in-polygon with holes), `PackFirst` behind a new `SubjectSource` interface, the pack as `AreaTracker`'s first provider (`AreaUpdate.fromPack`), and `StoryDirector.areaChanged(..., fromPack)` | `PackFirst.subjectsAround`: inside the region answers from the pack and never calls Overpass; outside calls Overpass. Area from polygons: Kerala, Mahé as Puducherry, the sea as unknown. `AreaTracker` with the pack: Kerala to Tamil Nadu is an entry, leaving the pack for online is not. `StoryDirector`: an Area named by the pack is announced without a lookup | Offline lookups work in tests, with no network code |
| **M7b** | **SQL and schema. Done.** `PackDatabase` (one query method) and `SqlRegionPack` in `:core`, holding the SQL and the schema (format 1, documented in its KDoc: `meta`, `places` with a 0.05-degree grid `cell`, `areas` with GeoJSON MultiPolygon coordinates); tested on the JVM through `sqlite-jdbc` 3.54.1.0 (test only) against a fixture written from the schema | `SqlRegionPack` on the fixture pack (JVM): the same answers as the fake; a wrong format version is refused | A fixture pack answers the same questions as the fake |
| **M7c** | **Builder. Done.** `scripts/build_region_pack.py`, run with `uv` (pyosmium and shapely declared inside the script), and `scripts/build_fixture_pack.sh`, which rebuilds the fixture pack `core/src/test/resources/packs/kerala-fixture.drivepal` from the hand-written `kerala-fixture.osm` beside it. M7b's tests now read that built pack instead of a hand-written one | `SqlRegionPack` on the built fixture: the same answers as before, plus a place mapped as an area (by its centroid), a place 2 km beyond the border kept and one 50 km beyond left out, and a province that does not touch the region left out | The script builds a pack from a Geofabrik extract |
| **M7d** | **Wiring** | Android `PackDatabase` adapter, pack discovery and the manifest check, an "Offline: Kerala" notice on the main screen; the minor places in the online Overpass query (a `SubjectFinderTest` case) | The app announces from the pack inside the region and goes online outside it |
| **M7e** | **Check** | The fast Pullad to Thiruvalla ride in Names mode with airplane mode on | The same villages are announced as online, no network request is made, and the voice works offline |

**Risks:** an offline text-to-speech voice must be installed on the phone (check at M7e rather than assume it); Geofabrik cuts boundary relations at the edge of its extracts, so the country polygon may not assemble (then the country comes from the region's `ISO3166-2` code or a builder argument); leaving the pack's boundaries hands over to online mid-ride, so a bad connection there leaves a gap; Stories and Landmarks offline are later work.

### The AI part

There is one `StoryWriter` interface, with one client for the **OpenAI-compatible chat completions API** (`POST /v1/chat/completions`). Switching provider means changing the base URL, model and key in settings:

| Provider | Cost | When to use it |
|---|---|---|
| **Ollama (local)**, model `gemma4:e4b` | Free | **Default for development.** Runs on the laptop's RTX 3070. The emulator reaches it at `http://10.0.2.2:11434/v1`. A phone on the same Wi-Fi needs `OLLAMA_HOST=0.0.0.0` on the laptop. Not reachable from the car. |
| **Google Gemini API** (through its OpenAI-compatible endpoint), model `gemini-3.5-flash-lite` | Free tier, rate-limited | **Chosen for real drives** (see the comparison below). Free-tier data may be used by Google to improve its models; it only ever receives place names and article text, never your position. Needs `reasoning_effort` left out. |
| **Groq / OpenRouter free models** | Free tier, rate-limited | Real drives; fast open-source models |
| **OpenAI API** | Paid (separate from a ChatGPT subscription) | Comparing story quality |
| **Claude** (Anthropic Java SDK) | Paid, about $0.01–0.03 per Story | Can be added later as a second `StoryWriter` implementation |

- **API keys:** today a Gradle property, `drivepal.aiKey`, kept in `~/.gradle/gradle.properties` (never in the repository); it ends up inside the APK, which is fine for a personal build and not for a published one. M6 moves it into the app's settings, encrypted with the Android Keystore. The key is sent only over `https`, and the AI provider gets a plain User-Agent without the optional contact.
- **Plain HTTP:** a debug-only network security config allows it for `10.0.2.2` and `localhost` only (a local Ollama, as the emulator sees it). Release builds allow none. Android 17 also asks for a local network permission, which the app requests only when the AI server is on the local network.
- **Failures and timeouts:** the AI call has a 90 s read timeout. On an error, a reply with nothing in it, or a failed check (twice), the Story falls back to the start of the article or section, and the screen says why. A reply cut off by the length limit is trimmed back to its last whole sentence. There is no deadline tied to Relevance: a Story that is no longer relevant when its turn comes is dropped. A fallback in a language with no installed voice is dropped, not read in English.
- **Quality check:** for each test Story, does every factual claim appear in the Source? Done by hand on a handful of real Stories (see below).

#### Model comparison (2026-11-20)

Same prompt, one Dutch article (Molen De Regt, 195 words) that the first local run got wrong, three samples each, Normal Talkativeness, Landmark (target 75–112 words):

| | Local `gemma4:e4b` | `gemini-3.5-flash-lite` | `gemini-3.5-flash` |
|---|---|---|---|
| "built **before** 1808" (the article says *voor 1808*) | wrong 3 of 3 ("built in 1808") | **right 3 of 3** | right 3 of 3 |
| Legend invented | 1 of 3 | 0 of 3 | 0 of 3 |
| Claims about which side of the road (the prompt forbids them) | 0 of 3 | 0 of 3 | 0 of 3 (my word search also matched "right here" and "right beside us", which are not side claims) |
| Time per Story | ~1.4 s warm, ~15 s cold | **~1.6 s** | **30–57 s** (too slow while driving) |
| Words | 65–70 | 100–107 | 102–110 |
| `reasoning_effort: "none"` | accepted, and needed | rejected with HTTP 400 | accepted |

Decision: use **Gemini 3.5 Flash-Lite** for real drives and keep local Ollama for offline development. Dutch terms for mills (*grondzeiler*, *stellingmolen*) are still translated clumsily by every model. A cloud model also faces one-off rate limits on the free tier, so the fallback to the article text stays.

### Testing

- **Unit tests, written first:** bearing and distance geometry, Passing and Relevance, Standstill and the check timer, tile and caching logic, story-worthiness and Notability, priority and Talkativeness gaps, Interlude doubling, Told memory, prompt building, and parsing of Overpass, Nominatim and Wikipedia responses (from recorded JSON).
- **Fakes** stand in for OpenStreetMap, Wikipedia and the AI, so tests are offline and free.
- **Integration:** debug builds can drive a test route by feeding fake GPS (`DebugDrive`, with real roads from OSRM in `DebugRoutes`: Kinderdijk, Kozhencherry and the E19 across the Dutch-Belgian border, parking at the end), and `scripts/replay_route.py` does the same on the emulator.
- **Final check:** a real drive, as the v1 milestone.

### Risks

| Risk | Mitigation |
|---|---|
| Some phone makers kill background services to save battery | A foreground service, plus a "disable battery optimisation" prompt |
| Public Nominatim and Overpass servers are rate-limited or slow | Query by tile, cache aggressively, back off on errors. If needed later, use a commercial OSM host or prefetch along a route |
| Few story-worthy Towns or Landmarks in rural areas | Interludes about the Province or Country; stay silent rather than invent things |
| A daily commute becomes silent once everything is Told | Intended. The 30-day memory brings Stories back eventually |
| A small local model writes weaker Stories or adds facts | Strict grounding prompt; compare providers on the same Sources; switch provider in settings |
| Free cloud tiers change limits or terms | Provider is a setting, so another one can be swapped in |
| Subjects from the Wikipedia fallback include non-places (a political constituency, schools) | M5's priority by Notability should rank them below the temple or the town; a type filter is not possible from geosearch alone |
| GPS speed alone can't tell a jam from walking | The Still-on-the-road check asks instead of guessing |
| `LocationManager` GPS is less battery-efficient than Google's fused provider | Fine while driving (usually on a charger); 5 s interval |
| The built-in text-to-speech voice sounds robotic | Pick the best installed voice; better voices are listed under later work |

## Later work, roughly in priority order

1. **Voice interaction:** "tell me more", "where's that from?" (names the Source), "what was that?", "be quiet for a while", using Android speech recognition.
2. **Crossings:** rivers, borders and mountain passes, as a third kind of subject.
3. **Automatic Trips:** start and stop when the phone connects to or disconnects from the car's Bluetooth.
4. **Memory across Trips:** learn your taste from Skipped Stories; Framing that links to Stories from earlier Trips when the subjects share something specific (the same person, event or family).
5. **Map screen:** MapLibre or osmdroid map with your position and the subjects that were narrated.
6. **Android Auto:** DrivePal is already a media app, so mainly add a car-friendly browse screen.
7. **Planned routes:** take a destination, route it with an OSM router (OSRM or GraphHopper), prepare Stories along the route ahead of time, cache them for offline stretches, and tie them into one narrative across the trip.
8. **On-device AI:** run a small model on the phone (for example Gemma through MediaPipe or Gemini Nano), so it works offline and costs nothing.
9. **Personal taste:** history, nature, food, odd trivia or folklore; a "kids in the car" mode.
10. **Better voices:** cloud text-to-speech or neural voices, and possibly different narrators.
11. **Other languages:** narrate in another language, or bilingual.
12. **Trip recap:** an end-of-drive summary or map of the Stories you heard.
13. **Public release:** a backend proxy for API keys, accounts, Play Store compliance (background location policy, driver-distraction rules), and OSM attribution in the app.


## Review of M3 to M5b (2026-11-21): what changed

An independent review found two HIGH problems, both fixed: Nominatim was never asked for `extratags`, so provinces still fell back to guessing their article from the name (the M5a claim was untrue in production, and its test passed only because the fixture had been recorded with extratags; the URLs now live in `:core` with tests), and Areas were keyed by name only, so Limburg in the Netherlands and Limburg in Belgium shared one article and one Told entry (they are now keyed by country and name, and the logic moved out of `Exploration` into the tested `StoryDirector`). Also fixed: a refused or delayed audio request can no longer wedge the narration, a late grant is handed back, an answered question is no longer asked, a reply cut off by the length limit is trimmed, the grounding checks look at the text the model was actually given, names and titles from the map or Wikipedia are kept out of the instructions (the source is fenced as data) and driving instructions are never spoken, switching between Nominatim and Photon is not a border crossing, a Told file that cannot be saved no longer crashes, a wrong phone clock cannot make a Story "told forever", caches are bounded and an Area that has been left is forgotten.

Not changed, and known: a country's article is guessed as `en:<name>` ("Ireland" is the island); a place within 3 km of where the Trip started stays silent for the whole Trip, even if you come back; and Wikipedia text can still be read as a fallback article without the driving-instruction check.

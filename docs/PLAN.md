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
  | **Quiet** | ~5 min | ~20–30 s | ~30 s | ~45 s | none |
  | **Normal** (default) | ~1 min | ~30–45 s | ~45–60 s | ~60–90 s | ~60–90 s |
  | **Chatty** | ~20 s | ~45–60 s | ~60–75 s | ~90–120 s | ~90–120 s |

- **Interludes:** after 3 in a row with nothing else in between, the Interlude gap doubles (5 → 10 → 20 min). When nothing untold is left, DrivePal stays silent.

**Relevance and memory**
- **Relevant:** a Town or Landmark Story is relevant within about 3 km, ahead or behind. A Story that starts after the subject is passed begins with a lead-in ("back there, behind us…"). Area Stories and Interludes are relevant while you're inside the Area. A Story that is no longer relevant is dropped.
- **Told:** a Story is Told when it plays to the end or is Skipped. A Told Story isn't repeated for about 30 days, across Trips.
- **Interrupted:** a Story cut off by navigation or a call resumes once the audio is free, if it's still Relevant. Otherwise it can come back on a later Trip.
- **Skipped** Stories are recorded, for learning your taste later.

**Trips**
- **Start and stop:** manual, with a Start/Stop button and a persistent notification.
- **Standstill:** below about 5 km/h for more than about 2 minutes, new Town and Landmark Stories wait, but Interludes continue.
- **Still-on-the-road check:** after about 30 minutes without driving speed (above about 15 km/h), DrivePal asks once, "Still on the road? Press play to keep going." With no answer within about 2 minutes, the Trip ends.

### Architecture

```
TripService (foreground service, location)
  ├─ LocationTracker      Android LocationManager (GPS): position, speed, heading every ~5 s
  ├─ MotionState          driving / Standstill / Still-on-the-road check
  ├─ AreaTracker          entering a Country or Province (Nominatim, rate-limited and cached)
  ├─ PassingDetector      Towns and Landmarks within ~3 km (Overpass, one query per area tile, cached)
  ├─ SourceLibrary        Wikipedia extracts and sections, story-worthiness and Notability (cached)
  ├─ StoryPicker          priority, Talkativeness gap, Relevance, Interludes, Told memory
  ├─ StoryWriter          an AI model writes a Story from the Source (provider can be swapped)
  ├─ Narrator             text-to-speech, lowers your music, Media3 session (skip, play, check answer)
  └─ StoryMemory (Room)   Trips, Told / Skipped / Interrupted Stories, Sources
UI (Jetpack Compose): Start/Stop Trip, the Story playing now, trip log (with Sources), settings
```

- **No Google Play Services** ([ADR 0001](adr/0001-no-google-play-services.md)).
- To keep it simple, the parts are connected by hand in code rather than through a dependency-injection library such as Hilt.

### Data sources

| Job | Source | Usage rules |
|---|---|---|
| GPS position, speed, heading | Android `LocationManager` (GPS provider) | none |
| Country and Province you're in | Nominatim reverse geocoding (OpenStreetMap) | At most 1 request per second, a User-Agent that identifies the app, cached results. Only query after moving a few km. |
| Towns and Landmarks nearby | Overpass API: `place=city/town/village`, `historic=*`, `tourism=attraction/viewpoint/museum`, `man_made=lighthouse/windmill` and the like, with a `wikipedia` or `wikidata` tag | One query per area tile (about 10 km), cached |
| Sources | Wikipedia Action API (extract and language links); Wikidata only for subjects without a Wikipedia tag | Polite User-Agent (add a contact with `-Pdrivepal.contact=`), requests spaced 300 ms apart, answers cached, failures retried after 30 s |
| Map on screen (after version 1) | MapLibre or osmdroid with OpenStreetMap tiles | Tile usage policy |

### Milestones

Each one ends with something you can run and check.

| # | Milestone | Done when |
|---|---|---|
| **M0** | **Project skeleton.** Gradle 9.9 / Android Gradle Plugin 9.5 / Kotlin 2.4 / Compose; minimum Android 10 (API 29), target Android 37 | The app builds, installs and opens on `DrivePal_API37` |
| **M1** | **Trip and location.** Foreground service; `LocationManager` GPS updates; permissions; live speed and heading on screen; Standstill detection and the Still-on-the-road check | Replaying a route in the emulator updates speed (and heading, where the device reports a bearing); tracking continues with the screen off; a parked replay triggers the check and ends the Trip |
| **M2** | **Finding subjects. Done.** Entering a Country or Province (Nominatim); Passing Towns and Landmarks within about 3 km (Overpass, one query per 0.1° tile, cached); heading derived from consecutive fixes; Sources from Wikipedia (language links give the number of language editions, Wikidata only when a subject has no Wikipedia tag); story-worthy means 150+ words; failed lookups are shown on screen and retried | On a replayed route past Kinderdijk the app entered the Netherlands and South Holland, then Passed towns and landmarks in order, with Source word counts and language editions, against the live services |
| **M3** | **Writing Stories. Done for Towns and Landmarks; Areas and Interludes come with M5.** `ChatClient` for any OpenAI-compatible server (thinking switched off with `reasoning_effort: none`, which fixed empty answers); `StoryPrompt` (length by kind and Talkativeness, grounding rules, "Back there, behind us…" lead-in, Framing from earlier Stories, no left/right claims); `StoryWriter` (strips stage directions and markdown, cuts at a sentence, retries once when a number or legend is not in the article, then falls back to the start of the article) | Live on the emulator against real Wikipedia and local Ollama (`gemma4:e4b`): Stories in English from Dutch articles. **Known limit:** the small local model mistranslates Dutch ("built before 1808" became "built in 1808"), which no check can catch. Compared with Gemini (see the model comparison): Flash-Lite got it right every time at the same speed, so it is the default for real drives |
| **M4** | **Narration.** Android text-to-speech; music is lowered while it talks; Media3 session (*next* = Skip, *play* = replay or answer the check); resume Interrupted Stories | A Story plays over music, Bluetooth buttons work, and a simulated call interrupts and resumes it |
| **M5** | **Pacing and memory.** Priority order, Talkativeness gaps, Relevance and dropping, Interludes with doubling gap, Told memory (30 days, across Trips) | An hour-long replayed route sounds natural in all three modes; replaying it a second time doesn't repeat Told Stories |
| **M6** | **Trip log and settings.** Trips with their Stories and Sources; Talkativeness; AI provider, endpoint, model and API key | You can look back over past Trips and see each Story's Source; you can switch AI providers without rebuilding |
| **✅ v1** | **A real drive** | A 1-hour real drive with no crashes and Stories that land |

### The AI part

There is one `StoryWriter` interface, with one client for the **OpenAI-compatible chat completions API** (`POST /v1/chat/completions`). Switching provider means changing the base URL, model and key in settings:

| Provider | Cost | When to use it |
|---|---|---|
| **Ollama (local)**, model `gemma4:e4b` | Free | **Default for development.** Runs on the laptop's RTX 3070. The emulator reaches it at `http://10.0.2.2:11434/v1`. A phone on the same Wi-Fi needs `OLLAMA_HOST=0.0.0.0` on the laptop. Not reachable from the car. |
| **Google Gemini API** (through its OpenAI-compatible endpoint), model `gemini-3.5-flash-lite` | Free tier, rate-limited | **Chosen for real drives** (see the comparison below). Free-tier data may be used by Google to improve its models; it only ever receives place names and article text, never your position. Needs `reasoning_effort` left out. |
| **Groq / OpenRouter free models** | Free tier, rate-limited | Real drives; fast open-source models |
| **OpenAI API** | Paid (separate from a ChatGPT subscription) | Comparing story quality |
| **Claude** (Anthropic Java SDK) | Paid, about $0.01–0.03 per Story | Can be added later as a second `StoryWriter` implementation |

- **API keys:** stored in `local.properties` (not committed to git) or entered in settings, and stored on the device. That's fine for personal use, but no key may be in the app if it is ever published.
- **Plain HTTP:** only allowed for `10.0.2.2` and LAN addresses (for Ollama), through a debug-only network security config.
- **Timeouts:** a Story must arrive while it's still Relevant. If the model is too slow, fall back to the Wikipedia summary.
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
- **Integration:** GPX route replay in the emulator, with 2–3 sample routes in the repo (motorway, through a town, a parked stop), against local Ollama.
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

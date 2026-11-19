# DrivePal

A driving companion that tells spoken stories about the places the driver passes through.

## Language

### Story subjects

**Area**:
A place with an extent, at one of the Area levels (Country, Province or Town). The driver enters a Country or Province, and passes a Town.
_Avoid_: Region, place, zone

**Area level**:
How big an Area is: Country, Province or Town. Each level gets its own kind of story; Country and Province get a "big picture" story.
_Avoid_: Tier, scale

**Province**:
The first level of Area below a Country (a province, state or region, depending on the country).
_Avoid_: State, region

**Town**:
A settlement (city, town or village) as people experience it, not the municipality that administers it. The driver can pass a Town without entering it.
_Avoid_: Municipality, gemeente, locality, city

**Landmark**:
A specific notable point near the road, such as a castle, windmill or viewpoint, that the driver passes.
_Avoid_: POI, point of interest, sight, attraction

**Passing**:
The moment the driver's route comes near a Town or Landmark (within sight, about 3 km), whether or not it goes through it.
_Avoid_: Visiting, encountering, nearby

**Crossing** _(not in version 1)_:
A line the driver goes over at a single moment, such as a river, a border or a mountain pass.
_Avoid_: Boundary, transition

### Choosing subjects

**Source**:
The encyclopedia article (in any language) that a Story about an Area or Landmark is based on. Nothing gets a Story without a Source.
_Avoid_: Article, reference, facts

**Story-worthy**:
Describes a Town or Landmark whose Source has enough substance to base a Story on. Thin stubs aren't story-worthy.
_Avoid_: Notable, interesting, eligible

**Notability**:
How well known a Town or Landmark is, used to choose between subjects that are passed at the same time.
_Avoid_: Popularity, importance, score

### Trips

**Trip**:
One drive, from when the driver starts DrivePal until they stop it.
_Avoid_: Drive, session, journey, ride

**Told**:
Describes a Story that played to the end or was Skipped. A Told Story isn't repeated for a while (about 30 days), however many Trips pass. A Story that was Interrupted or never played isn't Told.
_Avoid_: Played, heard, used, spent

**Skipped**:
Describes a Story the driver deliberately cut short. It counts as Told, and is a sign of what the driver doesn't care about.
_Avoid_: Dismissed, rejected, cancelled

**Interrupted**:
Describes a Story cut off by something other than the driver, such as a navigation instruction or a phone call. It resumes once the audio is free, if it's still relevant; otherwise it can come back on a later Trip.
_Avoid_: Paused, cut off, stopped

**Standstill**:
Part of a Trip where the driver is barely moving (below about 5 km/h for more than about 2 minutes), as in a traffic jam or a stop. New Town and Landmark Stories wait; Interludes continue.
_Avoid_: Jam, parked, stopped, paused

**Still-on-the-road check**:
A single spoken question after about 30 minutes without driving speed, asking the driver to press play to keep the Trip going. With no answer, the Trip ends.
_Avoid_: Timeout, auto-stop, idle check

**Talkativeness**:
How much DrivePal talks on a Trip: Quiet, Normal (the default) or Chatty. It sets both the minimum gap between Stories and how long each Story is; Quiet also means no Interludes.
_Avoid_: Frequency, verbosity, chattiness, mode

### Stories

**Story**:
A short spoken narration about one Area or Landmark. Every factual claim in it comes from its Source, and legends appear only if the Source reports them, told as legends.
_Avoid_: Narration, commentary, fact, blurb

**Interlude**:
A Story told to fill a silence, about the Province or Country the driver is in, taken from a part of its Source that hasn't been Told.
_Avoid_: Filler, gap story, bonus story

**Relevant**:
Describes a Story whose subject is still close enough to talk about. A Town or Landmark is relevant within the Passing range (about 3 km), ahead or behind; a Story about a subject already passed begins with a short lead-in ("back there, behind us…"). An Area Story or Interlude is relevant while the driver is inside that Area.
_Avoid_: Current, valid, fresh, in range

**Framing**:
The parts of a Story that make no factual claim: atmosphere, the driver's situation ("on your left…") and links to earlier Stories on the trip.
_Avoid_: Embellishment, flavour, colour

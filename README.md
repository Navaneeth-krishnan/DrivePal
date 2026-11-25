# DrivePal

A hands-free Android driving companion. As you drive, it announces the places you pass ("You are entering Thiruvalla.") and, if you like, tells short Stories about them, written by an AI and based only on their Wikipedia articles.

- **Talkativeness:** Names (only the place names), Normal or Chatty.
- **Offline in South India:** the app comes with a Region pack for Kerala, Karnataka, Tamil Nadu, Andhra Pradesh, Telangana, Puducherry, Lakshadweep and the Andaman and Nicobar Islands, so place names work with no network. Elsewhere it uses the online OpenStreetMap services.
- **Malayalam:** place names can be announced in Malayalam ("അടുത്ത സ്ഥലം: തിരുവല്ല."). Stories stay in English.

Terms such as Town, Landmark, Story and Region pack are defined in [`CONTEXT.md`](CONTEXT.md); the plan and the decisions behind it are in [`docs/PLAN.md`](docs/PLAN.md).

## Building

Needs JDK 17 and the Android SDK (Android 10 or newer on the phone).

```sh
./gradlew :core:test            # the logic, on the JVM
./gradlew :app:assembleDebug    # "DrivePal debug" (app.drivepal.debug), with simulated drives
./gradlew :app:assembleRelease  # "DrivePal" (app.drivepal)
```

Settings that must not be in the repository go in `~/.gradle/gradle.properties`:

| Property | What it is |
|---|---|
| `drivepal.aiUrl`, `drivepal.aiModel`, `drivepal.aiKey`, `drivepal.aiReasoningEffort` | The AI that writes the Stories: any OpenAI-compatible server. The key ends up inside the APK, which is fine for a personal build and not for a published one. They can also be set in the app's Settings. |
| `drivepal.contact` | A contact (preferably a project URL) for the User-Agent that Wikimedia and OpenStreetMap ask for. |
| `drivepal.releaseStoreFile`, `drivepal.releaseStorePassword`, `drivepal.releaseKeyAlias`, `drivepal.releaseKeyPassword` | Release signing. Without them a release build is left unsigned. |

## Region packs

A Region pack is a SQLite file of place names and boundaries for one or more States or Provinces, built from an OpenStreetMap extract with `scripts/build_region_pack.py` (run with [uv](https://docs.astral.sh/uv/), which fetches pyosmium and shapely). The bundled pack is rebuilt from Geofabrik's India southern zone with:

```sh
curl -LO https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf
scripts/build_bundled_pack.sh southern-zone-latest.osm.pbf
```

A pack copied to `Android/data/<app id>/files/packs/` on the phone, with its manifest (`.json`) beside it, is used instead of the bundled one.

## Data and licences

- **Code:** MIT licence, see [`LICENSE`](LICENSE).
- **Map data:** © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/). This covers the bundled Region pack (`app/src/main/assets/packs/`) and the place names, boundaries and positions the app reads from the OpenStreetMap services (Overpass, Nominatim, Photon). The MIT licence does not apply to this data. (The test packs in `core/src/test/resources/packs/` are built from a small made-up extract, not from OpenStreetMap.)
- **Stories:** written from [Wikipedia](https://www.wikipedia.org/) articles, whose text is available under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The Trip log records which article each Story came from.

# No Google Play Services: Android LocationManager and OpenStreetMap

DrivePal gets its position, speed and heading from Android's built-in `LocationManager` (GPS provider), and all place data (Areas, Towns, Landmarks) from OpenStreetMap through Nominatim and the Overpass API. It does not use Google Play Services, so no fused location provider, no Google Geocoder and no activity recognition. We chose this to stay independent of Google's services and terms, and to use OpenStreetMap's open, richly tagged data, including its `wikipedia`/`wikidata` links to each subject's Source.

## Consequences

- GPS-only location uses more battery than Google's fused provider. That's acceptable while driving, because the phone is usually charging.
- Without activity recognition we can't detect "in a vehicle", so Trips start manually, and a Standstill or the end of a Trip is worked out from GPS speed alone (hence the Still-on-the-road check).
- The public Nominatim and Overpass servers have usage limits, so place lookups must be cached per area tile and rate-limited. Heavier use would need a commercial OpenStreetMap host or our own.

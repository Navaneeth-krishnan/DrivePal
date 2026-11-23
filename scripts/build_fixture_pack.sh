#!/bin/sh
# Rebuilds the fixture Region packs that :core's tests read (Kerala alone; Kerala and Tamil Nadu), from the
# hand-written extract beside them.
set -eu
cd "$(dirname "$0")/.."
packs=core/src/test/resources/packs
scripts/build_region_pack.py "$packs/kerala-fixture.osm" --wikidata Q1186 --output "$packs/kerala-fixture.drivepal"
scripts/build_region_pack.py "$packs/kerala-fixture.osm" --wikidata Q1186 Q1445 --name "South India" \
  --output "$packs/south-fixture.drivepal"

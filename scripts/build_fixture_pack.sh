#!/bin/sh
# Rebuilds the fixture Region pack that :core's tests read, from the hand-written extract beside it.
set -eu
cd "$(dirname "$0")/.."
packs=core/src/test/resources/packs
scripts/build_region_pack.py "$packs/kerala-fixture.osm" --wikidata Q1186 --output "$packs/kerala-fixture.drivepal"

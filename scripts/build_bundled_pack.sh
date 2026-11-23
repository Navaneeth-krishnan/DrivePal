#!/bin/sh
# Rebuilds the Region pack that comes with the app (South India) from Geofabrik's India southern zone extract:
#   curl -LO https://download.geofabrik.de/asia/india/southern-zone-latest.osm.pbf
#   scripts/build_bundled_pack.sh southern-zone-latest.osm.pbf
set -eu
extract=$(realpath "$1")
cd "$(dirname "$0")/.."
# Kerala, Karnataka, Tamil Nadu, Andhra Pradesh, Telangana, Puducherry, Lakshadweep, Andaman and Nicobar Islands
scripts/build_region_pack.py "$extract" \
  --wikidata Q1186 Q1185 Q1445 Q1159 Q677037 Q66743 Q26927 Q40888 \
  --name "South India" --country India \
  --output app/src/main/assets/packs/south-india.drivepal

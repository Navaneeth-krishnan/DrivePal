#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["osmium>=4.0", "shapely>=2.0"]
# ///
"""Builds a DrivePal Region pack (format 1) from an OpenStreetMap extract.

Usage:
    scripts/build_region_pack.py EXTRACT --wikidata Q1186 --output kerala.drivepal [--country India]

EXTRACT is an .osm or .osm.pbf file that holds the whole region, such as Geofabrik's India southern zone for Kerala.
The region is the State or Province (boundary=administrative, admin_level=4) with that Wikidata id. The pack holds:

- the region and every admin level 4 neighbour that touches it, each with its country, simplified to about 50 m
  and keeping holes (Mahé, Puducherry's enclave in Kerala);
- named places (city, town, village, suburb, hamlet, locality; areas by their centroid) and named motorway
  junctions, inside the region or within 3 km of its border.

Beside the pack it writes a manifest (kerala.json) with the pack's size and SHA-256. The schema is the contract
with the app's reader, documented in core/src/main/kotlin/app/drivepal/core/SqlRegionPack.kt.
--country names the country when the extract cuts off the country's own boundary.
"""
import argparse
import hashlib
import json
import math
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path

import osmium
from shapely import wkb
from shapely.geometry import Point, mapping
from shapely.prepared import prep

FORMAT_VERSION = 1
PLACE_TYPES = {"city", "town", "village", "suburb", "hamlet", "locality"}
JUNCTION_TYPE = "motorway_junction"
CELL_DEGREES = 0.05
COLUMNS = 7200  # 360 degrees of longitude in 0.05-degree cells
SIMPLIFY_DEGREES = 0.0005  # about 50 m
BEYOND_BORDER_DEGREES = 0.027  # about 3 km: the Passing range just outside the region
NEIGHBOUR_DEGREES = 0.01  # touching, allowing for boundaries mapped a little apart
DECIMALS = 6  # about 0.1 m


@dataclass(frozen=True)
class Boundary:
    level: int
    name: str
    geometry: object
    wikipedia: str | None
    wikidata: str | None


@dataclass(frozen=True)
class Place:
    id: str
    name: str
    type: str
    lat: float
    lon: float
    wikipedia: str | None
    wikidata: str | None


def name_of(tags) -> str | None:
    """The English name when tagged, as the app does online."""
    return tags.get("name:en") or tags.get("name")


def read_extract(path: Path) -> tuple[list[Boundary], list[Place]]:
    """Every admin level 2 and 4 boundary, and every named place and junction anywhere in the extract."""
    to_shape = osmium.geom.WKBFactory()
    boundaries: list[Boundary] = []
    places: list[Place] = []
    processor = (
        osmium.FileProcessor(str(path))
        .with_areas()
        .with_filter(osmium.filter.KeyFilter("place", "boundary", "highway"))
    )
    for item in processor:
        tags = item.tags
        name = name_of(tags)
        if name is None:
            continue
        if item.is_node():
            kind = place_type(tags)
            if kind is not None:
                places.append(Place(f"node/{item.id}", name, kind, item.lat, item.lon, tags.get("wikipedia"), tags.get("wikidata")))
        elif item.is_area():
            geometry = shape_of(item, to_shape)
            if geometry is None:
                continue
            level = tags.get("admin_level")
            if tags.get("boundary") == "administrative" and level in ("2", "4") and not item.from_way():
                boundaries.append(Boundary(int(level), name, geometry, tags.get("wikipedia"), tags.get("wikidata")))
            kind = tags.get("place")
            if kind in PLACE_TYPES:
                centre = geometry.centroid
                osm_type = "way" if item.from_way() else "relation"
                places.append(Place(f"{osm_type}/{item.orig_id()}", name, kind, centre.y, centre.x, tags.get("wikipedia"), tags.get("wikidata")))
    return boundaries, places


def place_type(tags) -> str | None:
    if tags.get("place") in PLACE_TYPES:
        return tags.get("place")
    if tags.get("highway") == JUNCTION_TYPE:
        return JUNCTION_TYPE
    return None


def shape_of(area, factory):
    try:
        return wkb.loads(factory.create_multipolygon(area), hex=True)
    except RuntimeError:
        return None  # a broken ring: libosmium could not assemble it


def country_of(boundary: Boundary, countries: list[Boundary], fallback: str | None) -> str:
    inside = boundary.geometry.representative_point()
    for country in countries:
        if country.geometry.contains(inside):
            return country.name
    if fallback is None:
        sys.exit(f"No country boundary around {boundary.name} in the extract: pass --country")
    return fallback


def cell_of(lat: float, lon: float) -> int:
    return math.floor((lat + 90) / CELL_DEGREES) * COLUMNS + math.floor((lon + 180) / CELL_DEGREES)


def rounded(coordinates):
    if isinstance(coordinates[0], (int, float)):
        return [round(coordinates[0], DECIMALS), round(coordinates[1], DECIMALS)]
    return [rounded(part) for part in coordinates]


def polygons_json(geometry) -> str:
    """GeoJSON MultiPolygon coordinates: [longitude, latitude], each polygon's outline first, then its holes."""
    simplified = geometry.simplify(SIMPLIFY_DEGREES, preserve_topology=True)
    shape = mapping(simplified)
    polygons = shape["coordinates"] if shape["type"] == "MultiPolygon" else [shape["coordinates"]]
    return json.dumps(rounded(polygons), separators=(",", ":"))


def header_timestamp(path: Path) -> str:
    reader = osmium.io.Reader(str(path), osmium.osm.osm_entity_bits.NOTHING)
    try:
        return reader.header().get("osmosis_replication_timestamp", "")
    finally:
        reader.close()


def build(extract: Path, wikidata: str, output: Path, country: str | None) -> None:
    boundaries, places = read_extract(extract)
    provinces = [b for b in boundaries if b.level == 4]
    countries = [b for b in boundaries if b.level == 2]
    region = next((b for b in provinces if b.wikidata == wikidata), None)
    if region is None:
        sys.exit(f"No admin level 4 boundary with wikidata={wikidata} in {extract}")

    touching = prep(region.geometry.buffer(NEIGHBOUR_DEGREES))
    areas = sorted((b for b in provinces if touching.intersects(b.geometry)), key=lambda b: b.name)
    reach = prep(region.geometry.buffer(BEYOND_BORDER_DEGREES))
    kept = sorted((p for p in places if reach.contains(Point(p.lon, p.lat))), key=lambda p: p.id)
    region_country = country_of(region, countries, country)

    partial = output.with_suffix(output.suffix + ".partial")
    partial.unlink(missing_ok=True)
    with sqlite3.connect(partial) as db:
        db.executescript(SCHEMA)
        meta = {
            "format_version": str(FORMAT_VERSION),
            "region": region.name,
            "country": region_country,
            "source": extract.name,
            "data_timestamp": header_timestamp(extract),
        }
        db.executemany("INSERT INTO meta VALUES (?, ?)", sorted(meta.items()))
        db.executemany(
            "INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            [(p.id, p.name, p.type, round(p.lat, DECIMALS), round(p.lon, DECIMALS), cell_of(p.lat, p.lon), p.wikipedia, p.wikidata) for p in kept],
        )
        db.executemany(
            "INSERT INTO areas (name, country, is_region, south, west, north, east, polygons, wikipedia, wikidata) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [area_row(a, a is region, country_of(a, countries, country)) for a in areas],
        )
    with sqlite3.connect(partial) as db:
        db.execute("VACUUM")
    partial.replace(output)
    write_manifest(output, region.name)
    print(f"{output}: {region.name}, {region_country}; {len(kept)} places, {len(areas)} areas, {output.stat().st_size} bytes")


def area_row(area: Boundary, is_region: bool, country: str):
    west, south, east, north = area.geometry.bounds
    return (area.name, country, 1 if is_region else 0, south, west, north, east, polygons_json(area.geometry), area.wikipedia, area.wikidata)


def write_manifest(pack: Path, region: str) -> None:
    digest = hashlib.sha256(pack.read_bytes()).hexdigest()
    manifest = {"file": pack.name, "region": region, "format_version": FORMAT_VERSION, "size": pack.stat().st_size, "sha256": digest}
    pack.with_suffix(".json").write_text(json.dumps(manifest, indent=2) + "\n")


SCHEMA = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE places (id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL,
    lat REAL NOT NULL, lon REAL NOT NULL, cell INTEGER NOT NULL, wikipedia TEXT, wikidata TEXT);
CREATE INDEX places_cell ON places (cell);
CREATE TABLE areas (id INTEGER PRIMARY KEY, name TEXT NOT NULL, country TEXT NOT NULL, is_region INTEGER NOT NULL,
    south REAL NOT NULL, west REAL NOT NULL, north REAL NOT NULL, east REAL NOT NULL,
    polygons TEXT NOT NULL, wikipedia TEXT, wikidata TEXT);
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("extract", type=Path, help="an .osm or .osm.pbf file holding the whole region")
    parser.add_argument("--wikidata", required=True, help="the region's Wikidata id, such as Q1186 for Kerala")
    parser.add_argument("--output", required=True, type=Path, help="the pack to write, such as kerala.drivepal")
    parser.add_argument("--country", help="the country's name, if the extract has no boundary for it")
    args = parser.parse_args()
    build(args.extract, args.wikidata, args.output, args.country)


if __name__ == "__main__":
    main()

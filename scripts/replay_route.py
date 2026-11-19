#!/usr/bin/env python3
"""Replays a drive into the running Android emulator, in real time, one GPS fix every 5 s.

Usage: replay_route.py SCENARIO
Scenarios are lists of (seconds, km/h) legs, driven from a start point on the A13 near Rotterdam.
"""
import math
import subprocess
import sys
import time

SCENARIOS = {
    # motorway, a 3-minute jam, then moving off again
    "jam": [(60, 100), (180, 2), (35, 60)],
    # motorway, then a 90-second red light
    "red-light": [(60, 100), (90, 0), (30, 50)],
    # motorway, then parked and not moving for 33 minutes: the check, then the end of the Trip
    "parked": [(60, 100), (1980, 0)],
}

START_LAT, START_LON, HEADING_DEG = 51.9244, 4.4777, 315.0
STEP_SECONDS = 5
KNOTS_PER_MS = 1.943844  # the emulator takes velocity in knots
METERS_PER_DEGREE_LAT = 111_320.0


def fixes(legs):
    lat, lon = START_LAT, START_LON
    for seconds, kmh in legs:
        for _ in range(seconds // STEP_SECONDS):
            meters = kmh / 3.6 * STEP_SECONDS
            lat += meters * math.cos(math.radians(HEADING_DEG)) / METERS_PER_DEGREE_LAT
            lon += meters * math.sin(math.radians(HEADING_DEG)) / (
                METERS_PER_DEGREE_LAT * math.cos(math.radians(lat))
            )
            yield lat, lon, kmh / 3.6 * KNOTS_PER_MS


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in SCENARIOS:
        sys.exit(f"usage: replay_route.py {{{'|'.join(SCENARIOS)}}}")
    for lat, lon, knots in fixes(SCENARIOS[sys.argv[1]]):
        subprocess.run(
            ["adb", "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "0", "10", f"{knots:.2f}"],
            check=True, stdout=subprocess.DEVNULL,
        )
        time.sleep(STEP_SECONDS)


if __name__ == "__main__":
    main()

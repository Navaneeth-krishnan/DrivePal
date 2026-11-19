#!/usr/bin/env python3
"""Replays a drive into the running Android emulator, in real time, one GPS fix every 5 s.

Usage: replay_route.py SCENARIO
Scenarios are lists of (seconds, km/h) legs, driven from a start point on the A13 near Rotterdam.
"""
import math
import subprocess
import sys
import time

# name -> (start latitude, start longitude, heading in degrees, [(seconds, km/h), ...])
ROTTERDAM_A13 = (51.9244, 4.4777, 315.0)
SCENARIOS = {
    # motorway, a 3-minute jam, then moving off again
    "jam": (*ROTTERDAM_A13, [(60, 100), (180, 2), (35, 60)]),
    # motorway, then a 90-second red light
    "red-light": (*ROTTERDAM_A13, [(60, 100), (90, 0), (30, 50)]),
    # motorway, then parked and not moving for 33 minutes: the check, then the end of the Trip
    "parked": (*ROTTERDAM_A13, [(60, 100), (1980, 0)]),
    # due north at 100 km/h straight past Kinderdijk (51.8826 N, 4.6403 E): towns, windmills, a province
    "kinderdijk": (51.8400, 4.6403, 0.0, [(330, 100)]),
}

STEP_SECONDS = 5
KNOTS_PER_MS = 1.943844  # the emulator takes velocity in knots
METERS_PER_DEGREE_LAT = 111_320.0


def fixes(start_lat, start_lon, heading_deg, legs):
    lat, lon = start_lat, start_lon
    for seconds, kmh in legs:
        for _ in range(seconds // STEP_SECONDS):
            meters = kmh / 3.6 * STEP_SECONDS
            lat += meters * math.cos(math.radians(heading_deg)) / METERS_PER_DEGREE_LAT
            lon += meters * math.sin(math.radians(heading_deg)) / (
                METERS_PER_DEGREE_LAT * math.cos(math.radians(lat))
            )
            yield lat, lon, kmh / 3.6 * KNOTS_PER_MS


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in SCENARIOS:
        sys.exit(f"usage: replay_route.py {{{'|'.join(SCENARIOS)}}}")
    for lat, lon, knots in fixes(*SCENARIOS[sys.argv[1]]):
        subprocess.run(
            ["adb", "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "0", "10", f"{knots:.2f}"],
            check=True, stdout=subprocess.DEVNULL,
        )
        time.sleep(STEP_SECONDS)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Pack the DB-IP Lite CSV and Natural Earth 110m country GeoJSON for the CC3 map."""
import argparse
import csv
import gzip
import ipaddress
import json
import struct
from pathlib import Path


def pack_ips(source, target):
    ranges = {4: [], 6: []}
    with gzip.open(source, "rt", newline="") as stream:
        for first, last, country in csv.reader(stream):
            start = ipaddress.ip_address(first)
            end = ipaddress.ip_address(last)
            if len(country) == 2 and country.isalpha() and country != "ZZ":
                ranges[start.version].append((int(start), int(end), country.encode("ascii")))
    with target.open("wb") as stream:
        stream.write(struct.pack(">4sII", b"CC3G", len(ranges[4]), len(ranges[6])))
        for version, records in ranges.items():
            width = 4 if version == 4 else 16
            end_of_previous = -1
            for start, end, country in sorted(records):
                if start <= end_of_previous or start > end:
                    raise ValueError("Invalid IP range ordering")
                stream.write(start.to_bytes(width, "big"))
                stream.write(end.to_bytes(width, "big"))
                stream.write(country)
                end_of_previous = end


def pack_map(source, target):
    result = []
    for feature in json.loads(source.read_text())["features"]:
        properties = feature["properties"]
        geometry = feature["geometry"]
        if properties["ADMIN"] == "Antarctica":
            continue
        polygons = geometry["coordinates"]
        if geometry["type"] == "Polygon":
            polygons = [polygons]
        outlines = []
        for exterior, *_ in polygons:
            outlines.append([[round(lon, 2), round(lat, 2)] for lon, lat, *_ in exterior])
        result.append({
            "code": properties["ISO_A2_EH"],
            "point": [properties["LABEL_X"], properties["LABEL_Y"]],
            "outlines": outlines,
        })
    target.write_text(json.dumps(result, separators=(",", ":")) + "\n")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("country_csv_gz", type=Path)
parser.add_argument("natural_earth_geojson", type=Path)
parser.add_argument("assets", type=Path)
args = parser.parse_args()
pack_ips(args.country_csv_gz, args.assets / "country-ranges.bin")
pack_map(args.natural_earth_geojson, args.assets / "world.json")

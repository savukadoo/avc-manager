#!/usr/bin/env python3
"""Compare the vertical extents of flat fills in a native screenshot."""
import argparse
from pathlib import Path

from PIL import Image


def point(value):
    try:
        x, y = value.split(",")
        return int(x), int(y)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Use pixel coordinates X,Y.") from error


def vertical_span(image, x, y):
    pixels = image.load()
    colour = pixels[x, y]
    top = bottom = y
    while top > 0 and pixels[x, top - 1] == colour:
        top -= 1
    while bottom + 1 < image.height and pixels[x, bottom + 1] == colour:
        bottom += 1
    return top, bottom


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screenshot", type=Path)
    parser.add_argument("--at", type=point, required=True, action="append", metavar="X,Y")
    parser.add_argument("--tolerance", type=int, default=0, help="allowed edge difference in pixels")
    args = parser.parse_args()
    if len({x for x, _ in args.at}) < 2:
        parser.error("Choose points in at least two different columns.")
    if args.tolerance < 0:
        parser.error("Tolerance must be zero or greater.")

    try:
        with Image.open(args.screenshot) as source:
            image = source.convert("RGB")
    except OSError as error:
        parser.error(str(error))

    spans = []
    for x, y in args.at:
        if not (0 <= x < image.width and 0 <= y < image.height):
            parser.error(f"Point ({x}, {y}) falls outside the {image.width} × {image.height} image.")
        top, bottom = vertical_span(image, x, y)
        if top == bottom or bottom - top + 1 == image.height:
            parser.error(f"No distinct vertical fill at ({x}, {y}). Select another interior point.")
        spans.append((top, bottom))
        print(f"({x}, {y})  top {top}  bottom {bottom}  height {bottom - top + 1} px")

    tops, bottoms = zip(*spans)
    top_delta = max(tops) - min(tops)
    bottom_delta = max(bottoms) - min(bottoms)
    print(f"Edge differences  top {top_delta} px  bottom {bottom_delta} px")
    return int(max(top_delta, bottom_delta) > args.tolerance)


if __name__ == "__main__":
    raise SystemExit(main())

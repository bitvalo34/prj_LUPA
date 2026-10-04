#!/usr/bin/env python3
"""Build a DeepZoom pyramid from a PNG byte stream on stdin.

This is evaluation tooling, not part of the LUPA runtime. It exists to prove that
very large official PNG samples can be consumed without materializing the full
PNG on local storage. The caller is responsible for supplying the exact stream
(e.g. `unzip -p archive.zip member.png`).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

try:
    import pyvips
except ImportError as exc:  # pragma: no cover - exercised by operator setup
    raise SystemExit(
        "pyvips is required. On Ubuntu install it with: sudo apt install -y python3-pyvips"
    ) from exc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-base", required=True, help="DeepZoom base path without extension")
    parser.add_argument("--quality", type=int, default=85)
    parser.add_argument("--tile-size", type=int, default=256)
    parser.add_argument("--expected-width", type=int)
    parser.add_argument("--expected-height", type=int)
    parser.add_argument("--expected-bands", type=int, default=3)
    parser.add_argument("--max-cache-mib", type=int, default=256)
    parser.add_argument("--metadata-out", help="Optional JSON metadata output path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.quality <= 100:
        raise SystemExit("--quality must be between 1 and 100")
    if args.tile_size <= 0:
        raise SystemExit("--tile-size must be positive")
    if args.max_cache_mib <= 0:
        raise SystemExit("--max-cache-mib must be positive")

    output_base = Path(args.output_base).expanduser().resolve()
    output_base.parent.mkdir(parents=True, exist_ok=True)

    # Bound libvips' operation cache. This is not a total RSS limit, but prevents
    # the operation cache itself from growing without an explicit ceiling.
    pyvips.cache_set_max_mem(args.max_cache_mib * 1024 * 1024)

    started = time.monotonic()
    bytes_read = 0

    source = pyvips.SourceCustom()

    def read_handler(size: int) -> bytes:
        nonlocal bytes_read
        chunk = sys.stdin.buffer.read(size)
        bytes_read += len(chunk)
        return chunk

    source.on_read(read_handler)

    # Let libvips select pngload_source from the PNG signature. Sequential access
    # is intentional: stdin cannot seek, and the official evaluation PNGs are
    # non-interlaced top-to-bottom streams.
    image = pyvips.Image.new_from_source(
        source,
        "",
        access="sequential",
        fail_on="error",
    )

    input_width = image.width
    input_height = image.height
    input_bands = image.bands
    input_format = image.format
    input_interpretation = image.interpretation

    if args.expected_width is not None and input_width != args.expected_width:
        raise SystemExit(
            f"width mismatch: expected {args.expected_width}, stream reports {input_width}"
        )
    if args.expected_height is not None and input_height != args.expected_height:
        raise SystemExit(
            f"height mismatch: expected {args.expected_height}, stream reports {input_height}"
        )
    if input_bands != args.expected_bands:
        raise SystemExit(
            f"band mismatch: expected {args.expected_bands}, stream reports {input_bands}"
        )

    # The official samples currently report RGB/uchar. Keep the conversion here
    # so the resulting tiles follow the same sRGB policy as the normal importer.
    if image.interpretation != "srgb":
        image = image.colourspace("srgb")

    suffix = f".jpg[Q={args.quality}]"
    image.dzsave(
        str(output_base),
        layout="dz",
        suffix=suffix,
        tile_size=args.tile_size,
        overlap=0,
        depth="onetile",
        container="fs",
    )

    elapsed = time.monotonic() - started
    metadata = {
        "schemaVersion": 1,
        "tool": "scripts/eval/stream_png_to_dz.py",
        "pid": os.getpid(),
        "input": {
            "width": input_width,
            "height": input_height,
            "bands": input_bands,
            "format": input_format,
            "interpretation": input_interpretation,
            "streamBytesRead": bytes_read,
        },
        "output": {
            "base": str(output_base),
            "dzi": str(output_base.with_suffix(".dzi")),
            "tilesDirectory": str(output_base.parent / f"{output_base.name}_files"),
            "tileSize": args.tile_size,
            "jpegQuality": args.quality,
        },
        "elapsedSeconds": elapsed,
        "libvipsVersion": pyvips.version(0, 1, 2),
        "cacheMaxMiB": args.max_cache_mib,
    }

    print(json.dumps(metadata, indent=2), file=sys.stderr)
    if args.metadata_out:
        metadata_path = Path(args.metadata_out).expanduser().resolve()
        metadata_path.parent.mkdir(parents=True, exist_ok=True)
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

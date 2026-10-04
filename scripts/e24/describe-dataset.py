#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--data-root", type=Path, default=Path("data"))
    p.add_argument("--image-id", required=True)
    p.add_argument("--source-note", required=True)
    p.add_argument("--permission-note", required=True)
    p.add_argument("--jpeg-quality-note", default="unknown-not-persisted-in-manifest")
    p.add_argument("--out", type=Path, required=True)
    return p.parse_args()


def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    a = parse_args()
    root = a.data_root.resolve()
    catalog_path = root / "catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    images = [i for i in catalog.get("images", []) if i.get("imageId") == a.image_id]
    if len(images) != 1:
        raise SystemExit(f"expected one catalog entry for {a.image_id}, found {len(images)}")
    image = images[0]
    version = image["imageVersion"]
    pyramid = root / "pyramids" / a.image_id / version
    manifest_path = pyramid / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    tiles_root = pyramid / "tiles"
    tiles = sorted(tiles_root.rglob("*.jpg"))
    if not tiles:
        raise SystemExit(f"no published JPEG tiles under {tiles_root}")
    levels = {}
    for tile in tiles:
        rel = tile.relative_to(tiles_root)
        level = rel.parts[0]
        entry = levels.setdefault(level, {"tiles": 0, "bytes": 0})
        entry["tiles"] += 1
        entry["bytes"] += tile.stat().st_size
    result = {
        "schemaVersion": 1,
        "imageId": a.image_id,
        "imageVersion": version,
        "width": image.get("width"),
        "height": image.get("height"),
        "tileSize": image.get("tileSize"),
        "maxLevel": image.get("maxLevel"),
        "manifestSha256": sha256(manifest_path),
        "catalogSha256": sha256(catalog_path),
        "manifest": manifest,
        "tileFormat": "JPEG",
        "tileCount": len(tiles),
        "pyramidBytes": sum(t.stat().st_size for t in tiles),
        "perLevel": levels,
        "jpegQualityNote": a.jpeg_quality_note,
        "sourceNote": a.source_note,
        "permissionNote": a.permission_note,
    }
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

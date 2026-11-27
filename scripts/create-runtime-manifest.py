#!/usr/bin/env python3
"""Create the public GitHub-release manifest for the ABI-specific yt-dlp helpers."""
import argparse
import hashlib
import json
import re
from pathlib import Path


def describe(path: Path, component: str, version: str, abi: str, tag: str) -> dict:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return {
        "component": component,
        "version": version,
        "abi": abi,
        "fileName": path.name,
        "url": f"https://github.com/treeklyz-afk/Idownld/releases/download/{tag}/{path.name}",
        "sizeBytes": size,
        "sha256": digest.hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="App version used for the release tag")
    parser.add_argument("--assets-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        raise SystemExit("Expected a numeric app version such as 2.8.6")
    tag = f"v{args.version}"
    entries = []
    for abi in ("arm64-v8a", "x86_64"):
        entries.append(describe(args.assets_dir / f"qjs-{abi}", "quickjs", "0.12.1", abi, tag))
        entries.append(describe(args.assets_dir / f"aria2c-{abi}", "aria2", "1.37.0", abi, tag))

    manifest = {
        "schemaVersion": 1,
        "runtimeVersion": "quickjs-0.12.1+aria2-1.37.0",
        "releaseTag": tag,
        "repository": "treeklyz-afk/Idownld",
        "assets": entries,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

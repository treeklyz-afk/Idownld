#!/usr/bin/env python3
"""Refresh upstream Android package version references; this does not publish APK binaries."""
import gzip
import json
import pathlib
import time
import urllib.request
import sys
import tempfile
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "packages" / "manifest.json"
INDEX_URL = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"


def main() -> int:
    with tempfile.NamedTemporaryFile(prefix="termux-packages-", delete=False) as tmp:
        index_path = pathlib.Path(tmp.name)
    try:
        # Fetch bounded ranges: some CI/network proxies close small full-index
        # responses early. Each range is length-checked before it is appended.
        def fetch_range(start: int, end: int, total: int) -> bytes:
            expected = end - start + 1
            for attempt in range(5):
                request = urllib.request.Request(
                    INDEX_URL,
                    headers={"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"},
                )
                try:
                    with urllib.request.urlopen(request, timeout=30) as response:
                        chunk = response.read()
                        content_range = response.headers.get("Content-Range", "")
                        wanted = f"bytes {start}-{end}/{total}"
                        if response.status != 206 or content_range != wanted or len(chunk) != expected:
                            raise RuntimeError(f"Invalid package-index range {content_range!r}, received {len(chunk)} bytes")
                        return chunk
                except Exception:
                    if attempt == 4:
                        raise
                    time.sleep(1 + attempt)
            raise RuntimeError("Package-index range retries exhausted")

        first_request = urllib.request.Request(
            INDEX_URL, headers={"Range": "bytes=0-0", "Accept-Encoding": "identity"}
        )
        with urllib.request.urlopen(first_request, timeout=30) as response:
            content_range = response.headers.get("Content-Range", "")
            if response.status != 206 or not content_range.startswith("bytes 0-0/"):
                raise RuntimeError(f"Official package index did not honor range request: {content_range!r}")
            total = int(content_range.rsplit("/", 1)[1])
            first_byte = response.read()
        if total < 500_000 or len(first_byte) != 1:
            raise RuntimeError("Termux package index is unexpectedly small or incomplete")
        chunk_size = 256 * 1024
        with index_path.open("wb") as output:
            output.write(first_byte)
            for start in range(1, total, chunk_size):
                end = min(start + chunk_size - 1, total - 1)
                output.write(fetch_range(start, end, total))
        if index_path.stat().st_size != total:
            raise RuntimeError("Downloaded package index size does not match its advertised size")
        versions = {}
        with gzip.open(index_path, "rt", encoding="utf-8", errors="replace") as index_file:
            index_text = index_file.read()
        for paragraph in index_text.split("\n\n"):
            fields = {}
            for line in paragraph.splitlines():
                if line and not line[0].isspace() and ": " in line:
                    key, value = line.split(": ", 1)
                    fields[key] = value
            if fields.get("Package") and fields.get("Version"):
                versions[fields["Package"]] = fields["Version"]

        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
        missing = [item["id"] for item in data["packages"] if item["id"] not in versions]
        if missing:
            raise RuntimeError("Packages missing from official aarch64 index: " + ", ".join(missing))
        changed = False
        for item in data["packages"]:
            version = versions[item["id"]]
            if item.get("version") != version:
                item["version"] = version
                changed = True
        if changed:
            data["updatedAtUtc"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
            data["catalogVersion"] = int(data.get("catalogVersion", 0)) + 1
            MANIFEST.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print("Updated upstream package version references (no binaries installed or published).")
        else:
            print("Upstream package versions are already current; no changes made.")
        return 0
    finally:
        index_path.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Catalog update failed safely: {exc}", file=sys.stderr)
        raise SystemExit(1)

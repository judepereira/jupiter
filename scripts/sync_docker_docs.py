#!/usr/bin/env python3
"""Keep documented release image references aligned with the Maven version."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

IMAGE = "judepereira/jupiter:"
VERSION_RE = re.compile(r"[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[1-9][0-9]*")
REFERENCE_RE = re.compile(re.escape(IMAGE) + r"(?:latest|[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[1-9][0-9]*)")
ANY_REFERENCE_RE = re.compile(re.escape(IMAGE) + r"[^\s`\\]+")
DOC_COUNTS = {
    Path("README.md"): 3,
    Path(".wiki/Getting-Started.md"): 1,
    Path(".wiki/Running-with-Docker.md"): 3,
}


def maven_version(root_dir: Path) -> str:
    root = ET.parse(root_dir / "pom.xml").getroot()
    versions = [
        (child.text or "").strip()
        for child in root
        if child.tag.rsplit("}", 1)[-1] == "version"
    ]
    if len(versions) != 1:
        raise ValueError(f"pom.xml must contain exactly one top-level project version, found {len(versions)}")
    version = versions[0]
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f"pom.xml has invalid top-level project version: {version!r}")
    return version


def validate_version(version: str) -> None:
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f"invalid release version: {version!r}")


def inspect(path: Path, expected: str | None, expected_count: int) -> tuple[str, str]:
    text = path.read_text()
    references = REFERENCE_RE.findall(text)
    all_references = ANY_REFERENCE_RE.findall(text)
    if len(all_references) != len(references):
        raise ValueError(f"{path}: contains invalid Docker image references")
    if len(references) != expected_count:
        raise ValueError(f"{path}: expected {expected_count} image references, found {len(references)}")
    if expected is not None:
        wanted = IMAGE + expected
        if any(reference != wanted for reference in references):
            raise ValueError(f"{path}: references do not all match {wanted}")
    return text, IMAGE + (expected or "")


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--update", action="store_true")
    parser.add_argument("--version", help="release version; defaults to the top-level pom.xml version")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="repository root (defaults to the current working directory)",
    )
    args = parser.parse_args()

    try:
        root = args.root.resolve()
        version = args.version or maven_version(root)
        paths = {root / path: count for path, count in DOC_COUNTS.items()}
        validate_version(version)
        if args.check:
            for path in paths:
                inspect(path, version, paths[path])
            return 0

        for path in paths:
            text, _ = inspect(path, None, paths[path])
            updated = REFERENCE_RE.sub(IMAGE + version, text)
            if updated != text:
                path.write_text(updated)
    except (OSError, ET.ParseError, StopIteration, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

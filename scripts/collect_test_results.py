#!/usr/bin/env python3
"""Normalize Maven Surefire XML into the workflow's test-results artifact."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


def collect(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    failures = []
    errors = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            errors.append(f"{path.name}: {exc}")
            continue
        for case in root.iter("testcase"):
            children = list(case)
            if any(item.tag == "skipped" for item in children):
                continue
            results = [item for item in children if item.tag in ("failure", "error")]
            if not results:
                continue
            messages = []
            for result in results:
                message = result.get("message")
                if not message:
                    message = next((line.strip() for line in (result.text or "").splitlines() if line.strip()), "")
                if message and message not in messages:
                    messages.append(message)
            failures.append({
                "class": case.get("classname", ""),
                "name": case.get("name", ""),
                "message": "; ".join(messages)[:1000],
            })
    return files, failures, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-sha", required=True)
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--exit-status", type=int, required=True)
    args = parser.parse_args()
    reports, failures, errors = collect(args.reports)
    available = bool(reports)
    if not available:
        errors.append("No Surefire XML reports found")
    data = {
        "schema_version": 1,
        "build_sha": args.build_sha,
        "head_sha": args.head_sha,
        "build_exit_status": args.exit_status,
        "reports_available": available,
        "report_errors": [error[:1000] for error in errors],
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return 1 if args.exit_status != 0 or errors or failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

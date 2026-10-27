#!/usr/bin/env python3
"""Compare actual Kotlin and Swift preset snapshots, optionally accepting matched fixtures.

Native test exporters remove UUIDs and map commands to their shared Android key codes.
They run the real create functions; this script never reimplements layout generation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = ROOT / "shared/canvas-template-fixtures.json"


def read(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        result = json.load(stream)
    if not isinstance(result, dict):
        raise ValueError(f"{path}: expected JSON object")
    return result


def compare(label: str, left: dict, right: dict) -> None:
    missing, extra = left.keys() - right.keys(), right.keys() - left.keys()
    if missing or extra:
        raise ValueError(f"{label}: missing {sorted(missing)}, extra {sorted(extra)}")
    differences = [key for key in sorted(left) if left[key] != right[key]]
    if differences:
        key = differences[0]
        raise ValueError(
            f"{label}: {len(differences)} mismatches; first {key}\n"
            f"  expected: {left[key]}\n  actual:   {right[key]}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", type=Path, required=True, help="Kotlin test runtime snapshot")
    parser.add_argument("--ios", type=Path, required=True, help="Swift test runtime snapshot")
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument(
        "--update", action="store_true", help="accept geometry only after both runtimes agree"
    )
    args = parser.parse_args()
    try:
        fixture = read(args.fixture)
        if fixture["version"] != 1:
            raise ValueError("unsupported fixture version")
        android, ios = read(args.android)["cases"], read(args.ios)["cases"]
        expected_keys = {
            f"{profile['id']}/apps{count}/{hand}/{template['ios']}"
            for profile in fixture["profiles"]
            for count in range(3)
            for hand in ("right", "left")
            for template in fixture["templates"]
        }
        for label, cases in (("Android", android), ("iOS", ios)):
            if not isinstance(cases, dict) or set(cases) != expected_keys:
                raise ValueError(f"{label}: incomplete runtime matrix; expected {len(expected_keys)} cases")
            if any(not isinstance(items, list) or not items or not all(isinstance(i, str) for i in items) for items in cases.values()):
                raise ValueError(f"{label}: invalid normalized geometry")
        compare("Android vs iOS", android, ios)
        if args.update:
            fixture["cases"] = dict(sorted(android.items()))
            args.fixture.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        else:
            compare("Shared fixture vs Android", fixture["cases"], android)
        print(f"PASS: {len(android)} runtime layouts match across Kotlin, Swift and shared fixtures.")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

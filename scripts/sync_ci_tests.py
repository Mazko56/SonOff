"""Reconcile test files from multiple ZIP releases before running CI.

Expand-Archive overwrites existing files but DOES NOT delete old files. If a
previous release left legacy test_*.py modules in the tracked repository,
unittest discovery picks them up. Preserve these files in a separate location
and execute all tests belonging to this release. No app source is changed.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "scripts" / "test_inventory.json"


def expected_tests(manifest: Path = MANIFEST) -> set[str]:
    obj = json.loads(manifest.read_text(encoding="utf-8"))
    names = obj.get("tests", [])
    if not isinstance(names, list) or len(names) < 10 or len(set(names)) != len(names):
        raise ValueError("Invalid or incomplete test inventory")
    for name in names:
        if not isinstance(name, str) or not name.startswith("test_") or not name.endswith(".py") or "/" in name or "\\" in name:
            raise ValueError("Invalid test filename in inventory: " + repr(name))
    return set(names)


def reconcile(tests_dir: Path, expected: set[str], quarantine_dir: Path | None) -> list[str]:
    missing = sorted(name for name in expected if not (tests_dir / name).is_file())
    if missing:
        raise ValueError("Release test files missing: " + ", ".join(missing))
    obsolete = sorted(p for p in tests_dir.glob("test_*.py") if p.is_file() and p.name not in expected)
    if obsolete and quarantine_dir is None:
        raise ValueError("Tests from older ZIPs remain: " + ", ".join(p.name for p in obsolete)
                         + ". Run scripts/sync_tests_windows.ps1 or --quarantine.")
    if obsolete:
        quarantine_dir.mkdir(parents=True, exist_ok=True)
        for path in obsolete:
            target = quarantine_dir / path.name
            if target.exists():
                raise FileExistsError("Refusing to overwrite retired test: " + str(target))
            shutil.move(str(path), str(target))
            print(f"[RELEASE-CLEANUP] Archived retired test: {path.name} -> {target}", flush=True)
    else:
        print("[RELEASE-CLEANUP] No retired tests; release inventory matches", flush=True)
    return [p.name for p in obsolete]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--quarantine", type=Path, help="Directory outside tests/ where old tests will be preserved")
    args = p.parse_args(argv)
    expected = expected_tests()
    print(f"[RELEASE-CLEANUP] Expected {len(expected)} test files", flush=True)
    reconcile(ROOT / "tests", expected, args.quarantine)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"[RELEASE-CLEANUP] ERROR: {exc}", file=sys.stderr)
        sys.exit(1)

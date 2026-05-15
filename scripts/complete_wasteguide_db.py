#!/usr/bin/env python3
"""Run the remaining Wasteguide DB build steps sequentially to avoid SQLite locks."""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
LOG_DIR = ROOT / "data"


def run_step(name: str, args: list[str]) -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"complete-{name}.log"
    print(f"[{datetime.now().isoformat(timespec='seconds')}] START {name}", flush=True)
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"\n\n[{datetime.now().isoformat(timespec='seconds')}] START {name}\n")
        proc = subprocess.run(
            [sys.executable, *args],
            cwd=ROOT,
            stdout=log,
            stderr=log,
            text=True,
            check=False,
        )
        log.write(f"[{datetime.now().isoformat(timespec='seconds')}] END {name} code={proc.returncode}\n")
    if proc.returncode != 0:
        raise SystemExit(f"{name} failed with code {proc.returncode}; see {log_path}")
    print(f"[{datetime.now().isoformat(timespec='seconds')}] END {name}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Complete Wasteguide DB build serially")
    parser.add_argument("--delay-min", default="4")
    parser.add_argument("--delay-max", default="8")
    parser.add_argument("--skip-crawl", action="store_true", help="Only rebuild app-ready tables")
    args = parser.parse_args()

    if not args.skip_crawl:
        run_step(
            "region-law",
            [
                str(ROOT / "scripts" / "wasteguide_region_law_crawler.py"),
                "--delay-min",
                args.delay_min,
                "--delay-max",
                args.delay_max,
            ],
        )
        run_step(
            "dictionary",
            [
                str(ROOT / "scripts" / "wasteguide_crawler.py"),
                "--delay-min",
                args.delay_min,
                "--delay-max",
                args.delay_max,
                "--resume",
            ],
        )

    run_step("finalize-app-db", [str(ROOT / "scripts" / "finalize_app_db.py")])
    print("All DB build steps completed.", flush=True)


if __name__ == "__main__":
    main()

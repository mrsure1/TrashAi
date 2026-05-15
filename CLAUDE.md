# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TrashAI is a mobile recycling-guide app concept (Android-first). This repository currently contains:

- **Product/design docs** (`PRD.md`, `problem.md`, `docs/`) — Korean-language source of truth for product behaviour. Read these before changing data flows or copy.
- **Data pipeline** (`scripts/`) — Python crawlers + finalizer that build the on-device SQLite (`data/wasteguide_dictionary.sqlite3`) shipped to the app.
- **UI prototype** (`ui/`) — Static HTML/CSS mockup of the camera + bottom-sheet screen. No JS framework, no build step.

There is no mobile app source in this repo yet; the architecture in `docs/architecture.md` describes the intended client (Presentation / Domain / Data layers, on-device YOLO, Gemini fallback).

## Common Commands

Install Python deps:
```
pip install -r requirements.txt
```

Run the full DB build (crawl → finalize), serially to avoid SQLite locks:
```
python scripts/complete_wasteguide_db.py
```

Skip the (slow, polite) crawl and only rebuild the app-ready tables from the existing SQLite:
```
python scripts/complete_wasteguide_db.py --skip-crawl
```

Run individual steps:
```
python scripts/wasteguide_region_law_crawler.py [--delay-min 4 --delay-max 8] [--only-sido-code 41] [--limit N] [--dry-run]
python scripts/wasteguide_crawler.py            [--delay-min 4 --delay-max 8] [--limit N] [--resume] [--dry-run]
python scripts/finalize_app_db.py               [--db data/wasteguide_dictionary.sqlite3]
```

Logs from `complete_wasteguide_db.py` are appended to `data/complete-<step>.log`. There is no test suite, linter, or build for the UI prototype — open `ui/index.html` directly in a browser.

## Data Pipeline Architecture

All four scripts read/write a single SQLite file at `data/wasteguide_dictionary.sqlite3`. The pipeline has two distinct table tiers — keep this separation when adding columns or new sources:

1. **Raw crawled tables** (source of truth, can be re-derived):
   - `dictionary_item` — items from wasteguide.or.kr 품목사전 (`wasteguide_crawler.py`).
   - `region` / `region_ordinance` — local-government ordinance text (`wasteguide_region_law_crawler.py`).
2. **App-ready tables** (what the mobile client queries — built by `finalize_app_db.py`):
   - `app_item_rule` — per-item card copy (summary, disposal steps).
   - `app_region_ordinance` — per-region ordinance card copy.
   - `app_search_keyword` — keyword index used by the Clarification flow.

The mobile client must compose card text **only** from the `app_*` tables. LLM (Gemini) outputs are never rendered directly; they are matched back to an `item_id` / `ni_idx` in these tables before display. See `docs/architecture.md` §4 for the Clarification flow contract.

## Crawler Etiquette

The crawlers target a public Korean government site (wasteguide.or.kr). Respect what is already encoded:

- Default `--delay-min 4 --delay-max 8` (seconds between requests). Do not lower these without reason.
- The `User-Agent` is set to `TrashAi-InternalRAG/0.1 (+educational-local; polite-crawl)` — keep it identifiable.
- robots.txt disallows `/front/search` and `/front/support`; these paths are intentionally not crawled. Don't add them.
- `wasteguide_crawler.py` supports `--resume` (idempotent). The orchestrator `complete_wasteguide_db.py` always passes `--resume` so re-runs continue rather than re-fetch.

## UI Prototype

`ui/` is a hand-written HTML/CSS reference for the visual design tokens listed in `PRD.md` §4.2 (forest-green `#2D5A27`, glassmorphism, neon bounding box). It is not the production app and has no JS logic — treat it as a spec artifact. When the real client is built, the layout/states (`Scanning`, `Locked`, `Uncertain`, `Clarifying`, `GuidanceReady`) come from `docs/architecture.md` §3, not from this HTML.

## Language Note

Product docs, table copy, and most comments are in Korean. Preserve Korean strings verbatim when editing — they are user-facing copy sourced from official municipal text.

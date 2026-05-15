#!/usr/bin/env python3
"""Build app-friendly tables from the raw Wasteguide crawl tables."""

from __future__ import annotations

import argparse
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


APP_SCHEMA = """
CREATE TABLE IF NOT EXISTS app_item_rule (
  item_id TEXT PRIMARY KEY,
  item_name TEXT NOT NULL,
  categories TEXT,
  primary_category TEXT,
  discharge_method TEXT,
  feature_text TEXT,
  caution_text TEXT,
  app_summary TEXT,
  source_name TEXT NOT NULL,
  source_url TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_region_ordinance (
  region_id TEXT PRIMARY KEY,
  region_code TEXT,
  sido_name TEXT,
  sigungu_name TEXT,
  ordinance_title TEXT,
  ordinance_text TEXT,
  app_summary TEXT,
  source_name TEXT NOT NULL,
  source_url TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_search_keyword (
  keyword TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  weight INTEGER NOT NULL DEFAULT 100,
  PRIMARY KEY(keyword, target_type, target_id)
);

CREATE INDEX IF NOT EXISTS idx_app_item_name ON app_item_rule(item_name);
CREATE INDEX IF NOT EXISTS idx_app_region_name ON app_region_ordinance(sido_name, sigungu_name);
CREATE INDEX IF NOT EXISTS idx_app_search_keyword ON app_search_keyword(keyword);
"""


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def normalize_text(text: str | None) -> str:
    if not text:
        return ""
    text = re.sub(r"\r\n?", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def normalize_keyword(text: str) -> str:
    return re.sub(r"\s+", "", text.strip().lower())


def first_nonempty_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip()
        if line:
            return line
    return ""


def extract_between(text: str, start_label: str, stop_labels: tuple[str, ...]) -> str:
    if not text:
        return ""
    start = text.find(start_label)
    if start < 0:
        return ""
    start += len(start_label)
    end_candidates = [text.find(label, start) for label in stop_labels if text.find(label, start) >= 0]
    end = min(end_candidates) if end_candidates else len(text)
    return normalize_text(text[start:end])


def primary_category(categories: str | None) -> str:
    if not categories:
        return ""
    for part in re.split(r"[,/|]", categories):
        part = part.strip()
        if part:
            return part
    return categories.strip()


def make_item_summary(name: str, category: str, discharge: str, caution: str) -> str:
    lines: list[str] = []
    if category:
        lines.append(f"분류: {category}")
    if discharge:
        lines.append(discharge.splitlines()[0].strip())
    if caution:
        lines.append(f"주의: {caution.splitlines()[0].strip()}")
    return "\n".join(lines) or f"{name}의 배출 방법은 원문을 확인하세요."


def build_app_tables(db_path: Path) -> None:
    conn = sqlite3.connect(db_path, timeout=300)
    conn.execute("PRAGMA busy_timeout = 300000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(APP_SCHEMA)

    now = utc_now_iso()
    conn.execute("DELETE FROM app_item_rule")
    conn.execute("DELETE FROM app_region_ordinance")
    conn.execute("DELETE FROM app_search_keyword")

    item_rows = conn.execute(
        """SELECT ni_idx, title, categories, detail_url, disposal_text
           FROM dictionary_item
           WHERE title IS NOT NULL AND trim(title) != ''"""
    ).fetchall()
    for item_id, name, categories, source_url, raw_text in item_rows:
        raw = normalize_text(raw_text)
        discharge = extract_between(raw, "배출방법", ("특징", "유의사항"))
        feature = extract_between(raw, "특징", ("유의사항",))
        caution = extract_between(raw, "유의사항", ())
        pcat = primary_category(categories)
        conn.execute(
            """INSERT INTO app_item_rule
            (item_id, item_name, categories, primary_category, discharge_method, feature_text,
             caution_text, app_summary, source_name, source_url, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (
                str(item_id),
                name,
                categories,
                pcat,
                discharge or raw,
                feature,
                caution,
                make_item_summary(name, pcat, discharge or raw, caution),
                "생활폐기물 분리배출 누리집",
                source_url,
                now,
            ),
        )
        keyword = normalize_keyword(name)
        if keyword:
            conn.execute(
                """INSERT OR IGNORE INTO app_search_keyword
                (keyword, target_type, target_id, weight) VALUES (?,?,?,?)""",
                (keyword, "item", str(item_id), 100),
            )

    region_rows = conn.execute(
        """SELECT region_id, region_code, sido_name, sigungu_name, ordinance_text, source_url
           FROM region_ordinance
           WHERE sigungu_name IS NOT NULL AND trim(sigungu_name) != ''"""
    ).fetchall()
    for region_id, region_code, sido, sigungu, raw_text, source_url in region_rows:
        raw = normalize_text(raw_text)
        title = first_nonempty_line(raw) or "폐기물 관리조례"
        summary = "\n".join(raw.splitlines()[:8]).strip()
        conn.execute(
            """INSERT INTO app_region_ordinance
            (region_id, region_code, sido_name, sigungu_name, ordinance_title, ordinance_text,
             app_summary, source_name, source_url, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (
                str(region_id),
                region_code,
                sido,
                sigungu,
                title,
                raw,
                summary,
                "생활폐기물 분리배출 누리집",
                source_url,
                now,
            ),
        )
        for keyword, weight in (
            (normalize_keyword(str(sigungu)), 100),
            (normalize_keyword(f"{sido}{sigungu}"), 90),
        ):
            if keyword:
                conn.execute(
                    """INSERT OR IGNORE INTO app_search_keyword
                    (keyword, target_type, target_id, weight) VALUES (?,?,?,?)""",
                    (keyword, "region", str(region_id), weight),
                )

    for k, v in (
        ("app_db_finalized_at", now),
        ("app_item_rule_count", str(conn.execute("SELECT COUNT(*) FROM app_item_rule").fetchone()[0])),
        (
            "app_region_ordinance_count",
            str(conn.execute("SELECT COUNT(*) FROM app_region_ordinance").fetchone()[0]),
        ),
    ):
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, v))
    conn.commit()
    conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build app-ready Wasteguide tables")
    parser.add_argument(
        "--db",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "wasteguide_dictionary.sqlite3",
    )
    args = parser.parse_args()
    build_app_tables(args.db)
    print(f"app-ready tables built: {args.db}")


if __name__ == "__main__":
    main()

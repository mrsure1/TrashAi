#!/usr/bin/env python3
"""
wasteguide.or.kr의 robots 허용 경로만 사용해 지역별 분리배출 조례 HTML을 SQLite에 저장한다.

사용 경로:
- /front/bbsList.do?bbsId=BBS_0007
- /front/ajaxActNrRegionList.do
- /front/region/bbsListAboutLawHtml.do

금지 경로(/front/search, /front/support)는 호출하지 않는다.
"""

from __future__ import annotations

import argparse
import logging
import random
import re
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE = "https://wasteguide.or.kr"
LAW_PAGE = f"{BASE}/front/bbsList.do?bbsId=BBS_0007"
REGION_API = f"{BASE}/front/ajaxActNrRegionList.do"
LAW_API = f"{BASE}/front/region/bbsListAboutLawHtml.do"
USER_AGENT = "TrashAi-InternalRAG/0.1 (+educational-local; polite-region-law-crawl)"

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS region (
  region_id TEXT PRIMARY KEY,
  parent_region_id TEXT,
  region_code TEXT NOT NULL,
  region_name TEXT NOT NULL,
  region_level TEXT NOT NULL,
  fetched_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS region_ordinance (
  region_id TEXT PRIMARY KEY,
  parent_region_id TEXT,
  sido_name TEXT,
  sigungu_name TEXT,
  region_code TEXT,
  source_url TEXT NOT NULL,
  ordinance_html TEXT,
  ordinance_text TEXT,
  fetched_at TEXT NOT NULL,
  FOREIGN KEY(region_id) REFERENCES region(region_id)
);

CREATE INDEX IF NOT EXISTS idx_region_parent ON region(parent_region_id);
CREATE INDEX IF NOT EXISTS idx_region_ordinance_name ON region_ordinance(sido_name, sigungu_name);
"""


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def polite_sleep(delay_min: float, delay_max: float) -> None:
    time.sleep(random.uniform(delay_min, delay_max))


def extract_csrf(html: str) -> str:
    for pattern in (
        r'id="hdCsrfTk"\s+value="([^"]+)"',
        r'name="_csrf"\s+value="([^"]+)"',
    ):
        match = re.search(pattern, html)
        if match:
            return match.group(1)
    raise RuntimeError("CSRF token not found")


def clean_text(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    return soup.get_text("\n", strip=True)


def ensure_db(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=300)
    conn.execute("PRAGMA busy_timeout = 300000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)
    conn.commit()
    return conn


def fetch_regions(session: requests.Session, csrf: str, search_cnd: str, keyword: str = "") -> list[dict]:
    response = session.post(
        REGION_API,
        data={
            "sapStatus": "Code",
            "searchCnd": search_cnd,
            "searchKeyword": keyword,
            "_csrf": csrf,
        },
        headers={"Referer": LAW_PAGE, "X-Requested-With": "XMLHttpRequest"},
        timeout=45,
    )
    response.raise_for_status()
    data = response.json()
    return data.get("ajaxListResult", [])


def fetch_law_html(session: requests.Session, csrf: str, sido_id: str, sigungu_id: str) -> str:
    response = session.post(
        LAW_API,
        data={
            "bbsId": "BBS_0007",
            "searchOp9": sido_id,
            "searchOp10": sigungu_id,
            "_csrf": csrf,
        },
        headers={"Referer": LAW_PAGE, "X-Requested-With": "XMLHttpRequest"},
        timeout=60,
    )
    response.raise_for_status()
    return response.text


def upsert_region(
    conn: sqlite3.Connection,
    region_id: str,
    parent_region_id: str | None,
    region_code: str,
    region_name: str,
    region_level: str,
) -> None:
    conn.execute(
        """INSERT INTO region
        (region_id, parent_region_id, region_code, region_name, region_level, fetched_at)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(region_id) DO UPDATE SET
          parent_region_id=excluded.parent_region_id,
          region_code=excluded.region_code,
          region_name=excluded.region_name,
          region_level=excluded.region_level,
          fetched_at=excluded.fetched_at
        """,
        (region_id, parent_region_id, region_code, region_name, region_level, utc_now_iso()),
    )


def run(
    db_path: Path,
    delay_min: float,
    delay_max: float,
    limit: int | None,
    only_sido_code: str | None,
    dry_run: bool,
) -> None:
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept-Language": "ko-KR,ko;q=0.9",
        }
    )

    main = session.get(LAW_PAGE, timeout=45)
    main.raise_for_status()
    csrf = extract_csrf(main.text)

    conn = ensure_db(db_path)
    for k, v in (
        ("region_law_source", BASE),
        ("region_law_allowed_paths", "/front/bbsList.do,/front/ajaxActNrRegionList.do,/front/region/bbsListAboutLawHtml.do"),
        ("region_law_disallowed_paths_not_used", "/front/search,/front/support"),
        ("region_law_started_at", utc_now_iso()),
    ):
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, v))
    conn.commit()

    sido_rows = fetch_regions(session, csrf, "d1")
    if only_sido_code:
        sido_rows = [row for row in sido_rows if str(row.get("nrCode")) == only_sido_code]
    logging.info("sido count: %s", len(sido_rows))

    targets: list[tuple[dict, dict]] = []
    for sido in sido_rows:
        sido_id = str(sido["nrId"])
        sido_code = str(sido["nrCode"])
        sido_name = str(sido["nrName"])
        upsert_region(conn, sido_id, None, sido_code, sido_name, "sido")
        polite_sleep(delay_min, delay_max)
        sigungu_rows = fetch_regions(session, csrf, "d2", sido_code)
        logging.info("%s(%s): sigungu count %s", sido_name, sido_code, len(sigungu_rows))
        for sigungu in sigungu_rows:
            upsert_region(
                conn,
                str(sigungu["nrId"]),
                sido_id,
                str(sigungu["nrCode"]),
                str(sigungu["nrName"]),
                "sigungu",
            )
            targets.append((sido, sigungu))
    conn.commit()

    if limit is not None:
        targets = targets[:limit]
    logging.info("law targets: %s", len(targets))

    if dry_run:
        conn.close()
        logging.info("dry-run done")
        return

    fetched = 0
    for sido, sigungu in targets:
        sigungu_id = str(sigungu["nrId"])
        exists = conn.execute(
            "SELECT 1 FROM region_ordinance WHERE region_id=? AND ordinance_html IS NOT NULL AND length(ordinance_html)>100",
            (sigungu_id,),
        ).fetchone()
        if exists:
            logging.debug("skip existing %s", sigungu_id)
            continue

        polite_sleep(delay_min, delay_max)
        html = fetch_law_html(session, csrf, str(sido["nrId"]), sigungu_id)
        text = clean_text(html)
        conn.execute(
            """INSERT INTO region_ordinance
            (region_id, parent_region_id, sido_name, sigungu_name, region_code, source_url,
             ordinance_html, ordinance_text, fetched_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT(region_id) DO UPDATE SET
              parent_region_id=excluded.parent_region_id,
              sido_name=excluded.sido_name,
              sigungu_name=excluded.sigungu_name,
              region_code=excluded.region_code,
              source_url=excluded.source_url,
              ordinance_html=excluded.ordinance_html,
              ordinance_text=excluded.ordinance_text,
              fetched_at=excluded.fetched_at
            """,
            (
                sigungu_id,
                str(sido["nrId"]),
                str(sido["nrName"]),
                str(sigungu["nrName"]),
                str(sigungu["nrCode"]),
                LAW_PAGE,
                html,
                text,
                utc_now_iso(),
            ),
        )
        conn.commit()
        fetched += 1
        if fetched % 20 == 0:
            logging.info("law progress %s / %s", fetched, len(targets))

    conn.execute(
        "INSERT OR REPLACE INTO meta(key,value) VALUES ('region_law_completed_at', ?)",
        (utc_now_iso(),),
    )
    conn.commit()
    conn.close()
    logging.info("done. fetched=%s db=%s", fetched, db_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="wasteguide.or.kr 지역별 조례 크롤러")
    parser.add_argument(
        "--db",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "wasteguide_dictionary.sqlite3",
        help="SQLite 출력 경로",
    )
    parser.add_argument("--delay-min", type=float, default=4.0)
    parser.add_argument("--delay-max", type=float, default=8.0)
    parser.add_argument("--limit", type=int, default=None, help="조례 상세 수집 지역 수 제한")
    parser.add_argument("--only-sido-code", default=None, help="예: 경기도=41, 서울=11")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("-v", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.v else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    if args.delay_min > args.delay_max:
        raise SystemExit("delay-min must be <= delay-max")
    run(args.db, args.delay_min, args.delay_max, args.limit, args.only_sido_code, args.dry_run)


if __name__ == "__main__":
    main()

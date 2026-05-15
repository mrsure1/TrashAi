#!/usr/bin/env python3
"""
분리배출 누리집(wasteguide.or.kr) 품목사전 허용 구간만 예의 있게 수집해 SQLite를 만든다.

robots.txt 기준 수집 금지 경로는 호출하지 않는다.
- Disallow: /front/search
- Disallow: /front/support  (지역별 배너 페이지 등 포함 — 이 스크립트는 크롤하지 않음)

품목사전: /front/dischargeMethod/dictionary.do 및 ajaxDictionaryHtml.do, dictionaryView.do
"""

from __future__ import annotations

import argparse
import logging
import random
import re
import sqlite3
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup

BASE = "https://wasteguide.or.kr"
USER_AGENT = "TrashAi-InternalRAG/0.1 (+educational-local; polite-crawl)"
DICT_START = f"{BASE}/front/dischargeMethod/dictionary.do"
AJAX_LIST = f"{BASE}/front/dischargeMethod/ajaxDictionaryHtml.do"
VIEW = f"{BASE}/front/dischargeMethod/dictionaryView.do"

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS dictionary_item (
  ni_idx TEXT PRIMARY KEY,
  title TEXT,
  categories TEXT,
  list_page INTEGER,
  detail_url TEXT NOT NULL,
  detail_html TEXT,
  disposal_text TEXT,
  fetched_at TEXT NOT NULL,
  UNIQUE(ni_idx)
);
CREATE INDEX IF NOT EXISTS idx_dict_title ON dictionary_item(title);
"""


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def polite_sleep(delay_min: float, delay_max: float) -> None:
    time.sleep(random.uniform(delay_min, delay_max))


def extract_csrf(html: str) -> str | None:
    m = re.search(r'name="_csrf"\s+value="([^"]+)"', html)
    return m.group(1) if m else None


def max_list_page(html: str) -> int:
    nums = [int(x) for x in re.findall(r"fnListArticle\(\s*(\d+)\s*\)", html)]
    return max(nums) if nums else 1


def parse_dictionary_list(html: str, page_no: int) -> list[tuple[str, str, str]]:
    soup = BeautifulSoup(html, "html.parser")
    out: list[tuple[str, str, str]] = []
    for a in soup.select("a[onclick*='fnViewArticle']"):
        oc = a.get("onclick") or ""
        mid = re.search(r"fnViewArticle\(['\"]?(\d+)['\"]?\)", oc)
        if not mid:
            continue
        ni = mid.group(1)
        tit_el = a.select_one(".tit")
        title = tit_el.get_text(strip=True) if tit_el else ""
        cats_el = a.select_one(".txt > p")
        cats = cats_el.get_text(" ", strip=True) if cats_el else ""
        out.append((ni, title, cats))
    logging.info("list page %s: %s rows", page_no, len(out))
    return out


def extract_detail_fields(html: str) -> tuple[str, str]:
    soup = BeautifulSoup(html, "html.parser")
    main = soup.select_one(".dispose_detail.dictionary_info")
    if main:
        disposal = ""
        sec = main.select_one(".how")
        if sec:
            disposal = sec.get_text("\n", strip=True)
        return str(main), disposal
    body = soup.select_one("#mainContent") or soup.body
    if body:
        return str(body), body.get_text("\n", strip=True)
    return html, ""


def ensure_db(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=300)
    conn.execute("PRAGMA busy_timeout = 300000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)
    conn.commit()
    return conn


def persist_list_stubs(conn: sqlite3.Connection, rows: dict[str, tuple[str, str, int]]) -> None:
    now = utc_now_iso()
    for ni, (tit, cats, lp) in rows.items():
        url = f"{VIEW}?niIdx={ni}"
        conn.execute(
            """INSERT INTO dictionary_item
            (ni_idx, title, categories, list_page, detail_url, detail_html, disposal_text, fetched_at)
            VALUES (?,?,?,?,?,NULL,NULL,?)
            ON CONFLICT(ni_idx) DO UPDATE SET
              title=COALESCE(excluded.title, dictionary_item.title),
              categories=COALESCE(excluded.categories, dictionary_item.categories),
              list_page=excluded.list_page,
              detail_url=excluded.detail_url,
              fetched_at=excluded.fetched_at
            """,
            (ni, tit or None, cats or None, lp, url, now),
        )
    conn.commit()


def fetch_all_list_rows(session: requests.Session, delay_min: float, delay_max: float, list_pages_max: int | None):
    r0 = session.get(DICT_START, timeout=45)
    r0.raise_for_status()
    csrf = extract_csrf(r0.text)
    if not csrf:
        raise RuntimeError("CSRF 추출 실패")

    ajax1 = session.post(
        AJAX_LIST,
        data={
            "searchCnd": "1",
            "searchWrd": "",
            "searchOp1": "",
            "searchOp2": "",
            "pageIndex": "1",
            "_csrf": csrf,
        },
        timeout=60,
        headers={"X-Requested-With": "XMLHttpRequest", "Referer": DICT_START},
    )
    ajax1.raise_for_status()
    polite_sleep(delay_min, delay_max)

    total_pages = max_list_page(ajax1.text)
    if list_pages_max is not None:
        total_pages = min(total_pages, list_pages_max)
    logging.info(
        "total list pages detected: %s (crawl up to %s)",
        max_list_page(ajax1.text),
        total_pages,
    )

    rows: dict[str, tuple[str, str, int]] = {}
    for p in parse_dictionary_list(ajax1.text, 1):
        rows[p[0]] = (p[1], p[2], 1)

    for page in range(2, total_pages + 1):
        polite_sleep(delay_min, delay_max)
        r = session.post(
            AJAX_LIST,
            data={
                "searchCnd": "1",
                "searchWrd": "",
                "searchOp1": "",
                "searchOp2": "",
                "pageIndex": str(page),
                "_csrf": csrf,
            },
            timeout=60,
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": DICT_START},
        )
        if r.status_code != 200:
            logging.warning("list page %s http %s, refresh csrf 시도", page, r.status_code)
            polite_sleep(delay_min * 2, delay_max * 2)
            r0b = session.get(DICT_START, timeout=45)
            csrf = extract_csrf(r0b.text) or csrf
            continue
        for ni, tit, cats in parse_dictionary_list(r.text, page):
            rows.setdefault(ni, (tit, cats, page))
    return rows


def run(
    db_path: Path,
    delay_min: float,
    delay_max: float,
    limit_items: int | None,
    list_pages_max: int | None,
    dry_run: bool,
    resume: bool,
    min_stubs_resume: int,
) -> None:
    session = requests.Session()
    session.headers.update(
        {"User-Agent": USER_AGENT, "Accept-Language": "ko-KR,ko;q=0.9", "Referer": DICT_START}
    )

    conn = ensure_db(db_path)
    rows: dict[str, tuple[str, str, int]] = {}

    if resume and db_path.exists():
        n_stub = conn.execute(
            """SELECT COUNT(*) FROM dictionary_item
               WHERE COALESCE(trim(title),'')!='' AND (detail_html IS NULL OR length(detail_html)<100)"""
        ).fetchone()[0]
        n_have_detail = conn.execute(
            """SELECT COUNT(*) FROM dictionary_item WHERE detail_html IS NOT NULL AND length(detail_html)>100"""
        ).fetchone()[0]
        if n_stub >= min_stubs_resume or n_have_detail >= min_stubs_resume:
            logging.info("--resume: DB에서 목록 생략 (stub=%s, 상세있음=%s)", n_stub, n_have_detail)
            cur = conn.execute(
                "SELECT ni_idx, COALESCE(title,''), COALESCE(categories,''), COALESCE(list_page,0) FROM dictionary_item"
            )
            rows = {row[0]: (row[1], row[2], row[3]) for row in cur.fetchall()}
        else:
            logging.info("--resume 이지만 stub 부족(%s<%s) — 목록 재수집", n_stub, min_stubs_resume)

    if not rows:
        rows = fetch_all_list_rows(session, delay_min, delay_max, list_pages_max)
        if not dry_run:
            started = utc_now_iso()
            for k, v in (
                ("source", BASE),
                ("scraped_kind", "dictionary_only_no_regional_support_path"),
                ("disclaimer_robots", "no /front/search or /front/support"),
                ("scraped_at_utc_list_done", started),
            ):
                conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, v))
            persist_list_stubs(conn, rows)

    ids_sorted = sorted(rows.keys(), key=lambda x: int(x))
    if limit_items is not None:
        ids_sorted = ids_sorted[:limit_items]

    logging.info("unique dictionary ids: %s (will fetch detail: %s)", len(rows), len(ids_sorted))

    if dry_run:
        conn.close()
        logging.info("dry-run: 디테일 생략")
        return

    for k, v in (
        ("scraped_at_utc_detail_phase", utc_now_iso()),
    ):
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)", (k, v))
    conn.commit()

    fetched = 0
    for ni in ids_sorted:
        tit, cats, lp = rows[ni]
        url = f"{VIEW}?niIdx={ni}"

        exists = conn.execute(
            "SELECT 1 FROM dictionary_item WHERE ni_idx=? AND detail_html IS NOT NULL AND length(detail_html)>100",
            (ni,),
        ).fetchone()
        if exists:
            logging.debug("skip existing %s", ni)
            continue

        polite_sleep(delay_min, delay_max)
        dr = session.get(url, timeout=60, headers={"Referer": DICT_START})
        if dr.status_code != 200:
            logging.warning("detail %s status %s", ni, dr.status_code)
            continue
        section_html, disposal = extract_detail_fields(dr.text)

        conn.execute(
            """INSERT INTO dictionary_item
            (ni_idx, title, categories, list_page, detail_url, detail_html, disposal_text, fetched_at)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(ni_idx) DO UPDATE SET
              title=excluded.title,
              categories=excluded.categories,
              list_page=excluded.list_page,
              detail_url=excluded.detail_url,
              detail_html=excluded.detail_html,
              disposal_text=excluded.disposal_text,
              fetched_at=excluded.fetched_at
            """,
            (
                ni,
                tit or None,
                cats or None,
                lp,
                url,
                section_html,
                disposal or None,
                utc_now_iso(),
            ),
        )
        conn.commit()
        fetched += 1
        if fetched % 20 == 0:
            logging.info("detail progress %s / %s", fetched, len(ids_sorted))

    conn.execute(
        "INSERT OR REPLACE INTO meta(key,value) VALUES ('scraped_at_utc_complete', ?)",
        (utc_now_iso(),),
    )
    conn.commit()
    conn.close()
    logging.info("done. detail 저장 건수(이 실행): %s db=%s", fetched, db_path)


def main() -> None:
    ap = argparse.ArgumentParser(description="wasteguide.or.kr 품목사전 크롤러 (robots 허용 경로만)")
    ap.add_argument(
        "--db",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "wasteguide_dictionary.sqlite3",
        help="SQLite 출력 경로",
    )
    ap.add_argument("--delay-min", type=float, default=4.0, help="요청 간 최소 대기(초)")
    ap.add_argument("--delay-max", type=float, default=8.0, help="요청 간 최대 대기(초)")
    ap.add_argument("--limit", type=int, default=None, help="디테일 수집 ni_idx 개수 상한")
    ap.add_argument("--list-pages-max", type=int, default=None, help="목록 페이지 수 상한")
    ap.add_argument("--dry-run", action="store_true", help="목록만 확인하고 디테일 생략")
    ap.add_argument(
        "--resume",
        action="store_true",
        help="DB 스텁이 또는 상세 레코드가 충분하면 ajax 목록 생략 후 미수집 상세만 요청",
    )
    ap.add_argument(
        "--min-stubs-resume",
        type=int,
        default=500,
        metavar="N",
        help="--resume 시 이 개수 미만이면 목록부터 다시 받음",
    )
    ap.add_argument("-v", action="store_true", help="debug log")
    args = ap.parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.v else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    if args.delay_min > args.delay_max:
        logging.error("delay-min > delay-max")
        sys.exit(1)
    run(
        Path(args.db),
        args.delay_min,
        args.delay_max,
        args.limit,
        args.list_pages_max,
        args.dry_run,
        args.resume,
        args.min_stubs_resume,
    )


if __name__ == "__main__":
    main()

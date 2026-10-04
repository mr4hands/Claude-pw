"""Collect new items from the source feeds and pull full text for chosen stories."""

from __future__ import annotations

import logging
import time
from calendar import timegm
from datetime import datetime, timedelta, timezone

import feedparser
import httpx
import trafilatura

from .store import Item, Seen

log = logging.getLogger(__name__)

UA = "LobbyNewsBot/0.1 (+https://github.com/lobby-news; Hebrew gaming news, links back to sources)"
MAX_AGE = timedelta(hours=36)
MAX_TEXT = 8000


def _published(entry) -> datetime:
    parsed = entry.get("published_parsed") or entry.get("updated_parsed")
    if parsed:
        return datetime.fromtimestamp(timegm(parsed), timezone.utc)
    return datetime.now(timezone.utc)


def _strip(html: str) -> str:
    return (trafilatura.extract(f"<html><body>{html}</body></html>") or "").strip()


def parse_feed(raw: bytes | str, source: dict) -> list[Item]:
    feed = feedparser.parse(raw)
    items = []
    for e in feed.entries:
        url = e.get("link")
        if not url or not e.get("title"):
            continue
        items.append(
            Item(
                id=Item.make_id(url),
                source=source["name"],
                source_kind=source["kind"],
                title=e.title.strip(),
                url=url,
                summary=_strip(e.get("summary", ""))[:600],
                published=_published(e).isoformat(),
            )
        )
    return items


def collect(sources: list[dict], seen: Seen, client: httpx.Client) -> list[Item]:
    """New, recent items across all feeds. A dead feed is logged, never fatal."""
    cutoff = (datetime.now(timezone.utc) - MAX_AGE).isoformat()
    fresh: list[Item] = []
    for src in sources:
        try:
            r = client.get(src["url"])
            r.raise_for_status()
        except httpx.HTTPError as exc:
            log.warning("feed %s failed: %s", src["name"], exc)
            continue
        for item in parse_feed(r.content, src):
            if item.id not in seen and item.published >= cutoff:
                fresh.append(item)
    log.info("collected %d new items from %d sources", len(fresh), len(sources))
    return fresh


def fill_text(items: list[Item], client: httpx.Client) -> None:
    """Fetch and extract the article body; fall back to the feed summary."""
    for item in items:
        try:
            r = client.get(item.url)
            r.raise_for_status()
            item.text = (trafilatura.extract(r.text, include_comments=False) or "")[:MAX_TEXT]
        except httpx.HTTPError as exc:
            log.warning("article %s failed: %s", item.url, exc)
        if not item.text:
            item.text = item.summary
        time.sleep(0.5)  # be polite to publishers


def http_client() -> httpx.Client:
    return httpx.Client(headers={"User-Agent": UA}, timeout=20, follow_redirects=True)

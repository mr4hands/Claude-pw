"""Config, feed items, published articles and the newsroom's memory on disk.

Everything is plain JSON/TOML in the repo, so each scheduled run commits its
own state and the git history doubles as the audit log.
"""

from __future__ import annotations

import hashlib
import json
import tomllib
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config"
ARTICLES = ROOT / "content" / "articles"
STATE = ROOT / "state"


def now() -> datetime:
    return datetime.now(timezone.utc)


def load_site() -> dict:
    return tomllib.loads((CONFIG / "site.toml").read_text(encoding="utf-8"))


def load_sources() -> list[dict]:
    return tomllib.loads((CONFIG / "sources.toml").read_text(encoding="utf-8"))["source"]


@dataclass
class Item:
    """One entry from a source feed."""

    id: str
    source: str
    source_kind: str
    title: str
    url: str
    summary: str
    published: str  # ISO 8601
    text: str = ""  # full article text, fetched only for selected stories

    @staticmethod
    def make_id(url: str) -> str:
        return hashlib.sha1(url.encode()).hexdigest()[:12]


@dataclass
class Article:
    slug: str
    kind: str  # "news" | "rumor"
    title: str
    dek: str
    body_md: str
    tags: list[str]
    games: list[str]
    sources: list[dict]  # [{name, url, kind}]
    published_at: str
    reliability: dict | None = None  # rumors only: score, label, case_for, case_against, summary
    factcheck: dict = field(default_factory=dict)  # verdict, rounds, notes
    byline: str = "מערכת לובי · נכתב ע״י AI ונבדק אוטומטית מול המקורות"

    def path(self) -> Path:
        return ARTICLES / f"{self.published_at[:10]}-{self.slug}.json"

    def save(self) -> Path:
        p = self.path()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(asdict(self), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return p


def load_articles() -> list[Article]:
    arts = [Article(**json.loads(p.read_text(encoding="utf-8"))) for p in ARTICLES.glob("*.json")]
    return sorted(arts, key=lambda a: a.published_at, reverse=True)


class Seen:
    """Feed items already considered, so a story is never triaged twice."""

    def __init__(self, path: Path = STATE / "seen.json"):
        self.path = path
        self.data: dict[str, str] = json.loads(path.read_text()) if path.exists() else {}

    def __contains__(self, item_id: str) -> bool:
        return item_id in self.data

    def add(self, item_id: str) -> None:
        self.data[item_id] = now().isoformat()

    def prune(self, ttl_days: int) -> None:
        cutoff = (now() - timedelta(days=ttl_days)).isoformat()
        self.data = {k: v for k, v in self.data.items() if v >= cutoff}

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data, indent=1, sort_keys=True) + "\n")


def append_log(entry: dict) -> None:
    """One line per run: what was published, dropped and what it cost."""
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / "runs.jsonl").open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")

"""One newsroom run: collect → editor → (debate) → writer ⇄ fact-check → publish.

The per-story flow is a small state machine: each node reads and updates a
shared `StoryState`, and `route()` holds the conditional edges.

    debate? ─▶ write ─▶ check ─┬─ pass ─────────────▶ publish
                  ▲            ├─ fix (≤ MAX_FIXES) ─┘ back to write
                  └────────────┴─ reject / out of fixes ▶ drop

There is no human in the loop by design: anything the fact-checker will not
pass is dropped and logged rather than queued for approval.

Usage:  python -m newsroom.pipeline run [--dry-run]
"""

from __future__ import annotations

import argparse
import logging
import sys
from dataclasses import dataclass, field

from . import agents
from .fetch import collect, fill_text, http_client
from .llm import LLM, ClaudeLLM, RefusedError
from .store import Article, Item, Seen, append_log, load_articles, load_site, load_sources, now

log = logging.getLogger("newsroom")

MAX_FIXES = 2
MIN_RUMOR_SCORE = 20  # below this a rumor isn't worth amplifying at all


@dataclass
class StoryState:
    story: dict
    items: list[Item]
    reliability: dict | None = None
    draft: dict | None = None
    check: dict | None = None
    fixes: int = 0
    outcome: str = ""  # "published" | "dropped: <why>"
    trail: list[str] = field(default_factory=list)


# ---------------------------------------------------------------- nodes

def node_debate(llm: LLM, s: StoryState) -> None:
    s.reliability = agents.debate(llm, s.story, s.items)
    s.trail.append(f"debate score={s.reliability['score']}")


def node_write(llm: LLM, s: StoryState) -> None:
    problems = s.check["problems"] if s.check else None
    s.draft = agents.writer(llm, s.story, s.items, s.reliability, fix=problems, draft=s.draft)
    s.trail.append("write" if problems is None else f"rewrite#{s.fixes}")


def node_check(llm: LLM, s: StoryState) -> None:
    s.check = agents.fact_check(llm, s.story, s.items, s.draft)
    s.trail.append(f"check={s.check['verdict']}({len(s.check['problems'])})")


def node_publish(s: StoryState) -> Article:
    d = s.draft
    art = Article(
        slug=_clean_slug(d["slug"]),
        kind=s.story["kind"],
        title=d["title"].strip(),
        dek=d["dek"].strip(),
        body_md=d["body_md"].strip(),
        tags=d["tags"][:5],
        games=d["games"][:5],
        sources=[{"name": it.source, "url": it.url, "kind": it.source_kind} for it in s.items],
        published_at=now().isoformat(timespec="seconds"),
        reliability=s.reliability,
        factcheck={"verdict": "pass", "rounds": s.fixes + 1},
    )
    while art.path().exists():
        art.slug += "-2"
    art.save()
    s.outcome = "published"
    return art


# ---------------------------------------------------------------- edges

def route(s: StoryState) -> str:
    """Next node after the last one that ran."""
    last = s.trail[-1] if s.trail else ""
    if not last:
        return "debate" if s.story["kind"] == "rumor" else "write"
    if last.startswith("debate"):
        return "drop" if s.reliability["score"] < MIN_RUMOR_SCORE else "write"
    if last.startswith(("write", "rewrite")):
        return "check"
    verdict = s.check["verdict"]
    if verdict == "pass" and not s.check["problems"]:
        return "publish"
    if verdict == "reject" or s.fixes >= MAX_FIXES:
        return "drop"
    s.fixes += 1
    return "write"


def run_story(llm: LLM, s: StoryState) -> Article | None:
    while True:
        step = route(s)
        if step == "debate":
            node_debate(llm, s)
        elif step == "write":
            node_write(llm, s)
        elif step == "check":
            node_check(llm, s)
        elif step == "publish":
            return node_publish(s)
        else:
            s.outcome = f"dropped: {s.trail[-1]}"
            return None


def _clean_slug(slug: str) -> str:
    keep = "".join(c if c.isascii() and (c.isalnum() or c == "-") else "-" for c in slug.lower())
    return "-".join(p for p in keep.split("-") if p)[:60] or "story"


# ---------------------------------------------------------------- run

def run(llm: LLM, items: list[Item], seen: Seen, cfg: dict, client=None) -> dict:
    articles = load_articles()
    recent = [a.title for a in articles[:40]]
    today = now().date().isoformat()
    room = min(cfg["max_stories_per_run"],
               cfg["max_stories_per_day"] - sum(a.published_at.startswith(today) for a in articles))
    for it in items:
        seen.add(it.id)
    if not items or room <= 0:  # daily quota spent: skip even the editor call
        return {"published": [], "dropped": [], "considered": len(items)}

    stories = agents.editor(llm, items, recent)
    by_id = {it.id: it for it in items}
    chosen = sorted(
        (s for s in stories if s["kind"] != "skip" and s["importance"] >= cfg["min_importance"]),
        key=lambda s: -s["importance"],
    )[:room]
    log.info("editor: %d stories, %d chosen", len(stories), len(chosen))

    published, dropped = [], []
    for story in chosen:
        story_items = [by_id[i] for i in story["item_ids"]]
        if client is not None:
            fill_text(story_items, client)
        state = StoryState(story=story, items=story_items)
        try:
            art = run_story(llm, state)
        except RefusedError as exc:
            state.outcome = f"dropped: refused ({exc})"
            art = None
        except Exception as exc:  # one bad story must not sink the run
            log.exception("story failed: %s", story["topic"])
            state.outcome = f"dropped: error {type(exc).__name__}"
            art = None
        (published if art else dropped).append({"topic": story["topic"], "trail": state.trail,
                                                "outcome": state.outcome, **({"slug": art.slug} if art else {})})
        log.info("%s → %s %s", story["topic"], state.outcome, state.trail)
    return {"published": published, "dropped": dropped, "considered": len(items)}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="newsroom")
    p.add_argument("command", choices=["run"])
    p.add_argument("--dry-run", action="store_true", help="collect and list new items, no model calls")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    cfg = load_site()["newsroom"]
    seen = Seen()
    with http_client() as client:
        items = collect(load_sources(), seen, client)
        if args.dry_run:
            for it in items:
                print(f"{it.published[:16]}  {it.source:16}  {it.title}")
            return 0
        llm = ClaudeLLM()
        result = run(llm, items, seen, cfg, client)
    seen.prune(cfg["seen_ttl_days"])
    seen.save()
    append_log({"at": now().isoformat(timespec="seconds"), "model": llm.model, "usage": llm.usage, **result})
    print(f"published {len(result['published'])}, dropped {len(result['dropped'])}, usage {llm.usage}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

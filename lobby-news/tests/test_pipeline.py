from datetime import datetime, timezone
from email.utils import format_datetime
from pathlib import Path

import httpx
import pytest

from newsroom import pipeline, store
from newsroom.fetch import collect

FIXTURE = Path(__file__).parent / "fixtures" / "feed.xml"
SOURCE = {"name": "Test", "url": "https://example.com/feed", "kind": "press"}


@pytest.fixture(autouse=True)
def tmp_content(tmp_path, monkeypatch):
    monkeypatch.setattr(store, "ARTICLES", tmp_path / "articles")
    monkeypatch.setattr(store, "STATE", tmp_path / "state")
    return tmp_path


def feed_client() -> httpx.Client:
    body = FIXTURE.read_text().replace("{NOW}", format_datetime(datetime.now(timezone.utc)))
    return httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(200, text=body)))


class FakeLLM:
    """Scripted answers keyed by which agent is calling (recognised from its system prompt)."""

    def __init__(self, checks=("pass",)):
        self.checks = list(checks)
        self.calls = []

    def json(self, *, system, prompt, schema, effort="medium"):
        agent = ("editor" if "editor-in-chief" in system else "advocate" if "likely TRUE" in system
                 else "skeptic" if "likely FALSE" in system else "judge" if "impartial judge" in system
                 else "check" if "fact-checker" in system else "writer")
        self.calls.append(agent)
        if agent == "editor":
            ids = [line.split("id=")[1].split(" ")[0] for line in prompt.splitlines() if "id=" in line]
            plan = [("Space Game 2 announced", "news", 5), ("Space Game 2 Switch 2 port", "rumor", 3), ("deals", "skip", 1)]
            stories = [{"item_ids": [i], "topic": t, "kind": k, "importance": imp, "reason": ""}
                       for i, (t, k, imp) in zip(ids, plan)]
            stories.append({"item_ids": ["made-up-id"], "topic": "hallucinated", "kind": "news", "importance": 5, "reason": ""})
            return {"stories": stories}
        if agent in ("advocate", "skeptic"):
            return {"points": ["x"]}
        if agent == "judge":
            return {"score": 45, "label": "לא ברור", "case_for": ["א"], "case_against": ["ב"], "summary": "ג"}
        if agent == "check":
            v = self.checks.pop(0) if len(self.checks) > 1 else self.checks[0]
            return {"verdict": v, "problems": [] if v == "pass" else [{"claim": "c", "issue": "i"}], "ai_tone": False}
        n = self.calls.count("writer")
        return {"title": f"כותרת {n}", "dek": "תקציר", "slug": "Space Game 2: הכרזה!", "body_md": "גוף **הכתבה**",
                "tags": ["PS5"], "games": ["Space Game 2"]}


CFG = {"max_stories_per_run": 4, "min_importance": 3}


def test_collect_skips_old_and_seen_items():
    seen = store.Seen(Path("/nonexistent/seen.json"))
    items = collect([SOURCE], seen, feed_client())
    assert [i.title for i in items][:3] == [
        "Studio announces Space Game 2 for PS5 and PC", "Insider: Space Game 2 coming to Switch 2", "Best headset deals this week"]
    assert len(items) == 3  # 2018 item is too old
    seen.add(items[0].id)
    assert len(collect([SOURCE], seen, feed_client())) == 2


def test_run_publishes_news_and_rumor_and_skips_the_rest():
    seen = store.Seen(Path("/nonexistent/seen.json"))
    items = collect([SOURCE], seen, feed_client())
    llm = FakeLLM()
    result = pipeline.run(llm, items, seen, CFG)

    assert len(result["published"]) == 2 and not result["dropped"]
    arts = store.load_articles()
    rumor = next(a for a in arts if a.kind == "rumor")
    assert rumor.reliability["score"] == 45
    assert all(a.slug.startswith("space-game-2") for a in arts)
    assert llm.calls.count("advocate") == 1  # debate only for the rumor
    assert all(i.id in seen for i in items)


def test_fact_check_fix_loop_then_drop():
    seen = store.Seen(Path("/nonexistent/seen.json"))
    items = collect([SOURCE], seen, feed_client())[:1]
    # fix, fix, fix: two rewrites allowed, then dropped
    llm = FakeLLM(checks=("fix",))
    result = pipeline.run(llm, items, seen, {**CFG, "max_stories_per_run": 1})
    assert not result["published"]
    assert result["dropped"][0]["trail"] == ["write", "check=fix(1)", "rewrite#1", "check=fix(1)", "rewrite#2", "check=fix(1)"]

    # fix once, then pass: published after one rewrite
    llm = FakeLLM(checks=("fix", "pass"))
    result = pipeline.run(llm, items, seen, {**CFG, "max_stories_per_run": 1})
    assert result["published"][0]["trail"] == ["write", "check=fix(1)", "rewrite#1", "check=pass(0)"]


def test_reject_and_low_score_rumor_are_dropped():
    seen = store.Seen(Path("/nonexistent/seen.json"))
    items = collect([SOURCE], seen, feed_client())
    llm = FakeLLM(checks=("reject",))
    result = pipeline.run(llm, items[:1], seen, {**CFG, "max_stories_per_run": 1})
    assert result["dropped"][0]["outcome"] == "dropped: check=reject(1)"


def test_site_builds(tmp_path):
    from sitegen.build import build
    seen = store.Seen(Path("/nonexistent/seen.json"))
    pipeline.run(FakeLLM(), collect([SOURCE], seen, feed_client()), seen, CFG)
    out = tmp_path / "site"
    assert build(out) == 2
    html = (out / "index.html").read_text()
    assert 'dir="rtl"' in html and "כותרת" in html
    assert (out / "rumors.html").read_text().count('role="img"') == 1
    assert "<item>" in (out / "feed.xml").read_text()

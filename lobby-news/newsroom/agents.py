"""The newsroom's agents. Each is one focused model call with a strict schema.

editor      – picks which of the new feed items are worth a story, groups duplicates
debate      – rumors only: advocate, skeptic, then a judge who scores reliability
writer      – turns the sources into a Hebrew article in the house style
fact_check  – compares the draft against the sources, claim by claim
"""

from __future__ import annotations

import json
from pathlib import Path

from .llm import BOOL, INT, LLM, STR, arr, enum, obj
from .store import Item

STYLE = (Path(__file__).parent / "prompts" / "style.md").read_text(encoding="utf-8")


def _sources_block(items: list[Item], full: bool = True) -> str:
    parts = []
    for i, it in enumerate(items, 1):
        body = it.text if full else it.summary
        parts.append(
            f'<source n="{i}" name="{it.source}" kind="{it.source_kind}" url="{it.url}" '
            f'published="{it.published}">\n<title>{it.title}</title>\n{body}\n</source>'
        )
    return "\n\n".join(parts)


# ---------------------------------------------------------------- editor

EDITOR_SYSTEM = """You are the editor-in-chief of לובי, a Hebrew gaming news site for Israeli gamers.
You receive new items from English-language gaming feeds. Decide which deserve a Hebrew story.

Group items that report the same story. For each group decide:
- kind "news": confirmed by an official source or a reputable outlet reporting it as fact.
- kind "rumor": leaks, insider claims, unconfirmed reports, "according to sources".
- kind "skip": deals roundups, sponsored posts, guides/walkthroughs, opinion columns, minor patch notes,
  quizzes, anything already covered in the recent headlines list, or anything Israeli gamers won't care about.

importance (1-5): 5 = major announcement/launch/delay of a big game or platform news; 3 = solid mid-tier news;
1 = trivia. Be strict. The test: would an Israeli gamer bring this up with friends? A typical day has only
5-10 stories like that across all runs, so on most runs zero to two items deserve 3 or above."""

EDITOR_SCHEMA = obj({
    "stories": arr(obj({
        "item_ids": arr(STR),
        "topic": STR,
        "kind": enum("news", "rumor", "skip"),
        "importance": INT,
        "reason": STR,
    }))
})


def editor(llm: LLM, items: list[Item], recent_titles: list[str]) -> list[dict]:
    listing = "\n".join(
        f"- id={it.id} | {it.source} ({it.source_kind}) | {it.title} | {it.summary[:300]}" for it in items
    )
    recent = "\n".join(f"- {t}" for t in recent_titles) or "(none)"
    prompt = f"<recent_headlines>\n{recent}\n</recent_headlines>\n\n<new_items>\n{listing}\n</new_items>"
    out = llm.json(system=EDITOR_SYSTEM, prompt=prompt, schema=EDITOR_SCHEMA, effort="low")
    known = {it.id for it in items}
    stories = []
    for s in out["stories"]:
        s["item_ids"] = [i for i in s["item_ids"] if i in known]
        if s["item_ids"]:
            stories.append(s)
    return stories


# ---------------------------------------------------------------- rumor debate

ADVOCATE_SYSTEM = """You argue that a gaming rumor is likely TRUE. Use only the provided sources and well-known,
verifiable context (e.g. a leaker's public track record, studio patterns). Make the strongest honest case.
Do not invent evidence. Answer in English."""

SKEPTIC_SYSTEM = """You argue that a gaming rumor is likely FALSE or overstated. Look for: single anonymous source,
outlets citing each other in a circle, vague wording, contradictions, a leaker with a poor record,
and incentives to fabricate. Make the strongest honest case. Do not invent evidence. Answer in English."""

ARGUMENT_SCHEMA = obj({"points": arr(STR)})

JUDGE_SYSTEM = """You are the impartial judge in a debate about a gaming rumor. Weigh both cases against the sources
and give a reliability score from 0 to 100 (probability the core claim turns out true).
Labels: 80+ "סביר מאוד", 60-79 "סביר", 40-59 "לא ברור", 20-39 "מפוקפק", under 20 "כנראה לא נכון".
Write case_for, case_against and summary in natural Hebrew (2-4 short bullet points each; summary one sentence)."""

JUDGE_SCHEMA = obj({
    "score": INT,
    "label": enum("סביר מאוד", "סביר", "לא ברור", "מפוקפק", "כנראה לא נכון"),
    "case_for": arr(STR),
    "case_against": arr(STR),
    "summary": STR,
})


def debate(llm: LLM, story: dict, items: list[Item]) -> dict:
    ctx = f"<rumor>{story['topic']}</rumor>\n\n{_sources_block(items)}"
    pro = llm.json(system=ADVOCATE_SYSTEM, prompt=ctx, schema=ARGUMENT_SCHEMA, effort="low")
    con = llm.json(system=SKEPTIC_SYSTEM, prompt=ctx, schema=ARGUMENT_SCHEMA, effort="low")
    prompt = (
        f"{ctx}\n\n<advocate>\n{json.dumps(pro['points'], ensure_ascii=False)}\n</advocate>\n"
        f"<skeptic>\n{json.dumps(con['points'], ensure_ascii=False)}\n</skeptic>"
    )
    verdict = llm.json(system=JUDGE_SYSTEM, prompt=prompt, schema=JUDGE_SCHEMA, effort="medium")
    verdict["score"] = max(0, min(100, int(verdict["score"])))
    return verdict


# ---------------------------------------------------------------- writer

WRITER_SCHEMA = obj({
    "title": STR,
    "dek": STR,
    "slug": STR,
    "body_md": STR,
    "tags": arr(STR),
    "games": arr(STR),
})

WRITER_TASK = """Write the story described below as a לובי article, following the style guide exactly.
`slug`: short English kebab-case (e.g. "gta-6-delay-2027"). `tags`: 2-5 Hebrew or platform tags
(e.g. "PS5", "Xbox", "נינטנדו", "PC", "שמועות", "השקות", "עסקים"). `games`: the game titles involved, in English."""


def writer(llm: LLM, story: dict, items: list[Item], reliability: dict | None,
           fix: list[dict] | None = None, draft: dict | None = None) -> dict:
    parts = [WRITER_TASK, f"<story kind=\"{story['kind']}\">{story['topic']}</story>", _sources_block(items)]
    if reliability:
        parts.append(
            "<reliability_verdict>\n" + json.dumps(reliability, ensure_ascii=False) + "\n</reliability_verdict>\n"
            "Mention the reliability verdict in the body in one sentence; the site also shows it as a meter."
        )
    if fix:
        parts.append(
            "<previous_draft>\n" + json.dumps(draft, ensure_ascii=False) + "\n</previous_draft>\n"
            "<fact_check_problems>\n" + json.dumps(fix, ensure_ascii=False) + "\n</fact_check_problems>\n"
            "Rewrite the draft fixing every problem. Remove any claim the sources do not support."
        )
    return llm.json(system=STYLE, prompt="\n\n".join(parts), schema=WRITER_SCHEMA, effort="medium")


# ---------------------------------------------------------------- fact check

FACTCHECK_SYSTEM = """You are the fact-checker of a Hebrew gaming news site. Compare the Hebrew draft against the
English sources, claim by claim. Flag:
- any fact, number, date, platform, price, name or quote not supported by the sources
- mistranslations that change meaning
- a rumor presented as confirmed fact
- any claim of first-hand experience (the site is written by AI and never played/tested anything)
- sentences copied verbatim from a source (beyond a short attributed quote)
verdict: "pass" if publishable as is, "fix" if the problems are fixable by rewriting,
"reject" if the story itself is unsupported or the sources are too thin to write it honestly."""

FACTCHECK_SCHEMA = obj({
    "verdict": enum("pass", "fix", "reject"),
    "problems": arr(obj({"claim": STR, "issue": STR})),
    "ai_tone": BOOL,
})


def fact_check(llm: LLM, story: dict, items: list[Item], draft: dict) -> dict:
    prompt = (
        f"<story kind=\"{story['kind']}\">{story['topic']}</story>\n\n{_sources_block(items)}\n\n"
        f"<draft>\n{json.dumps(draft, ensure_ascii=False)}\n</draft>\n\n"
        "Set ai_tone true if the Hebrew reads like a stiff machine translation (that counts as a 'fix' problem too)."
    )
    return llm.json(system=FACTCHECK_SYSTEM, prompt=prompt, schema=FACTCHECK_SCHEMA, effort="high")

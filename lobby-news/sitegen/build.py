"""Render content/articles/*.json into a static site in _site/.

Usage:  python -m sitegen.build [--out _site]
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
from datetime import datetime
from email.utils import format_datetime
from pathlib import Path
from xml.sax.saxutils import escape

import markdown
from jinja2 import Environment, FileSystemLoader, select_autoescape

from newsroom.store import ROOT, Article, load_articles, load_site

HERE = Path(__file__).parent
MONTHS = ["ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"]


def he_date(iso: str) -> str:
    d = datetime.fromisoformat(iso)
    return f"{d.day} ב{MONTHS[d.month - 1]} {d.year}"


def hue(text: str) -> int:
    return int(hashlib.md5(text.encode()).hexdigest()[:4], 16) % 360


def meter_class(score: int) -> str:
    return "hi" if score >= 60 else "mid" if score >= 40 else "lo"


def build(out: Path) -> int:
    site = load_site()
    base = site["base_path"].rstrip("/")
    env = Environment(loader=FileSystemLoader(HERE / "templates"), autoescape=select_autoescape(["html"]))
    env.filters.update(he_date=he_date, hue=hue, meter_class=meter_class)
    env.globals.update(site=site, base=base, year=datetime.now().year)

    arts = load_articles()
    for a in arts:
        a.html = markdown.markdown(a.body_md, extensions=["extra", "sane_lists"])  # type: ignore[attr-defined]

    if out.exists():
        shutil.rmtree(out)
    (out / "a").mkdir(parents=True)
    shutil.copytree(HERE / "static", out / "static")

    def page(name: str, path: str, **ctx) -> None:
        (out / path).write_text(env.get_template(name).render(**ctx), encoding="utf-8")

    page("index.html", "index.html", articles=arts[:40], nav="home")
    page("rumors.html", "rumors.html", articles=[a for a in arts if a.kind == "rumor"][:60], nav="rumors")
    page("reviews.html", "reviews.html", nav="reviews")
    page("about.html", "about.html", nav="about")
    page("404.html", "404.html", nav="")
    for a in arts:
        related = [b for b in arts if b is not a and set(b.games) & set(a.games)][:4]
        page("article.html", f"a/{a.slug}.html", a=a, related=related, nav="")

    (out / "feed.xml").write_text(rss(site, arts[:30]), encoding="utf-8")
    (out / "sitemap.xml").write_text(sitemap(site, arts), encoding="utf-8")
    (out / ".nojekyll").write_text("")
    return len(arts)


def _url(site: dict, a: Article) -> str:
    return f"{site['base_url'].rstrip('/')}/a/{a.slug}.html"


def rss(site: dict, arts: list[Article]) -> str:
    items = "".join(
        f"<item><title>{escape(a.title)}</title><link>{_url(site, a)}</link><guid>{_url(site, a)}</guid>"
        f"<pubDate>{format_datetime(datetime.fromisoformat(a.published_at))}</pubDate>"
        f"<description>{escape(a.dek)}</description></item>"
        for a in arts
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>'
        f"<title>{escape(site['name'])}</title><link>{site['base_url']}</link>"
        f"<description>{escape(site['tagline'])}</description><language>he</language>{items}</channel></rss>\n"
    )


def sitemap(site: dict, arts: list[Article]) -> str:
    urls = "".join(f"<url><loc>{_url(site, a)}</loc><lastmod>{a.published_at[:10]}</lastmod></url>" for a in arts)
    return f'<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{urls}</urlset>\n'


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=str(ROOT / "_site"))
    n = build(Path(p.parse_args().out))
    print(f"built {n} articles")


if __name__ == "__main__":
    main()

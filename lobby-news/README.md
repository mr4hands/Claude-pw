# לובי: חדר חדשות גיימינג אוטונומי בעברית

אתר חדשות גיימינג בעברית שרץ לבד. כל שעתיים GitHub Actions מריץ צוות של סוכני Claude שאוסף חדשות, כותב, בודק עובדות ומפרסם אתר סטטי ל-GitHub Pages. בלי שרת, בלי מסד נתונים ובלי אף אחד שצריך לאשר.

```
RSS (סוני, Xbox, נינטנדו, Steam, Eurogamer...)
  → עורך ראשי: מסנן, מאחד כפילויות, מדרג חשיבות
  → [שמועה?] דיבייט: סוכן בעד ⟷ סוכן נגד → שופט קובע ציון אמינות 0–100
  → כתב: ידיעה בעברית לפי מדריך הסגנון, עם ייחוס למקורות
  → בודק עובדות: טענה מול מקור ── תיקון (עד פעמיים) ──→ נכשל? לא מתפרסם
  → פרסום: content/articles/*.json → אתר סטטי
```

## עקרונות

- **שקיפות.** האתר אומר בגלוי שהחדשות נכתבות ע״י AI, ומקשר לכל מקור.
- **ה-AI לא ממציא חוויות.** הוא לא כותב "שיחקנו" או "בדקנו". ביקורות נכתבות רק ע״י מבקרים אנושיים.
- **אין אישור ידני.** מה שלא עובר בדיקת עובדות לא מתפרסם, ונרשם ב-`state/runs.jsonl`.
- **בלי תמונות מוגנות.** הכיסויים נוצרים אוטומטית מצבעים ושם המשחק.

## הפעלה (פעם אחת, כ-5 דקות)

1. **מפתח API**: ב-[console.anthropic.com](https://console.anthropic.com) צרו API key, והגדירו מגבלת הוצאה חודשית (Limits).
2. **סוד ב-GitHub**: Settings ← Secrets and variables ← Actions ← New repository secret. שם: `ANTHROPIC_API_KEY`.
3. **אתר**: Settings ← Pages ← Source: **GitHub Actions**. (בחשבון GitHub חינמי, Pages עובד רק במאגר ציבורי.)
4. **הרצה ראשונה**: Actions ← newsroom ← Run workflow. אחרי כמה דקות האתר באוויר, ומאז הוא מתעדכן לבד כל שעתיים.
5. ב-`config/site.toml` עדכנו `base_url` לכתובת שקיבלתם מ-Pages.

### דומיין משלכם
Settings ← Pages ← Custom domain. אחר כך ב-`config/site.toml` שנו `base_path = ""` ו-`base_url` לדומיין.

## עלויות ושליטה

| הגדרה | איפה | ברירת מחדל |
|---|---|---|
| תקרת ידיעות ליום | `config/site.toml` → `max_stories_per_day` | 8 |
| כמה ידיעות בכל הרצה | `config/site.toml` → `max_stories_per_run` | 2 |
| סף חשיבות | `config/site.toml` → `min_importance` | 3 |
| תדירות | `.github/workflows/newsroom.yml` → `cron` | כל שעתיים |
| מודל | Settings ← Variables ← `NEWSROOM_MODEL` | `claude-opus-5-5` |

הערכה גסה: בערך $0.25–0.45 לידיעה (כתיבה, בדיקה ותיקונים; שמועה קצת יותר), ועוד כמה סנטים לכל הרצה של העורך. עם תקרה של 8 ידיעות ביום זה בערך $70–110 לחודש, ועם `NEWSROOM_MODEL=claude-sonnet-5-5` בערך חצי. צריכת הטוקנים בפועל של כל הרצה נרשמת ב-`state/runs.jsonl`.

## מקורות

`config/sources.toml`: מוסיפים `[[source]]` עם `name`, `url` של פיד RSS ו-`kind` (`official` / `press` / `aggregator`).

## פיתוח

```bash
pip install -r requirements.txt pytest
python -m pytest -q                       # בדיקות עם מודל מדומה, בלי API
python -m newsroom.pipeline run --dry-run # רק לראות מה נאסף מהפידים
python -m newsroom.pipeline run           # הרצה אמיתית (צריך ANTHROPIC_API_KEY)
python -m sitegen.build                   # בונה את האתר ל-_site/
```

| קובץ | תפקיד |
|---|---|
| `newsroom/agents.py` | הסוכנים: עורך, דיבייט, כתב, בודק עובדות |
| `newsroom/prompts/style.md` | מדריך הסגנון והכללים שהכתב ובודק העובדות אוכפים |
| `newsroom/pipeline.py` | התזמור: מצב לכל ידיעה, צמתים ומעברים מותנים |
| `newsroom/fetch.py` | איסוף RSS וחילוץ טקסט נקי מכתבות |
| `sitegen/` | בניית האתר (Jinja, RTL, מצב כהה) |

## הלאה

- **מעקב מחירים ודילים**: מחירי משחקים וחומרה בשקלים מ-KSP, Ivory, Bug ו-Steam, עם קישורי שותפים מסומנים.
- **כלי למבקר**: המבקר מכתיב הערות אחרי משחק, המערכת מכינה טיוטה וגיליון עובדות, והוא עורך וחותם.
- **הפצה**: ערוץ טלגרם/וואטסאפ אוטומטי לכל ידיעה.

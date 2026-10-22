# Design system ("quiet ink", 0.10.0)

Calm and editorial: warm mist background, white cards, one brand colour used sparingly.

| Piece | Where | Use |
|---|---|---|
| Colours, tints, font, type scale, shapes | `ui/theme/Theme.kt` | `MaterialTheme`, `LocalTints` (one soft hue per category) |
| Page title / sub-page bar | `PageHeader`, `SubPageBar` (`ui/Design.kt`) | every tab / every settings page |
| Grouped card + hairline | `CardGroup`, `RowDivider` | lists of rows; inset past a 36 dp icon tile |
| Rows | `NavRow` (opens a page), `SwitchRow` (whole row toggles), `InfoRow` | settings |
| Notes | `Note` | explanations under a group — keep row bodies to one line |
| Status | `HeroCard`, `Pill(Tone)` | top-of-page state; small status labels |
| Icons | `AppIcons` (`ui/Icons.kt`) | Material paths not in icons-core |

Rules of thumb: one idea per row; long explanations go in a `Note`, not the row body;
group labels are short nouns; no borders or shadows — separation comes from the mist
background between white cards. Hebrew-first, forced RTL.

Screenshots: `app/src/test/java/com/callnotes/ui/ScreenshotTest.kt` renders stateless
pieces with sample data; CI (`screenshots.yml`) publishes them to the `screenshots`
branch. Locally: `git fetch origin screenshots && git archive origin/screenshots <branch-dir> | tar -x`.

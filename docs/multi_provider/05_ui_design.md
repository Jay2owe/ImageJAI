# UI Design — Cascading Provider/Model Dropdown

**Status:** Draft · **Date:** 2026-05-02
**Depends on:** [`01`](01_provider_survey.md), [`02`](02_curation_strategy.md), [`06`](06_tier_safety.md) · **Consumed by:** stage 7
**Audience:** Java/Swing implementer.

## Purpose

Glues the prior stages into a single Swing UI: a cascading provider→model dropdown replacing the existing flat `JComboBox<String>` agent selector. Deep enough that a Java/Swing engineer can implement without further design calls — every visual state mocked, every Swing pitfall named, every existing-file edit line-referenced.

## Sections

1. [Current-state audit](#1-current-state-audit) · 2. [Cascading-dropdown architecture](#2-cascading-dropdown-architecture) · 3. [Swing-specific implementation notes](#3-swing-specific-implementation-notes) · 4. [Visual states](#4-visual-states-with-ascii-mockups) · 5. [Hover-detail card integration](#5-hover-detail-card-integration) · 6. [First-use-of-paid-model dialog integration](#6-first-use-of-paid-model-dialog-integration) · 7. [Status-icon click behaviour](#7-status-icon-click-behaviour) · 8. [Refresh-button UX](#8-refresh-button-ux) · 9. [Settings-panel touchpoints](#9-settings-panel-touchpoints) · 10. [Migration](#10-migration-from-current-agentlauncher--airootpanel) · 11. [Java-side files to create / modify](#11-java-side-files-to-create--modify)

---

## 1. Current-state audit

The existing model picker is **not** in `AgentLauncher.java` — that is a backend-only class (no Swing imports). The picker lives in `AiRootPanel.java`.

### What `AgentLauncher.java` does today

`src/main/java/imagejai/engine/AgentLauncher.java` (380 lines):

- A static `KNOWN_AGENTS` table of nine CLI agents (`Claude Code`, `Aider`, `GitHub Copilot CLI`, `Gemini CLI`, `Open Interpreter`, `Cline`, `Codex CLI`, `Gemma 4 31B`, `Gemma 4 31B (Claude-style)`) at lines 62–72. Each row: `{display name, command, description, context flags}`.
- `detectAgents()` (lines 97–118) — `where`/`which` probe per row, returns installed subset as `List<AgentInfo>`.
- `launch(AgentInfo, Mode)` (lines 135–159) — spawns the chosen CLI in EXTERNAL (detached terminal) or EMBEDDED (PTY inside Fiji panel). The call site the new dropdown ultimately hits.
- Constant `LOCAL_ASSISTANT_NAME = "Local Assistant"` (line 24) used as the always-present in-panel option.

No Swing in this file. Pure detect/launch service.

### What `AiRootPanel.java` does today

`src/main/java/imagejai/ui/AiRootPanel.java` builds the visible header. Relevant region:

| Line(s) | Code | Purpose |
|---------|------|---------|
| 63 | `private JComboBox<String> agentSelector;` | The flat dropdown field. |
| 209–212 | `agentSelector = new JComboBox<String>(); agentSelector.setPreferredSize(new Dimension(140, 22)); agentSelector.setFont(...); refreshAgentSelector(...)` | Construction — fixed 140×22 px, single column of plain strings. |
| 213–223 | `agentSelector.addActionListener(...)` | On change: persist to `settings.setSelectedAgentName(selected)`, refresh launch-button enablement. |
| 224 | `leftPanel.add(agentSelector);` | Mounted in header `FlowLayout`. |
| 257–265 | `agentBtn = createHeaderButton("▶", "Launch selected external agent");` then `addActionListener(...) -> launchSelectedAgentAsync()` | Play button, two slots right of dropdown. |
| 311–337 | `refreshAgentSelectorAsync()` | `SwingWorker` that calls `agentLauncher.detectAgents()` off-EDT then `refreshAgentSelector(agents)`. |
| 339–371 | `refreshAgentSelector(List<AgentInfo>)` | Repopulates: strips listeners, removes items, inserts `"Local Assistant"` first, then each detected name, restores selection, re-attaches listeners. |
| 373–384 | `updateLaunchButtonState()` | Enables ▶ only when selected name maps to a detected `AgentInfo`. |
| 398–411 | `launchSelectedAgentAsync()` | Reads `settings.getSelectedAgentName()`, looks up in `detectedAgents`, hands off to `launchAgentAsync(agent)`. |
| 413–436 | `launchAgentAsync(AgentInfo)` | Picks `Mode.EMBEDDED` vs `Mode.EXTERNAL` from settings, calls `agentLauncher.launch(agent, mode)`, hands `AgentSession` to `handleLaunchedSession`. |

### Quoted construction site

```java
// AiRootPanel.java:209
agentSelector = new JComboBox<String>();
agentSelector.setPreferredSize(new Dimension(140, 22));
agentSelector.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
refreshAgentSelector(new ArrayList<AgentLauncher.AgentInfo>());
agentSelector.addActionListener(new ActionListener() {
    @Override
    public void actionPerformed(ActionEvent e) {
        String selected = (String) agentSelector.getSelectedItem();
        if (selected != null) {
            settings.setSelectedAgentName(selected);
            chatView.refreshInputState();
            updateLaunchButtonState();
        }
    }
});
leftPanel.add(agentSelector);
```

### Implications for the migration

- Dropdown surface today is **140 × 22 px**. The cascading replacement must fit (or grow gracefully — `FlowLayout`, lines 254/293 — so widening is cheap).
- "Selection" stored is a single string (`settings.selectedAgentName`). Multi-provider needs `(provider_id, model_id)` — small `Settings` schema bump.
- The play button (`agentBtn`, line 257) is **separate** from the dropdown today. The new design fuses them — clicking a model row in the cascading menu *is* the launch. The standalone ▶ button is **repurposed to "re-launch last"** (user decision; §10.3).
- `KNOWN_AGENTS` (line 62) becomes one *transport* in the new world — "spawn a CLI" — alongside the new LiteLLM-routed paths. The dropdown is keyed off the merged model list from stage 2, not off this table.

---

## 2. Cascading-dropdown architecture

### 2.1 Replacement at a glance

The flat `JComboBox<String>` at AiRootPanel.java:63/209 is replaced by a **single header button** that, when clicked, pops a `JPopupMenu`. The popup is the provider list. Each provider is a cascading `JMenu` whose submenu carries that provider's models. The button's caption shows the *currently selected* model (e.g. `Claude Sonnet 4.6 ▾`). The standalone ▶ at AiRootPanel.java:257 is repurposed to "re-launch last" (§10.3); model rows in the cascading menu launch on click — the dropdown *is* the launcher.

### 2.2 Component hierarchy (Swing)

```
AiRootPanel.header  (FlowLayout, line 254/293)
└── ModelPickerButton                                ← new JButton, replaces JComboBox at line 209
        on-click → showsPopup(modelMenuPopup)
        ↑ caption refreshes to current selection ("Claude Sonnet 4.6 ▾")

modelMenuPopup : JPopupMenu                          ← new
├── HeaderStrip                                      ← custom JPanel (non-selectable item)
│      ├── JLabel "Models"
│      ├── JLabel "last updated 3 min ago"           ← from cache fetched_at
│      └── JButton "↻ refresh"                       ← §8
├── JSeparator
├── PinnedSection (visible if user has pins)         ← floats pins above providers
│      └── ModelMenuItem × N                         ← flat-rendered, provider shown inline
├── JSeparator
├── ProviderMenu : JMenu  "Anthropic ✓"              ← cascading; status icon in name
│      ├── ModelMenuItem  🔵 ✓ ★ Claude Opus 4.7
│      ├── ModelMenuItem  🔵 ✓ ☆ Claude Sonnet 4.6
│      ├── ModelMenuItem  🔵 ✓ ☆ Claude Haiku 4.5
│      ├── (curated soft-deprecated, struck-through)
│      ├── JSeparator
│      ├── JLabel       "— Auto-discovered (unverified) —"   ← non-selectable
│      ├── ModelMenuItem  ⚪ ✓ ☆ claude-3-7-experimental
│      ├── JSeparator
│      └── JMenuItem    "✎ Add/edit credentials…"            ← always last leaf
├── ProviderMenu : JMenu  "OpenAI ⚠"                 ← unconfigured
│      ├── (no curated rows — provider has no key)
│      └── JMenuItem    "✎ Add credentials…"
├── ProviderMenu : JMenu  "Ollama (local) ✓"
│      ├── ModelMenuItem  🟢 ✓ ☆ Llama 3.2 3B
│      ├── ModelMenuItem  🟢 ✓ ☆ Qwen 2.5 7B
│      ├── JSeparator
│      └── JMenuItem    "↻ Refresh local Ollama list"
├── … (one ProviderMenu per provider in 02 §6 — 15 total) …
├── JSeparator
└── JMenuItem  "⚙  Open multi-provider settings…"     ← shortcut to §9 settings tab
```

`ModelMenuItem` is a custom `JMenuItem` subclass carrying five visual elements in a fixed-width row: tier badge | status icon | pin star | display name | provider/right-aligned hint. Renderer per §3.

### 2.3 Selection semantics

A "selection" is `(provider_id, model_id)` — written to settings, stamped onto the button caption. The popup is stateless: clicking a row launches and dismisses the popup. No "save and close" — that pattern fits a `JComboBox` editor, not a launch surface.

If the user wants to *change* selection without launching (e.g. pre-pick for a later session), they right-click → "Make default without launching" (§3.5). The default-without-launching path is intentionally hidden behind right-click so the primary affordance stays one-click-launch.

### 2.4 Why `JPopupMenu` (and not `JComboBox` with custom renderer)

`JComboBox` cannot host cascading submenus. Forcing it to flatten 80 models into one scrollable list defeats the grouping argument from `01`/`02`. `JPopupMenu` + nested `JMenu` is the only Swing-native way to get hover-to-expand provider groups without writing a popup framework from scratch. Every Swing IDE that does hierarchical pickers (NetBeans, Eclipse) uses `JMenu` cascading.

The cost is paid in **rendering hooks** — `JMenuItem` renders its own background/icon/text/accelerator slot, and we override most of that to fit five elements on one row. §3.2 specifies the override.

---

## 3. Swing-specific implementation notes

Checklist of pitfalls collected against the Oracle Swing tutorial, OpenJDK 25 javadoc for `javax.swing.JMenu` / `MenuSelectionManager`, and the Fiji L&F notes in `AiRootPanel` (which already overrides background/font defaults — line 50, `BG_MAIN = new Color(30, 30, 35)`).

### 3.1 Cascading hover behaviour — confirmed

`JMenu` opens its submenu on hover by default; delay is governed by `MenuSelectionManager`'s internal timer (~250 ms). No custom code needed — the popup tree wires arrow keys, hover-expand, and child-of-parent dismissal correctly.

What we **do not** get for free:
- Hover *while a submenu is open* on a sibling provider does not auto-switch expansion. Default Swing is fine — user clicks the new provider header to switch.
- Long submenus (Ollama Cloud may have 30+ entries) get default scroll arrows top/bottom. Acceptable; document.

### 3.2 Custom `JMenuItem` rendering — `paintComponent`, not a renderer

`JMenuItem` does **not** use `ListCellRenderer`. Either:

- (a) Subclass `JMenuItem`, override `paintComponent(Graphics)`, draw the five elements with `Graphics2D`. **Recommended.**
- (b) Install custom `MenuItemUI` via `UIManager`. Heavyweight; collides with Fiji L&F.

(a) gives predictable widths per element. 16 px badge column, 14 px status-icon, 14 px pin-star, then text, then right-aligned provider label. `paintComponent` lets us position absolutely without dragging in a layout manager.

```java
public class ModelMenuItem extends JMenuItem {
    private static final int COL_BADGE  = 6;
    private static final int COL_STATUS = 26;
    private static final int COL_STAR   = 44;
    private static final int COL_TEXT   = 64;
    private static final int ROW_HEIGHT = 24;

    private final ModelEntry entry;            // tier, status, pinned, display_name, provider
    private boolean starHovered;

    public ModelMenuItem(ModelEntry e) {
        this.entry = e;
        setPreferredSize(new Dimension(380, ROW_HEIGHT));
        setOpaque(true);
        addMouseMotionListener(starHoverDetector());
        addMouseListener(starClickHandler());   // §3.4
        setToolTipText(null);                   // hover-card handled by HoverCard, not Swing tooltip — §5
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // background — respect arm/select highlight
        g2.setColor(getModel().isArmed() ? UIManager.getColor("MenuItem.selectionBackground")
                                          : getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());

        TierBadgeIcon.forTier(entry.tier).paintIcon(this, g2, COL_BADGE, 4);
        StatusIcon.forStatus(entry.status).paintIcon(this, g2, COL_STATUS, 4);
        PinStarIcon.forState(entry.pinned, starHovered).paintIcon(this, g2, COL_STAR, 4);
        g2.setFont(getFont());
        g2.setColor(textColorFor(entry));
        g2.drawString(entry.displayName, COL_TEXT, 16);
        FontMetrics fm = g2.getFontMetrics();
        String hint = entry.providerLabel();
        g2.setColor(new Color(140, 140, 150));
        g2.drawString(hint, getWidth() - fm.stringWidth(hint) - 8, 16);

        g2.dispose();
    }
}
```

`TierBadgeIcon`, `StatusIcon`, `PinStarIcon` are tiny `Icon` impls drawing filled discs/glyphs. Reused in the hover-card header.

### 3.3 Tooltip per row — but not via `setToolTipText`

`JMenuItem.setToolTipText(html)` works but: (a) default tooltip delay/dismissal can't be tuned per item; (b) `ToolTipManager` causes flicker between sibling rows (each item triggers a fresh show/hide cycle).

Use a **single shared `HoverCard` JWindow** (§5), driven by `MouseEntered`/`MouseExited` on `ModelMenuItem`. The `HoverCard` is a singleton owned by `ModelPickerButton`; shows/hides as a unit.

### 3.4 Click-to-pin without dismissing the popup

The headline Swing trick. The default `JMenuItem` consumes `MOUSE_CLICKED` by:
1. Firing `ActionPerformed` (our handler interprets as "launch").
2. Calling `MenuSelectionManager.defaultManager().clearSelectedPath()` — **closes the popup**.

We want clicking the **star column** to toggle pin and *not* close; clicking the **name** to launch and *do* close. Solution: install a `MouseListener` that intercepts `mousePressed` *before* the default handler runs:

```java
private MouseListener starClickHandler() {
    return new MouseAdapter() {
        @Override public void mousePressed(MouseEvent e) {
            if (e.getX() >= COL_STAR && e.getX() < COL_STAR + 14) {
                togglePin();
                repaint();
                e.consume();           // suppresses ActionPerformed + popup-close
            }
            // any other x → fall through, normal launch path runs
        }
    };
}
```

`MouseEvent.consume()` works because `JMenuItem` listens on `MouseListener` *after* user-installed listeners. Same pattern Eclipse uses for "pin tab" affordances on tab close handles.

### 3.5 Right-click context menu

`MOUSE_PRESSED` with `isPopupTrigger()` true → small `JPopupMenu` anchored to cursor:

- Pin / Unpin
- Make default (no launch)
- Set this as launch target for ▶ (re-launch-last alias)
- Refresh provider's `/models`
- Suggest curation… (only for ⚪; opens GitHub issue URL)
- Hide this model (writes `hidden: true` in `models_local.yaml` per `02 §4`)

The right-click menu shadows the main popup but does not close it — when it dismisses, focus returns to the main popup with the original row armed.

### 3.6 Keyboard navigation — default behaviour is enough

`JMenu`/`JMenuItem` already wires ↑/↓ within submenu, → into submenu, ← out, Enter to fire ActionPerformed (launch), Esc to dismiss.

We **add**:
- Space — toggle pin on focused row (mirrors star click). Wired with `KeyStroke` on each `ModelMenuItem`'s input map.
- Letter keys — Swing's mnemonic system already does prefix-jump within a `JMenu` if items have unique first letters. Most providers do; leave on.

### 3.7 Look-and-feel concerns with Fiji

Fiji uses OS-default L&F. On Windows that is `WindowsLookAndFeel`; on macOS, Aqua. The dark `BG_MAIN = (30, 30, 35)` of `AiRootPanel` is applied via `setBackground` on individual components; the popup tree does **not** inherit it. So the popup renders *light* on a dark-themed parent — visually jarring but consistent with every other Swing menu in Fiji (File → Open, etc.).

**Recommendation: do not theme the popup.** Forcing a dark menu via `UIManager.put` only breaks other panels. Keep popup OS-default; parent panel stays dark. Matches existing `JComboBox` behaviour (line 209) which also drops to OS-default dropdown styling.

Icons drawn in `paintComponent` must respect both light and dark palettes — test against `UIManager.getColor("MenuItem.background")` luminance and pick a contrasting glyph colour.

### 3.8 EDT discipline

Every popup-mutating call (rebuild after refresh, pin toggle persisted to disk) must run on EDT. `/models` calls are off-EDT in a `SwingWorker` — same pattern as `refreshAgentSelectorAsync` at AiRootPanel.java:311–337. The new `ProviderRegistry.refreshAsync()` mirrors that worker; `done()` calls `rebuildPopup()` on EDT.

### 3.9 Memory: do not rebuild the popup unless data changes

`JPopupMenu` allocates 1 `JMenu` per provider + 1 `JMenuItem` per model. At 80 models × cascading children, ~120 components. Cheap, but rebuilding on every `setVisible(true)` causes flicker + re-layout. Build once at startup; edit in place when:
- A pin toggles (single `repaint()` on affected `ModelMenuItem`).
- A provider's `/models` refresh returns new entries (rebuild that one `ProviderMenu`'s children, not the whole popup).
- Tier-change banners arrive (refresh affected entries' badges; `06 §7`).

---

## 4. Visual states (with ASCII mockups)

### 4.1 Element grammar

Every `ModelMenuItem` row renders five elements left-to-right:

```
[BADGE] [STATUS] [STAR] [DISPLAY NAME] ………………… [PROVIDER HINT]
   2 ch    1 ch    1 ch    flex                         right-aligned
```

| Slot | Glyphs | Source |
|------|--------|--------|
| BADGE  | 🟢 🟡 🔵 🟣 ⚪ | `06 §1.1` (tier from `models.yaml`) |
| STATUS | ✓ ⚠ ✗ | `06 §4` (live, per-provider) |
| STAR   | ★ filled / ☆ hollow | `02 §4` (user pin state) |
| NAME   | curated `display_name`, raw `model_id` for ⚪, struck-through for soft-deprecated | `02 §1` |
| HINT   | provider short-name | derived from `provider` |

### 4.2 Per-element states

**BADGE** (one of five, from `06 §1.1`): 🟢 Free · 🟡 Free with limits · 🔵 Pay-as-you-go · 🟣 Subscription · ⚪ Uncurated.

**STATUS** (one of three, from `06 §4`): ✓ Ready (configured, recent successful auth) · ⚠ Needs setup (no credential) · ✗ Unavailable (credential present but errored).

**STAR** (one of two): ★ Pinned (filled, gold outline) · ☆ Unpinned (hollow grey).

**NAME** (one of three styles):
- **Active** — normal weight, default colour.
- **Soft-deprecated (≤30 days)** — yellow-amber, struck-through, subtitle `"no longer available since YYYY-MM-DD — try {replacement}"`.
- **Pinned-deprecated (>30 days, kept by user pin)** — grey, struck-through, badge swapped to RETIRED red dot, subtitle `"calls will fail"`.

### 4.3 Cross-product matrix (representative)

Every row is a real possibility the renderer must handle:

```
  ROW                                                         BADGE STATUS STAR NAME-STYLE
────────────────────────────────────────────────────────────  ───── ────── ──── ─────────────
  🟢 ✓ ☆  Llama 3.2 3B               Ollama (local)            🟢    ✓     ☆    active
  🟢 ✓ ★  Llama 3.2 3B               Ollama (local)            🟢    ✓     ★    active
  🟡 ✓ ☆  Llama 3.3 70B Versatile    Groq                      🟡    ✓     ☆    active
  🔵 ✓ ★  Claude Sonnet 4.6          Anthropic                 🔵    ✓     ★    active
  🔵 ⚠ ☆  Claude Opus 4.7            Anthropic                 🔵    ⚠     ☆    active           ← needs setup
  🔵 ✗ ☆  Grok 4.1 Fast              xAI                       🔵    ✗     ☆    active           ← errored
  🟣 ⚠ ☆  Claude (Pro chat route)    Anthropic Pro             🟣    ⚠     ☆    active
  ⚪ ✓ ☆  llama-3.2-90b-text-preview Groq                      ⚪    ✓     ☆    active           ← uncurated
  ⚪ ✓ ★  moonshotai/kimi-vl-…       OpenRouter                ⚪    ✓     ★    active           ← uncurated + pinned
  🔵 ✓ ☆  Claude Haiku 3.5 ̶         Anthropic                 🔵    ✓     ☆    soft-deprecated
  🔵 ✓ ★  gemma4:31b-cloud ̶        Ollama Cloud              red   ✓     ★    pinned-deprecated  ← user kept pin
```

Note bottom row: even retired, the user's `★` pin forces it visible. Badge upgraded to red `RETIRED` per `02 §3` lifecycle.

### 4.4 Full dropdown snapshot

A representative open popup, two providers expanded. User has hovered Anthropic and OpenAI — Anthropic fully configured, OpenAI needs setup.

```
┌─────────────────────────── Models ──────────────────────────┐
│  last updated 3 min ago                          [ ↻ refresh ]│
├──────────────────────────────────────────────────────────────┤
│  ★ Pinned                                                    │
│      🔵 ✓ ★  Claude Sonnet 4.6     Anthropic                 │
│      🟡 ✓ ★  Llama 3.3 70B         Groq                      │
├──────────────────────────────────────────────────────────────┤
│  ▸ Ollama (local)            ✓                               │
│  ▾ Anthropic                 ✓                               │
│      🔵 ✓ ★  Claude Sonnet 4.6     Anthropic                 │
│      🔵 ✓ ☆  Claude Opus 4.7       Anthropic                 │
│      🔵 ✓ ☆  Claude Haiku 4.5      Anthropic                 │
│      🔵 ✓ ☆  Claude Haiku 3.5 ̶    Anthropic                 │
│           no longer available since 2026-04-20 — try Haiku 4.5│
│      ──────  Auto-discovered (unverified) ──────             │
│      ⚪ ✓ ☆  claude-3-7-experimental Anthropic               │
│      ─────────────────────────────────────                   │
│      ✎ Add/edit credentials…                                 │
│  ▾ OpenAI                    ⚠   needs setup                 │
│      ✎ Add credentials…                                      │
│  ▸ Groq                      ✓                               │
│  ▸ Cerebras                  ✓                               │
│  ▸ Gemini                    ✓                               │
│  ▸ OpenRouter                ✓                               │
│  ▸ GitHub Models             ✓                               │
│  ▸ Mistral                   ⚠                               │
│  ▸ Together AI               ⚠                               │
│  ▸ HuggingFace               ⚠                               │
│  ▸ DeepSeek                  ⚠                               │
│  ▸ xAI                       ✗   could not reach api.x.ai    │
│  ▸ Perplexity                ⚠                               │
│  ▸ Ollama Cloud              ✓                               │
├──────────────────────────────────────────────────────────────┤
│  ⚙  Open multi-provider settings…                            │
└──────────────────────────────────────────────────────────────┘
```

The pinned section includes models from any provider — `Llama 3.3 70B` from Groq, `Claude Sonnet 4.6` from Anthropic, sorted alphabetically by display name within the pinned bucket. A user with no pins sees no pinned section (and no leading separator).

`Anthropic` shows: three active rows, one soft-deprecated row with replacement subtitle, an "Auto-discovered" subsection with one ⚪ row, and `Add/edit credentials…` leaf.

`OpenAI` has no curated rows shown (no credential — auto-discovery skipped, curated entries can't be exercised). Cleaner UX is to make the user fix credential first.

`xAI` is configured but errored — header shows ✗ and short reason. Clicking opens §7 error dialog rather than expanding.

---

## 5. Hover-detail card integration

### 5.1 Recommendation: custom `JWindow`, not `JToolTip`

`JToolTip` (with HTML body) loses on three points:

1. **Width control.** `JToolTip` autosizes from HTML; pricing rows can break across two lines on narrow JVMs. Card needs fixed 440 px (per `06 §2.4`) regardless of L&F.
2. **Flicker on rapid traversal.** `ToolTipManager` re-runs show/hide timers per item; arrowing down a 12-item submenu produces 12 mount/unmount cycles.
3. **Anchoring.** `JToolTip` anchors near cursor; we want anchored to **right edge of popup**, fixed regardless of where in the row the cursor sits.

`JWindow` is the right primitive — borderless, undecorated top-level window that does not steal focus. Instantiate one on first hover, reuse for the popup's lifetime; mouse-enter on a different row swaps content but keeps the window mounted (no flicker).

### 5.2 Lifecycle

1. `ModelPickerButton.showPopup()` creates the popup; popup also creates a `HoverCard` instance (private `JWindow`) with `setVisible(false)`.
2. `ModelMenuItem.mouseEntered` schedules a `Timer` for **400 ms** (matches `06 §2`). If cursor stays for the full 400 ms:
   - Computes anchor: `popup.getLocationOnScreen().x + popup.getWidth() + 8`, `row.getLocationOnScreen().y`.
   - `card.setContent(modelEntry)` (rebuilds labels in place).
   - `card.setLocation(x, y)`, `card.setVisible(true)`.
3. `mouseExited` cancels pending timer; if card already visible, a **120 ms grace timer** hides it. Grace prevents sibling-traversal flicker — moving from row A to row B reuses the window (A's grace cancelled by B's `mouseEntered`).
4. Popup's own `popupMenuWillBecomeInvisible` hides the card and disposes it.

### 5.3 Anti-flicker rules

- **One `JWindow` per popup show.** Sibling traversal swaps content, doesn't reopen.
- **400 ms show delay** matches `06 §2`. Shorter feels jumpy on arrow-keying.
- **120 ms grace on hide** — once inside the card, `mouseEntered(card)` cancels the hide.
- **Card is read-only** (per `06 §2`); cursor-into-card is purely for inspection.

### 5.4 Anchoring vs cursor

Anchored to **right edge of popup**, top-aligned to row. Reasons:

- Card never overlaps the popup, so the user can keep clicking rows.
- Card never moves vertically while moving between siblings — stable enough to scan with peripheral vision.
- Keyboard nav (↓/↑) updates the card just like mouse hover — timer fires on `caretChanged` from `MenuSelectionManager`; card jumps to align with new row.

Edge case: at right edge of screen, the card flips to the **left** (anchor `popup.x - card.width - 8`). Standard Swing tooltip flip logic.

### 5.5 Interaction with first-use dialog

When the user clicks a 🔵/🟣 row and §6 dialog opens, the hover card is hidden first (it's a child of the popup, not the dialog) so it doesn't float behind a modal. Re-shown if the user cancels and the popup re-arms.

### 5.6 Accessibility

The card sets `AccessibleContext.setAccessibleName` to the `aria-label`-style summary string from `06 §2.4`. JAWS / NVDA read it on hover. The popup itself uses Swing's built-in menu accessibility.

---

## 6. First-use-of-paid-model dialog integration

### 6.1 Where it fires in the launch flow

```
mousePressed (not on star)
   ↓
ModelMenuItem.fireActionPerformed
   ↓
ModelPickerButton.handleSelection(entry)
   ├── if status == ⚠  → §7.1 (open installer panel)
   ├── if status == ✗  → §7.2 (open error dialog)
   └── if status == ✓
         ↓
       ProviderTierGate.requireConfirmation(entry)
         ├── if entry.tier in {🔵, 🟣}
         │     and provider not in firstUseDialog.dontAskAgain
         │     → show FirstUseDialog (modal)
         │       ├── Continue → proceed
         │       └── Use a free model instead → close dialog, refilter popup, abort
         └── else → proceed
         ↓
       ProviderTierGate.checkUncurated(entry)
         ├── if entry.curated == false
         │     and provider not in firstUseUncuratedDialog.dontAskAgain
         │     → show UncuratedDialog (modal)
         │       (stacks AFTER the paid dialog if both apply, per 06 §5.3)
         └── else → proceed
         ↓
       Settings.setSelectedModel(entry.providerId, entry.modelId)
         ↓
       PopupMenu.setVisible(false)
         ↓
       AgentLaunchOrchestrator.launchAsync(entry)
         ├── chooses transport: LiteLLM proxy / native Anthropic / native Gemini / spawn-CLI
         ├── opens terminal (Mode.EMBEDDED unless settings.agentEmbeddedTerminal == false)
         └── delegates to existing AgentLauncher.launch(...) at line 135 OR new ProxyAgentLauncher
```

The dialog fires **before** any process spawn or HTTP request. `AgentLauncher.launch()` at line 135 is never called if the user clicks "Use a free model instead". This is the cost-safety guarantee from `06 §3.1`.

### 6.2 Modality

**Modal** — `JDialog` with `setModal(true)`, parent is `AiRootPanel`'s top-level `JFrame` (line 65). The user cannot accidentally background and forget about it; the dropdown is hidden behind the modal; user must commit one way or other. Stack of paid → uncurated → launch is linear.

Use `Dialog.ModalityType.DOCUMENT_MODAL` (not application-modal) — Fiji's image windows must remain interactive.

### 6.3 "Don't ask again" persistence

Per `06 §3.4`: **per-(provider × session)**, in-memory only. The `Set<ProviderId>` lives on a singleton `ProviderTierGate` (or static field on `ProviderRegistry`). On Fiji process restart, the set is empty.

`Settings.tier_safety.confirm_paid_models` (bool, default true) is a separate global toggle a power-user can flip. The in-session don't-ask-again is **not** persisted to `settings.json`.

### 6.4 User cancel behaviour

The cancel button reads "**Use a free model instead**":
1. Closes the dialog.
2. Re-opens the popup, **filtered** to 🟢 and 🟡 rows only (transient flag `showOnlyFreeTier = true` cleared the next time the popup is opened from the button).
3. Does **not** launch.

`[×]` is equivalent — closes dialog, re-opens popup, no launch. Matches `06 §3.2` ("does not silently launch").

If the user explicitly closes the popup (Esc or click-elsewhere) instead of picking a free model, the launch is abandoned. No error toast — they chose to back out.

### 6.5 First-use-of-uncurated dialog — same scaffolding

Identical modality, parent, persistence as 6.1–6.4, but cancel re-opens the popup filtered to **curated** rows only (drops both `curated:false` and `tier == ⚪`). Stacking when both apply: paid first, then uncurated, per `06 §5.3` ("we do not merge them").

---

## 7. Status-icon click behaviour

The status-icon column is interactive — clicks short-circuit the normal launch path. Distinguish from row-click via the `mousePressed` x-coordinate test from §3.4:

```java
@Override public void mousePressed(MouseEvent e) {
    if (e.getX() >= COL_STATUS && e.getX() < COL_STATUS + 14) {
        handleStatusClick(entry); e.consume(); return;
    }
    if (e.getX() >= COL_STAR && e.getX() < COL_STAR + 14) {
        togglePin(); e.consume(); return;
    }
    // else fall through to launch via fireActionPerformed
}
```

### 7.1 ⚠ Needs setup → installer panel

Per `06 §4.3`:

1. Record `pendingLaunch = entry` on `ProviderRegistry`.
2. Close the popup (`MenuSelectionManager.clearSelectedPath()`).
3. Open existing **Settings → Models & Agents** tab (`SettingsDialog.java:166`) pre-targeted at the provider — implementation: a new `SettingsDialog.openWithProvider(providerId)`.
4. The installer's existing flow handles paste / sign-in / save.
5. On successful save: `ProviderRegistry.refreshProvider(providerId)`; if `pendingLaunch` set and new state ✓, §6 first-use dialog fires (this *is* the first paid use), and on Continue the originally-selected model launches.
6. On failure or skip: `pendingLaunch` cleared; popup re-opens.

The "one click from dropdown to installer panel" promise from `06 §4.3` is preserved — no intervening confirmation.

### 7.2 ✗ Unavailable → cached-error dialog

Per `06 §4.3`:

1. Open small modal `JDialog` with cached error from `ProviderRegistry.lastError(providerId)` (timestamp, HTTP status, message).
2. Three buttons: `Retry now`, `Reconfigure key`, `Pick another`.
3. `Retry now` → `ProviderRegistry.refreshProvider(providerId)` synchronously with a 4 s timeout. On success, dialog closes, popup re-arms, status icon flips to ✓. On failure, error message updates in place.
4. `Reconfigure key` → installer panel (same as ⚠ flow).
5. `Pick another` → close, return to popup.

### 7.3 ✓ Ready → no-op (clicks fall through to launch)

Clicking ✓ does nothing; the row's main click handler (launch) takes the action. The icon is interactive only for "broken" states. Matches every checkmark UI Apple ever shipped: green ticks confirm state, they don't open dialogs.

---

## 8. Refresh-button UX

### 8.1 Location

Header strip of `JPopupMenu`, anchored top-right (mirrors `06 §4.4`'s `[ refresh ]` link). Strip rendered as non-selectable JPanel pinned to top with `JLabel` left ("Models · last updated 3 min ago") and `JButton` right ("↻ refresh"). Clicking the button **does not close the popup** — same `MouseEvent.consume()` pattern as the pin star (§3.4).

### 8.2 Loading state

While refresh in flight:

```
┌─────────────────────────── Models ──────────────────────────┐
│  ⟳ fetching from 8 providers… (3 done)        [ cancel ]    │
├──────────────────────────────────────────────────────────────┤
```

The button slot becomes a `JLabel` with a small animated spinner. Provider rows below stay rendered from previous cache — **not greyed out**. The user can still click a row to launch from the stale list while a refresh runs in background.

### 8.3 Result toast

When `ProviderRegistry.refreshAll()` completes:

```
│  Models · 3 new, 1 removed since last check    [ ↻ refresh ]│
```

Lasts 6 seconds, then reverts to timestamp. Detailed one-time tooltip (clickable) reveals the actual diff — a floating panel listing each new/removed entry. Useful for users investigating tier-change banner (`06 §7`).

### 8.4 Failure UX

If one or more providers fail (timeout, 401, 5xx) — per `02 §5` "stale-but-present beats empty":

```
│  ⚠ Couldn't reach Anthropic — using cached list. [ retry ]   │
```

Strip stays in this state until either user clicks `[ retry ]` (re-runs only failed providers) or next full refresh succeeds.

If multiple providers failed, message reads `Couldn't reach 3 providers` and clicking opens an expandable sub-panel listing them. Cache **not** invalidated — popup keeps rendering whatever each provider's last successful fetch returned.

Per-provider timeout: **4 s** (matches `06 §4.4`).

### 8.5 Auto-refresh

Per `02 §5`: 24 h TTL, refresh on Fiji startup if stale, refresh on demand otherwise. The popup's own `popupMenuWillBecomeVisible` does **not** trigger a refresh — that would defeat the cache.

---

## 9. Settings-panel touchpoints

The current `SettingsDialog.java:163` has two tabs:
1. **Profiles** (line 81–161 — model config picker).
2. **Models & Agents** (line 166 — installer).

The new design **adds one new tab — Multi-Provider** — and re-uses the existing **Models & Agents** tab as the installer surface for individual provider credentials. Existing tabs are not redesigned.

### 9.1 New "Multi-Provider" tab

```
┌──────────────────────── Multi-Provider ────────────────────────┐
│                                                                │
│  Defaults                                                      │
│    Default provider:    [ Anthropic            ▾ ]             │
│    Default model:       [ Claude Sonnet 4.6    ▾ ]             │
│       (used by re-launch-last when no last-launched record)    │
│                                                                │
│  Refresh                                                       │
│    Refresh interval:    [ 24 h  ▾ ]   ☑ Refresh on startup     │
│                                                                │
│  Pinned models                                                 │
│    ┌──────────────────────────────────────────────────────┐    │
│    │ ★ Claude Sonnet 4.6        Anthropic       [unpin]   │    │
│    │ ★ Llama 3.3 70B Versatile  Groq            [unpin]   │    │
│    │ ★ moonshotai/kimi-vl-...   OpenRouter      [unpin]   │    │
│    └──────────────────────────────────────────────────────┘    │
│    ☐ Show pinned section as default-on-launch in dropdown       │
│                                                                │
│  Hidden models                                                 │
│    [3 models hidden]                          [ Manage… ]      │
│                                                                │
│  Tier safety                                                   │
│    ☑ Confirm before first use of a paid model in each session  │
│    ☑ Warn before first use of an unverified model              │
│    ☐ Pause the agent if a session exceeds  [ $1.00  ▾ ]        │
│      (free models are not counted)                             │
│                                                                │
│  Currency footnote                                             │
│    ☑ Show "Charged in USD" footnote when locale ≠ en-US        │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 9.2 Existing "Models & Agents" tab — extended

Per-provider credential rows added at `SettingsDialog.java:166`:

| Provider | Field | Source for new dropdown |
|----------|-------|-------------------------|
| Anthropic | API key (paste) | LiteLLM proxy config + `~/.imagejai/state.json` for "Ready" check |
| OpenAI | API key | same |
| Gemini | API key (paste) or browser sign-in (line 217) | same |
| Groq | API key | same |
| Cerebras | API key | same |
| OpenRouter | API key | same |
| GitHub Models | PAT (`models:read` scope) | same |
| Mistral | API key | same |
| Together AI | API key | same |
| HuggingFace | HF token | same |
| DeepSeek | API key | same |
| xAI | API key | same |
| Perplexity | API key | same |
| Ollama (local) | Daemon URL (default `http://localhost:11434`) | Ollama-specific |
| Ollama Cloud | Account sign-in (browser) | uses existing `gemma4_31b_agent` flow |

Each row's standard layout from `06 §4.3`: provider icon + name; status icon (✓/⚠/✗); last refreshed timestamp; `[ Save ]`/`[ Sign in ]`; test result line. Destination for §7.1 ⚠-click flow.

### 9.3 Existing "Profiles" tab — unchanged

The Profiles tab (line 81) lets the user save **named configs** (provider + model + system prompt). Coexists with the new dropdown — dropdown picks a model for the current session, Profiles saves a reusable bundle. A user can have a "Histology pipeline" profile pinned to Claude Sonnet 4.6 and an "exploratory chat" profile pinned to Gemini Flash.

The Profiles `providerCombo` at line 135 is widened to all 15 providers from §9.2 (currently four: `gemini`, `ollama`, `openai`, `custom`). Existing entries keep meanings for backward compat; `custom` becomes a fallback for non-LiteLLM endpoints.

### 9.4 Settings schema additions

The current `Settings.java` (~275 LOC):

```java
public class Settings {
    // ... existing fields ...

    // === Multi-provider (new) =========================================
    public String selectedProvider;            // e.g. "anthropic"
    public String selectedModelId;             // e.g. "claude-sonnet-4-6"

    public String defaultProvider;             // for re-launch-last
    public String defaultModelId;

    public int    refreshIntervalHours = 24;
    public boolean refreshOnStartup    = true;

    public boolean confirmPaidModels       = true;
    public boolean warnUncuratedModels     = true;
    public boolean budgetCeilingEnabled    = false;
    public double  budgetCeilingUsd        = 1.00;
    public boolean showCurrencyFootnote    = true;

    // Legacy field stays — read by AiRootPanel.java:218 — but is now a
    // *display alias* containing "<provider>:<model_id>" for new entries
    // and the legacy bare agent name for old entries. See §10.
    public String selectedAgentName;
}
```

Pinned/hidden lists are **not** in `settings.json` — they live in `models_local.yaml` per `02 §4`. The Multi-Provider tab reads `models_local.yaml` directly via the same `ProviderRegistry`.

---

## 10. Migration from current `AgentLauncher` / `AiRootPanel`

### 10.1 What replaces the `JComboBox<String>` (line 63 / 209)

```java
// Replaces: private JComboBox<String> agentSelector;     (line 63)
private ModelPickerButton modelPicker;
```

Construction (replaces lines 209–212):

```java
modelPicker = new ModelPickerButton(providerRegistry, settings);
modelPicker.setPreferredSize(new Dimension(220, 22));   // a touch wider than 140
modelPicker.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
```

### 10.2 What replaces the `actionListener` (lines 213–223)

```java
modelPicker.addSelectionListener(new ModelPickerButton.SelectionListener() {
    @Override public void onSelectionChanged(ModelEntry entry) {
        settings.selectedProvider = entry.providerId;
        settings.selectedModelId  = entry.modelId;
        settings.selectedAgentName = entry.providerId + ":" + entry.modelId;  // legacy alias
        settings.save();
        chatView.refreshInputState();
        updateLaunchButtonState();
    }

    @Override public void onLaunchRequested(ModelEntry entry) {
        launchModelAsync(entry);    // new replacement for launchSelectedAgentAsync()
    }
});
```

`onSelectionChanged` fires *only* on right-click → "Make default" (no launch). `onLaunchRequested` is the new path for click-to-launch.

### 10.3 What happens to the standalone ▶ button (line 257–265)

Repurposed to **re-launch last** (user decision; the safe default).

```java
// agentBtn caption: "▶ re-run last"
// tooltip: "Re-launch <last model name> without re-opening the picker"
agentBtn.addActionListener(e -> {
    String prov = settings.selectedProvider;
    String mid  = settings.selectedModelId;
    if (prov == null || mid == null) {
        modelPicker.showPopup();   // first launch — make them pick
        return;
    }
    ModelEntry entry = providerRegistry.lookup(prov, mid);
    if (entry == null) {
        modelPicker.showPopup();   // selection fell through (provider removed)
        return;
    }
    launchModelAsync(entry);       // bypass picker, fire dialogs and launch
});
```

The button's enabled state (`updateLaunchButtonState`, lines 373–384) becomes: "enabled iff `settings.selectedProvider/selectedModelId` resolves to a `ModelEntry` with status ✓". Disabled tooltip: "Pick a model from the dropdown first."

This preserves muscle memory of users who use ▶ without re-opening the picker — common for repeated batch runs.

### 10.4 What happens to `detectAgents()` (`AgentLauncher.java:97–118`)

`detectAgents()` becomes the **CLI-spawn transport's discovery method**, one of three transports:

| Transport | Provider keys | Discovery |
|-----------|---------------|-----------|
| **CLI spawn** (existing) | `claude-code`, `aider`, `gemini-cli`, `codex-cli`, `cline`, `gh-copilot`, `interpreter`, `gemma4_31b_agent` | `AgentLauncher.detectAgents()` runs `where`/`which`; results become entries in synthetic provider `"cli"` |
| **LiteLLM proxy** | every provider with `default_tier:` listed in `06 §1.3` | `/models` endpoint per `02 §6` |
| **Native Anthropic / Gemini** | `anthropic`, `gemini` | LiteLLM proxy *and* native SDK; native preferred when caller wants caching/tool richness |

`KNOWN_AGENTS` at lines 62–72 is renamed to `CLI_AGENTS` and read by `ProviderRegistry` at startup as the seed list for the synthetic `"cli"` provider. Each `AgentInfo` becomes a `ModelEntry` with: `providerId = "cli"`; `modelId = AgentInfo.command` (e.g. `"claude"`, `"aider"`); `display_name = AgentInfo.name`; `tier = free` (CLI spawn doesn't bill you); `tool_call_reliability = high`.

`AgentLauncher.launch()` at line 135 is **not** removed. It is called by `ProviderRegistry` when `entry.providerId == "cli"`, exactly as today. Other providers route through `ProxyAgentLauncher` (LiteLLM) or `NativeAgentLauncher` (Anthropic/Gemini SDKs).

### 10.5 Settings schema bump

Per §9.4. Legacy `selectedAgentName` is **kept** for backward read compat — read once at first launch on the new build, used to seed `(provider, model_id)` if it matches a known CLI agent. After that, the new fields take over. We **do not** delete `selectedAgentName` until at least one release of co-existence has shipped.

```java
if (settings.selectedProvider == null && settings.selectedAgentName != null) {
    Map<String, String> legacyToNew = Map.of(
        "Claude Code",      "cli:claude",
        "Aider",            "cli:aider",
        "Gemini CLI",       "cli:gemini",
        "Codex CLI",        "cli:codex",
        // ...
        "Local Assistant",  "cli:gemma4_31b_agent"  // closest fit
    );
    String mapped = legacyToNew.get(settings.selectedAgentName);
    if (mapped != null) {
        String[] parts = mapped.split(":", 2);
        settings.selectedProvider = parts[0];
        settings.selectedModelId  = parts[1];
        settings.save();
    }
}
```

### 10.6 Atomic vs incremental migration

**Incremental.** The new `ModelPickerButton` is built behind `Settings.useMultiProviderPicker` (default `false` for one release, `true` the next). Both paths read from the same `Settings`; new fields populated on first picker launch.

The flag retirement gate is **two weeks of lab build without regressions** (per `07 §1`). After that flip, the old code is deleted in one commit: `JComboBox<String> agentSelector` field, `refreshAgentSelector*` methods (lines 311–371), `findDetectedAgent` helper (line 386).

Reasoning:
- The new picker depends on (a) the LiteLLM proxy daemon, (b) `models.yaml` in the JAR, (c) new credential UI in Settings → Models & Agents. Any slipping shouldn't block the rest.
- A feature flag lets us QA the new path in the lab build before exposing it externally.
- One flag flip removes the old code in the next release — no long-lived branch.

---

## 11. Java-side files to create / modify

### 11.1 New classes

All under `imagejai.ui.picker` (new sub-package) unless noted.

| Class | Purpose |
|-------|---------|
| `ModelEntry` | Immutable VO: `(providerId, modelId, displayName, tier, status, pinned, curated, vision, contextLength, deprecation, replacement, notes)`. `equals`/`hashCode` keys: `(providerId, modelId)`. |
| `ProviderEntry` | Immutable VO: `(providerId, displayName, status, lastError, lastRefreshed, hasCredential, defaultTier)`. |
| `ProviderRegistry` | Singleton service that loads `models.yaml`, `models_local.yaml`, runs `/models` discovery per `02 §6`, applies merge from `02 §2`, exposes `Iterable<ProviderEntry>` and `lookup(provider, modelId)`. Holds cache from `02 §5`. |
| `ProviderRegistry.RefreshWorker` | `SwingWorker` running `/models` calls in parallel with 4 s timeout (per `06 §4.4`); EDT-safe. |
| `ModelPickerButton` | The header button (replaces `JComboBox<String> agentSelector`). Owns the `JPopupMenu` and `HoverCard`. Exposes `addSelectionListener`, `showPopup`, `setSelectedEntry`. |
| `ModelMenuItem` | `JMenuItem` subclass with custom `paintComponent` rendering five elements (badge, status, star, name, hint). §3.2. |
| `ProviderMenu` | `JMenu` subclass holding curated rows + auto-discovered subsection + "Add credentials…" leaf. |
| `HoverCard` | Borderless, undecorated `JWindow` rendering `06 §2` card layout. Re-used across hovers; never re-instantiated within one popup show. |
| `TierBadgeIcon` | 12 px disc for one of {🟢, 🟡, 🔵, 🟣, ⚪}. Reused in menu, hover-card header, first-use dialog. |
| `StatusIcon` | {✓, ⚠, ✗}. |
| `PinStarIcon` | filled / hollow. |
| `FirstUseDialog` | Modal `JDialog` with `06 §3.2` text. `showAndAwait()` returns `CONTINUE`/`PICK_FREE`/`CANCEL`. |
| `UncuratedDialog` | Modal `JDialog` with `06 §5.3` text. Same pattern. |
| `BillingFailureDialog` | Modal `JDialog` for `06 §3.7` (mid-session 402). |
| `TierChangeBanner` | Non-modal `JPanel` strip mounted above chat card; consumes `06 §7` events. |
| `BudgetCeilingDialog` | Inline overlay/dialog for `06 §6.3`. |
| `InstallerPanelOpener` | Bridge that opens `SettingsDialog` pre-targeted at one provider (powers §7.1). Implementation: `SettingsDialog.openWithProvider(Frame, String providerId)`. |
| `ProxyAgentLauncher` | New transport calling LiteLLM proxy at `localhost:4000`. Mirrors `AgentLauncher.launch()` shape over HTTP. |
| `NativeAgentLauncher` | New transport for `provider in {anthropic, gemini}` using native SDKs. |
| `ProviderTierGate` | Pure controller deciding whether to fire `FirstUseDialog`/`UncuratedDialog`. Holds the per-(provider × session) "don't ask again" set. |

`ModelEntry` and `ProviderEntry` belong under `imagejai.engine.picker` (engine-side) so the LiteLLM and native launchers can depend on them without pulling in Swing.

### 11.2 Existing files to modify

| File | Lines | Change |
|------|-------|--------|
| `AiRootPanel.java` | 63 | Replace `JComboBox<String> agentSelector` with `ModelPickerButton modelPicker`. |
| | 209–212 | Replace construction. |
| | 213–223 | Replace `actionListener` with `modelPicker.addSelectionListener(...)`. |
| | 224 | `leftPanel.add(modelPicker)`. |
| | 257–265 | Repurpose `agentBtn` to "re-launch last" (§10.3). |
| | 311–337 | `refreshAgentSelectorAsync` deleted (refresh moves to `ProviderRegistry.refreshAsync`). |
| | 339–371 | `refreshAgentSelector(List<AgentInfo>)` deleted (popup rebuild moves to `ModelPickerButton.rebuildPopup`). |
| | 373–384 | `updateLaunchButtonState` reduced to "enabled iff `selectedProvider/selectedModelId` resolves to status ✓". |
| | 386–396 | `findDetectedAgent` deleted (lookup moves to `ProviderRegistry.lookup`). |
| | 398–411 | `launchSelectedAgentAsync` replaced by `launchModelAsync(ModelEntry)`. |
| | 413–436 | `launchAgentAsync` becomes `launchModelAsync` — chooses transport (`cli` → `AgentLauncher.launch`, otherwise → `ProxyAgentLauncher` / `NativeAgentLauncher`). |
| `AgentLauncher.java` | 24 | `LOCAL_ASSISTANT_NAME` kept (still used by chat view). |
| | 62–72 | Rename `KNOWN_AGENTS` → `CLI_AGENTS` (post-Phase-D cleanup). |
| | 97–118 | `detectAgents` kept verbatim — feeds `ProviderRegistry` at startup as seed for synthetic `"cli"` provider. |
| | 135–159 | `launch(AgentInfo, Mode)` kept — called by `ProviderRegistry` when `entry.providerId == "cli"`. |
| `Settings.java` | end of class | Add eight fields from §9.4. Bump `Settings.SCHEMA_VERSION` if it exists; otherwise add a one-line `migrate()` invocation in `Settings.load()`. |
| `SettingsDialog.java` | 32 / 135 | Widen `providerCombo` to 15 providers (§9.3). |
| | 163–167 | Add third tab `"Multi-Provider"` per §9.1. |
| | end of class | Add `public static void openWithProvider(Frame parent, String providerId)` for §7.1 deep-link. |

### 11.3 New non-Java files

| Path | Purpose |
|------|---------|
| `agent/providers/models.yaml` | Curated descriptions per `02 §1`. Bundled in the JAR. |
| `agent/providers/audit_models.py` | Curator audit tool per `02 §7`. |
| `%APPDATA%/imagejai/models_local.yaml` | User pin/hide overrides per `02 §4`. Created on first pin. |
| `%APPDATA%/imagejai/cache/models/*.json` | Per-provider `/models` cache per `02 §5`. |
| `%APPDATA%/imagejai/state.json` | Favourites, dismissed banners, "first use of day" per `06 §7.2` and `06 §3.6`. |

### 11.4 Build / dependency changes

`pom.xml`:
- `org.snakeyaml:snakeyaml-engine` (or `org.yaml:snakeyaml` if version-compat) for reading `models.yaml`/`models_local.yaml` from Java. **Use SafeConstructor only** (CVE history).
- HTTP client for `/models`: `java.net.http.HttpClient` is JDK 25 stdlib, no Maven dep.

LiteLLM proxy runs as a Python sidecar started by an existing launcher pattern — see stage 7. No JVM-side dependency.

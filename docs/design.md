---
title: Design language
nav_order: 6
---

# Silo — Design Language

```
███████╗██╗██╗      ██████╗
██╔════╝██║██║     ██╔═══██╗
███████╗██║██║     ██║   ██║
╚════██║██║██║     ██║   ██║
███████║██║███████╗╚██████╔╝
╚══════╝╚═╝╚══════╝ ╚═════╝
                              READY.
```

This is the visual spec for the Silo admin SPA. Bold, fun, opinionated. The dashboard is a
terminal, not a Material design — read like a serial console, feel like an SRE's pet.

## Philosophy

- Silo is infrastructure. Infrastructure should look like infrastructure.
- No gradients. No shadows. No glassmorphism. No `border-radius > 0`. No easing curves.
- Influences: Norton Commander, `htop`, `k9s`, `cmatrix`, the WOPR terminal from *WarGames*.
- Every component should look at home rendered in a 24×80 VT100 emulator.

## Visual language — themes

Three themes, persisted in `localStorage` under `silo:theme`. Toggle in the about page.

### Phosphor Green (default)

```
--silo-bg:      #000000
--silo-fg:      #33ff33
--silo-dim:     #1a8c1a
--silo-accent:  #aaffaa
--silo-alert:   #ff5555
--silo-warn:    #ffaa00
--silo-good:    #33ff99
--silo-border:  #226622
--silo-cursor:  #aaffaa
```

### Amber CRT

```
--silo-bg:      #0a0500
--silo-fg:      #ffb000
--silo-dim:     #995c00
--silo-accent:  #ffd27f
--silo-alert:   #ff5555
--silo-warn:    #ffe066
--silo-good:    #ffb000
--silo-border:  #663300
--silo-cursor:  #ffd27f
```

### Paper Tape (light)

```
--silo-bg:      #f5f5dc
--silo-fg:      #1a1a1a
--silo-dim:     #5a5a5a
--silo-accent:  #006400
--silo-alert:   #b00020
--silo-warn:    #a05a00
--silo-good:    #2e6e00
--silo-border:  #999988
--silo-cursor:  #1a1a1a
```

All token pairs achieve **WCAG AAA contrast (≥ 7:1)** on their backgrounds. Verified at write time.

## Typography

- Display, body, code — **one** family: `JetBrains Mono` → `IBM Plex Mono` → `ui-monospace`.
- Shipped as WOFF2 in `/web/static/fonts/`. **No CDN.** No external font requests.
- Single weight: 400 regular. 700 bold is reserved for stat values and headings only.
- `line-height: 1.4` everywhere. No exceptions. Vertical rhythm > density.
- `font-variant-ligatures: none` everywhere. Code ligatures break ASCII alignment.

Type scale:

| Token | Size | Use |
|---|---|---|
| `--type-xs` | 0.75rem | timestamps, footers |
| `--type-sm` | 0.875rem | secondary labels |
| `--type-base` | 1rem | body |
| `--type-lg` | 1.25rem | table headings |
| `--type-xl` | 1.5rem | page headings |
| `--type-display` | 2.25rem | splash wordmark only |

## Spacing

Spacing is measured in `ch` units so it scales with the type.

| Token | Value |
|---|---|
| `--space-0` | 0 |
| `--space-1` | 0.5ch |
| `--space-2` | 1ch |
| `--space-3` | 2ch |
| `--space-4` | 4ch |
| `--space-5` | 8ch |

Page container: `max-width: 120ch`.

## Components

### Stat tile (the hero element)

```
+----------------+
| HITS           |
| 12,432         |
| +312 / 1h      |
+----------------+
```

API: `<StatTile label value delta intent="neutral|good|bad|warn" />`.

- Label uppercase, dim color.
- Value bold, accent.
- Delta dim. Sign prefixed (`+312`, `-12`).
- Hover: border → accent. **No animation.**

### Table

ASCII column separators (`│`). Header row underlined with `─`. **No background-color zebra.**
Active rows are marked with a leading `>`. Sort indicators: `[v]` desc, `[^]` asc. Selectable rows
show `[ ]` / `[x]`.

```
   key            size       last_access     status
   ──────────────────────────────────────────────────
   abc123…        12.4 MB    2h ago          [OK]
 > def456…        488 KB     14m ago         [OK]
   789ghi…        2.1 GB     yesterday       [...]
```

### Log viewer

- Monospace, `font-variant-ligatures: none`.
- Timestamps in left gutter (`--space-3`), dim.
- Levels colored: `INFO` dim, `WARN` warn, `ERROR` alert.
- Tail mode auto-scrolls with a blinking `█` cursor at the end.
- Virtualised scroller for ≥100k lines.

### Sparkline

Unicode block characters only: `▁▂▃▄▅▆▇█`. No SVG, no Canvas, no chart library in v0.1.

```
req/s ▁▂▃▅▆▇█▇▅▃▂▁  (peak 412)
```

60 chars wide = 60 data points. Render server-side or client-side — the string is the picture.

### Button

```
> [ APPLY ]    [ CANCEL ]
```

- Square brackets. Uppercase label. No border, no background.
- Primary distinguished from secondary by leading `>` prefix only.
- Hover: brackets → accent, label stays fg.
- Disabled: `[       ]` (label dimmed to bg-adjacent).

### Input

Underline only, no boxes:

```
URL: _____________________________
```

Focus shows a blinking `█` cursor at the end. Validation errors prefixed with `!`.

### Modal

Full ASCII box, centered. Title bar with `[ x ]` close in the top right. Backdrop: solid black at 80% alpha. **No blur.**

```
+─── ABOUT ───────────────────[ x ]─+
│                                   │
│ Silo v0.1.0 (sha 4f3a1c2)         │
│ Uptime: 17h 22m                   │
│ Apache-2.0                        │
│                                   │
+───────────────────────────────────+
```

### Status indicator

`[OK]` good, `[FAIL]` alert, `[...]` warn (3-dot cycle animation). **No emoji.** **No checkmarks/crosses.**

## Motion

- **No easing curves.** All transitions are `step-start` or `steps(N, end)`.
- Cursor blink: 1 Hz, on/off, no fade.
- Loading dots: `[ . ]` → `[ .. ]` → `[ ... ]` at 3 Hz.
- Scanline overlay (optional, off by default): horizontal repeating gradient, 2px on / 2px off, `mix-blend-mode: overlay`, 5% opacity. Toggle in settings.
- Page transitions: **none.** Hard cut. This is a terminal.
- `prefers-reduced-motion: reduce` disables cursor blink, loading dots, and scanlines.

## Pages

| Path | Purpose | Key components |
|---|---|---|
| `/` Dashboard | At-a-glance KPIs | 6 stat tiles, 1 sparkline, recent activity table |
| `/storage` | Disk usage, top-N entries, eviction queue depth | Table, gauge bar |
| `/auth` | Users, last-seen, role badges | Table, "add user" modal |
| `/errors` | Last 1000 4xx/5xx | Filtered log viewer |
| `/config` | Live HOCON view (read-only in v0.1) | Code block with "copy" button |
| `/about` | Version, uptime, SHA, license, links | Static panel, ASCII wordmark |

**Splash** (first paint): ASCII figlet `SILO`, version on the next line, `READY.` prompt, brief flash, then `/`.

## Accessibility

- All color pairs ≥ 7:1 contrast (AAA).
- Focus-visible is rendered as ASCII brackets around the focused element via `::before` `::after` content `'['` `']'` — keyboard navigation is **the** primary interaction model.
- Skip-to-content link at the top of every page.
- `aria-live="polite"` on stat tiles (announces deltas).
- `prefers-reduced-motion: reduce` disables blink, dots, scanlines.
- `?` opens the keyboard shortcut help modal.
- Minimum tap target 24px (desktop-first, but).

## Brand splash

ASCII wordmark, ANSI Shadow font, used on splash, about page, 404, and the project README:

```
███████╗██╗██╗      ██████╗
██╔════╝██║██║     ██╔═══██╗
███████╗██║██║     ██║   ██║
╚════██║██║██║     ██║   ██║
███████║██║███████╗╚██████╔╝
╚══════╝╚═╝╚══════╝ ╚═════╝
```

Favicon (16×16): silo silhouette in ASCII-style glyph `▟▙` rendered to a square PNG, phosphor green on transparent.

Social card (1200×630): black background, phosphor-green figlet wordmark centered, strapline below in dim green, version + license in small monospace at footer right.

## Implementation notes

- Tokens are CSS custom properties at `:root[data-theme="phosphor|amber|paper"]`.
- Kobweb exposes them as a `SiloTokens` object (`Modifier.color(SiloTokens.fg)` etc.).
- No external JS animation libraries.
- Theme switch persists in `localStorage`. Default = phosphor.
- All ASCII art is in a `<pre>` block with `font-family: var(--silo-font-mono)` — never an image.

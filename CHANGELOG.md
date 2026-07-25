# Changelog — 白い熊 地図

Everything built on top of stock OsmAnd (`upstream/master`). The version is
`<upstream base>+<fork build>`; the base commits track OsmAnd's development line.

## 5.4.0+14 — 2026-07-25

Base refreshed: OsmAnd `master` at `d328a7693c` (328 commits ahead of the previous base —
upstream's new spatial search engine and search-history filter chips, the widget-panel
appearance revamp, solar/lunar eclipse explorers in Star Map, Android 14+ MSL altitude
handling, and more). Includes the unpublished 5.4.0+10 – +13 builds.

### Export / Import (new)
- **Everything settable, one archive** — a new Export / Import section at the top of the
  白い熊 地図 UI page replaces the stock Settings export/import rows.
- **Backup directory** (SAF folder, device-local, never itself exported): chosen inside the
  panel; the UI page reports it read-only — red while unset, yellow with the folder name
  once set — and shows the newest export's timestamp, queried on opening the page.
- **Category panel**: Maps first with the stock subcategories (standard maps, road-only,
  wiki & travel, depth, terrain, map sources), **deselected by default**, each line and the
  Maps total gaining a live size count while the panel is open; then Settings / My places /
  Resources with every stock export type — profiles, global settings, quick actions, POI
  filters, avoid-roads, favorites, tracks, OSM notes/edits, A/V notes, markers and marker
  history, search and navigation history, itineraries, rendering styles, routing files,
  online routing engines, map sources, voices, color palettes.
- **白い熊 地図 UI category**: the theming page's colors, fonts, sizes — including imported
  font files — ride inside the standard `.osf` archive as a fork sidecar entry.
- **Export**: writes the stock `.osf` straight into the backup directory as
  `shiroikuma-chizu_<version>_export_<timestamp>.osf`; live horizontal MB bar plus an
  "Items: x/y" counter; a working Cancel aborts the task and the copy, removing partial
  files and leaving the panel open. Data collection runs off the UI thread.
- **Import**: pick an archive, the selected categories are restored with duplicates
  replaced silently; the finish dialog offers Later / Restart now.
- **Close chain**: export OK and import acknowledgement close the info dialog, the panel
  and the UI page in one go; failure messages toast and leave the panel open.

### UI & theming
- **kxkb-style UI page**: 20sp/17sp bold yellow headings with text-wide underlines, 1px
  hairline separators between sections, the deep 36/72/54/90 dp indent ladder.
- **Export/Import dialogs in the fork look**: black cards with a 2 dp yellow border;
  Arcanechat-style pill buttons (black fill, yellow outline and ripple) — Cancel separated
  left, Import and Export on the right.
- **What's-new dialog** (after an app update): black card with a yellow border, yellow buttons.
- **Yellow icon** for the 白い熊 地図 UI row in main Settings.

### Fixes & behavior
- **Drawer button long-press fires promptly**: an own touch-dispatch timer triggers the
  haptic and opens the UI page the moment the long-press timeout elapses — no more waiting
  for finger-lift — and suppresses the pending drawer click.

## 5.4.0+9 — 2026-07-17

Base unchanged (OsmAnd `master` at `52d8e08df1`).

### Android Auto
- **Launcher icon now full-bleed on car screens** — black disc, yellow pin, no white.
  Car hosts prefer the legacy square mipmap PNG when one exists and inset it with
  padding on a white circular plate; the fork now ships the launcher icon
  **adaptive-only** (the five legacy `mipmap-*/icon.png` removed, like
  shiroikuma-denwa), so every host masks the adaptive icon edge to edge. On
  API 24–25 devices the launcher would show a generic icon — none in use here.

## 5.4.0+8 — 2026-07-16

Base unchanged (OsmAnd `master` at `52d8e08df1`). Android Auto release; includes
the unpublished 5.4.0+7 changes.

### Android Auto
- **Launcher icon without the white rim**: `android:roundIcon` declared, so car hosts
  render the adaptive icon full-bleed instead of insetting the square legacy PNG on a
  white disc.
- **Yellow button glyphs**: every action on the navigation screen (list, compass, 2D/3D,
  settings, pan, my location, zoom in/out) is tinted with the theme yellow via the new
  `ChizuCar` helper; colors resolve through `ColorUtilities`, so the theming page's
  overrides reach the car screen. Button chrome stays host-drawn — the car API restricts
  action background colors to primary actions.
- **Themed car flashes**: messages such as "Position not yet known" show as navigation
  alerts on the host's dark card (with a `CarToast` fallback) instead of the host's
  black-on-white toast pill.

### Fixes & behavior
- **Fixed a template-validation crash loop in Android Auto** (introduced in the
  unpublished 5.4.0+7): action background colors on non-primary actions made
  `NavigationTemplate` validation throw on every car render.
- **OpenGL renderer self-heal**: the crash loop had pushed OsmAnd's `OPENGL_RENDER_FAILED`
  counter past the auto-disable threshold, silently switching the phone to the legacy
  renderer (no 2D/3D button, degraded map). On an app-version change with the counter past
  the threshold, the fork now resets it and re-enables the OpenGL renderer.

## 5.4.0+6 — 2026-07-14

Base: OsmAnd `master` at `52d8e08df1` (5.4.0 dev line, incl. the spatial-search
backend work and the POI multi-contact-value bottom sheet).

### Identity & packaging
- App id `shiroikuma.chizu`, label **白い熊 地図** — installs side-by-side with official
  OsmAnd; the code namespace stays `net.osmand.plus` for a minimal upstream diff.
- FileProvider authority derived from the application id (`${applicationId}.fileprovider`).
- Launcher icon: yellow `#FFFF00` edge-traced pin on black (adaptive icon + legacy mipmaps).
- Fork versioning: `versionName = <base>+N`, `versionCode = <baseCode>*10000+N` — upgrades
  stay monotonic across upstream bumps; `N` resets to 1 on each new upstream base.
- Release signing from a gitignored `keystore.properties`; `buildApk` gradle task assembles
  the signed `androidFullOpenglArm64Release`, copies the APK to `~/tmp/`, bumps the counter.
- 8 GB gradle heap so the OpenGL core AARs process reliably.

### UI & theming
- **Black-yellow rebrand as default**: dark theme by default; pure-black activity, card,
  list, widget, app-bar and map-button backgrounds; yellow primary/secondary/tertiary text
  (with graded alpha), yellow default/primary/active icons, yellow dividers and stroked
  button outlines; black splash with the traced-pin logo, wordmarks removed; in-app
  wordmark string is 白い熊 地図.
- **白い熊 地図 settings page** (Settings row + long-press on the drawer map button):
  - RGBA slider color pickers with prior-color swatches and live preview, overriding any
    themed color at runtime via `ColorUtilities` — no rebuild;
  - external font import (SAF), glyph-rendered font picker, weight choice, live sample,
    applied app-wide through `FontCache`;
  - map button size, roundness and opacity sliders with preview; map text size control.
- **Themed flashes**: in-app toasts render as black rounded cards with yellow text and a
  yellow border instead of the unthemable Android 12+ system pill, and follow the settings
  page's color overrides. Android Auto car toasts unchanged.

### Map & behavior
- **Always-visible position marker**: built-in location/navigation icons are never promoted
  to OpenGL 3D models (which load from storage `models/` and the moving core snapshot, and
  can silently render nothing, leaving only the faint accuracy circle while driving); the
  crisp 2D bitmaps are used instead. Explicitly chosen `model_*` icons still take the 3D path.
- **Black marker border**: the markers' top layer (white in stock) is tinted black — the
  yellow triangle/dot is edged in black on both the yellow-road map and dark terrain.
- **Shared main storage**: declares `MANAGE_EXTERNAL_STORAGE` (as stock's own builds do)
  and routes the "directory not writeable" dead end to the system All-files-access toggle,
  so the main storage can be a shared folder — e.g. one folder of maps shared with the
  official OsmAnd install.

### Repo tooling
- `upstream-new-version` skill: proceed-gated tabular feature summary before any rebase,
  fast-forward-only `master` mirror, `custom` rebased with a pre-rebase conflict-surface
  check (files touched by both sides), build-counter reset rules, post-rebase verification
  of every customization.
- `build-apk` / delivery skills: one-command signed build to `~/tmp/` with automatic
  push to the phone (or scp fallback).

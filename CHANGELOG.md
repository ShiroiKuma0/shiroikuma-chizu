# Changelog — 白い熊 地図

Everything built on top of stock OsmAnd (`upstream/master`). The version is
`<upstream base>+<fork build>`; the base commits track OsmAnd's development line.

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

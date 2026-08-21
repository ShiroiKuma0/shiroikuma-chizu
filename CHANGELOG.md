# Changelog — 白い熊 地図

Everything built on top of stock OsmAnd (`upstream/master`). The version is
`<upstream base>+<fork build>`; the base commits track OsmAnd's development line.

## 5.4.0+021 — 2026-08-21

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### Android Auto: the crash on every navigation start

`+020` painted the car screen's ETA panel yellow and, in doing so, hit the one validator in the
car API that refuses a custom colour. Starting navigation in the car killed the app — every
time, sixteen fatal crashes in a single drive's crash buffer.

- **What threw.** `TravelEstimate.Builder.setRemainingTimeColor` and `setRemainingDistanceColor`
  validate against `CarColorConstraints.STANDARD_ONLY`: they accept the seven standard car
  colours (`DEFAULT`, `PRIMARY`, `SECONDARY`, `RED`, `GREEN`, `BLUE`, `YELLOW`) and reject a
  custom one outright. `+020` handed them `ChizuCar.accent()`, a custom `#FFFF00`, so building
  the trip threw `IllegalArgumentException: Car color type is not allowed: [type: CUSTOM,
  color: -256, dark: -256]`.
- **Why navigation start, specifically.** Building the trip is the first thing
  `NavigationSession` does in `startCarNavigation`, and it does it again on every
  `newRouteIsCalculated` — so the car session died the instant a route went live.
- **Why the phone kept working.** `TripHelper` only ever runs inside a car session; navigating
  on the phone itself never touched the failing code.
- **The fix.** Both colours now go through a new `ChizuCar.standardAccent()`, which returns
  `CarColor.PRIMARY`. That passes the validator, and the host resolves it from
  `ChizuCarAppTheme`'s `carColorPrimary` — the same `#FFFF00` — so the ETA still reads yellow.
  The one cost is the limitation `PRIMARY`/`SECONDARY` already carried: the host process cannot
  see the theming page's runtime overrides, so those two numbers stay at the manifest value.

`paintEstimate` is shared by the destination, current-step and next-step estimates, so the
single change covers all three.

Nothing else `+020` painted can fail the same way. In `androidx.car.app:app:1.7.0`, the version
the fork builds against, `TravelEstimate.Builder` is the **only** class in the entire library
that references `STANDARD_ONLY` — icon tints, action background colours, `PlaceMarker` colours,
`ForegroundCarColorSpan` and the navigation card background are all `UNCONSTRAINED` and keep
taking the custom accent.

## 5.4.0+020 — 2026-08-16

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### Android Auto: black-yellow everywhere the car API reaches

`+8` gave the car screen yellow glyphs and a themed flash. Everything around them stayed the
host's grey with white text, for a reason nothing had looked at: **OsmAnd declares no
`androidx.car.app.theme` in its manifest.** Without that meta-data the host resolves
`CarColor.PRIMARY` and `CarColor.SECONDARY` from its own defaults — and `NavigationScreen` was
already setting `CarColor.SECONDARY` as the background of the card behind the turn instructions,
so it was literally asking the host to pick whichever grey it liked.

- **The theme is now declared.** `chizu_car_theme.xml` defines `carColorPrimary` /
  `carColorSecondary` (and their `*Dark` variants) as our yellow and black, and the manifest
  names it. Day and night carry the same values on purpose — the fork is black-yellow either way.
- **Everything the app itself paints goes through `ChizuCar`**, which reads `ColorUtilities` and
  therefore follows the 白い熊 地図 theming page. The car host runs in its own process and cannot
  see those runtime overrides, so the manifest style carries the static defaults for
  `PRIMARY`/`SECONDARY` alone.

Themed this release:

- **The navigation card** behind the turn instructions is black instead of host grey.
- **Every action glyph is yellow** — search, compass, 2D/3D, settings, pan, my location, zoom in
  and out, on the landing screen, the navigation screen, history and tracks.
- **Filled yellow buttons**: Stop, Start, Apply, Yes / No, Allow / Cancel, both missing-maps
  buttons, the location-permission button (stock: `CarColor.GREEN`) and the purchase button
  (stock: `CarColor.BLUE`).
- **Monochrome row icons are yellow** — including the two "sort by last modified" icons that were
  still tinted upstream's OsmAnd orange, and the POI and amenity glyphs, which are drawn in the
  app's *default icon colour* and so came out **grey whenever the phone itself was in day mode**,
  regardless of what the car was doing.
- **Every row's second line is yellow** — distances, addresses, opening hours, track descriptions.
- **The ETA panel** — remaining time and remaining distance — plus the **destination pin** and the
  **`Place` markers the host drops on the map** for list rows.
- **Car flashes** carry a yellow glyph, and the Android Auto **notification** gets the accent tint
  and our own name in place of the hard-coded "OsmAnd Android Auto".

Left alone deliberately: icons whose colour carries meaning are **not** flattened to yellow — a
favourite keeps its category colour, a map marker keeps its own — and turn arrows keep
`nav_arrow` `#FADE23` with the imminent green, since that is the same drawable the in-app map
widget renders.

### What the car API does not allow, recorded so it is not retried

- **A row title cannot be coloured.** `Row.setTitle` validates against
  `CarTextConstraints.TEXT_AND_ICON`, which rejects a `ForegroundCarColorSpan` and throws. Only
  `addText` — the second line — permits colour spans, which is why the subtitles are yellow and
  the titles are still the host's white.
- **The surrounding chrome is host-drawn** with no hook: list background, header bar, dividers,
  scrollbar, and the standard `Action.BACK` / `Action.APP_ICON`.
- **Background colours must follow `ActionsConstraints` exactly**, because violating them throws
  at template-build time and takes the whole car session down — the crash loop fixed in `+8`.
  Body actions on `Pane` and `MessageTemplate` accept a background on any action, so
  `ChizuCar.filledAction` just sets one; navigation and map action strips accept one only on the
  single primary action, so `ChizuCar.primaryAction` pairs it with `FLAG_PRIMARY`. The map
  strip's four buttons therefore stay icon-tint-only — that strip has no primary action.

### Map pins landed on the driver (fix)

`MapMarkersScreen` built each row's `Place` metadata from **our own position** rather than the
marker's, so every pin the host drew for the map-markers list sat on top of the driver instead of
on the marker. Harmless while the pins were host-default and easy to miss; not once they are
themed and conspicuous.

## 5.4.0+019 — 2026-08-08

Base unchanged (OsmAnd `master` at `7c597b19bd`). Packaging only — the app is identical to `+18`.

### Build counter zero-padded to three digits

The `versionName` counter was written bare, so the APK filenames it feeds — and the release tags
taken from those filenames — sorted lexicographically instead of in build order: `+10` landed
before `+3`, burying the newest build in the middle of `~/tmp/`, of the phone's file manager, and
of the release list. Three digits fixes the order up to `+999`, which the `versionCode` multiplier
(`base * 10000 + N`) had already capped it at.

- **`versionName` is now `<base>+NNN`** — this release is `5.4.0+019`, and the APK is
  `shiroikuma-chizu_5.4.0+019_arm64-v8a.apk`.
- **`versionCode` keeps the plain integer** (`53990019`): padding is a text convention, and the
  code has to stay numeric and monotonic. `BUILD_NUMBER` in `gradle.properties` stays plain too, so
  the `buildApk` auto-increment is untouched.
- **Nothing already published was renamed.** Builds up to `+017` keep their unpadded tags and
  filenames. Padded names sort before the older unpadded ones for a while; that settles as the old
  builds age out.

## 5.4.0+18 — 2026-08-08

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### Legible content on the yellow accent (fix)

Stock OsmAnd uses one color — `active_buttons_and_links_text_*`, a near-white `#ebebeb` — for two
different jobs: content on the **app bar**, and content on the **accent fill**. Upstream those
agree, because upstream's app bar is dark grey and its accent is orange; near-white reads on both.
They cannot agree here. Our app bar is `#000000` and needs light content, while our accent is
`#FFFF00` and needs dark content. Every accent-filled button in the app was therefore drawing
near-white text on yellow — the trip-recording **Start** button being the one that made it obvious.

- **The two roles are now separate resources.** `active_buttons_and_links_text_*` keeps the app
  bar, untouched, so all ~40 toolbar call sites behave exactly as before; a new
  `chizu_on_accent_dark` / `chizu_on_accent_light` carries the accent fill, exposed to layouts as
  `?attr/chizu_on_accent`.
- **The on-accent color is derived, not fixed.** Since the accent is user-settable in the 白い熊
  地図 theming page, hard-coding black would break the moment a dark accent was chosen.
  `ChizuTheme.contrastOn()` picks black or white from the WCAG relative luminance of the accent,
  and the override is re-derived whenever the ACCENT slot changes. The default yellow gives black.

Fixed everywhere light content sat on the accent fill:

- the **trip-recording Start button**, and the pressed state of every button in that sheet
- **every PRIMARY dialog button in the app** — Apply, Save, Continue, Replace all — via the
  `dlg_btn_primary_text_dark` selector's resting state
- the **navigation Go button**, split per-branch so the branch that is *not* accent-filled keeps
  its light label on the activity background
- the **route statistic** and **public transport** card buttons
- the **Replace all** icon in the import-duplicates screen
- **wikivoyage** primary buttons
- **FABs filled with the accent** — quick actions, key assignments, marker groups
- the **custom-POI Show bar**, whose subtitle was separately yellow-on-yellow, i.e. invisible

Deliberately left alone, having been checked and found legible: selection-mode toolbars (they fill
with `#0E3A7C`, not the accent), snackbars (black fill), and plugin logos (already black on yellow).

Known limitation, pre-existing: a custom accent only repaints views colored through
`ColorUtilities`. XML-themed views keep the static yellow, so changing the accent does not yet
recolor those fills — only the label contrast on them follows.

## 5.4.0+17 — 2026-07-31

Base refreshed: OsmAnd `master` at `7c597b19bd` (124 commits ahead of the previous base). No
fork-side changes in this build — everything below is upstream's, carried in by the rebase, and
all 22 of our commits replayed onto it without a single conflict.

### What came in from upstream
- **Search reworked, then partly rolled back**: upstream reverted its own "Improve spatial search
  android" wholesale and rebuilt it piecemeal — POI-category search gains a frequency signal and
  zoom-tile deduplication, bounding-box clipping, a search radius raised to **400 km**, short
  region codes in the search URL, "show more" and category items in the result list, the category
  list capped at five, and client-side sorting dropped in favour of the engine's own order.
  Search results will behave noticeably differently from `+16`.
- **Native crash logs can be shared**: a new `NativeCrashHandler` and a refactored
  `FeedbackHelper`; the "Send crash log" dialog no longer interrupts active navigation.
- **A dedicated media folder** for attached media, with a legacy A/V-notes fallback — this
  reworks `AttachedMediaExportType`, which is the stock side of our `my_places.attached_media`
  backup category, so an export/import round-trip of attached media is worth exercising.
- **Wikipedia images fixed**: legacy Wikimedia thumbnail URL matching, and broken article images.
- **Sensors**: the external-sensor battery warning now fires once per connection rather than
  repeatedly; cadence data parsing fixed.
- **Nautical**: AIS rendering optimized, with stable decluttering.
- **POI**: a new `site` type, rare categories resolved at low zooms, chip-scope colors.
- **Fixes**: favourites search UI, GPX layer rendering, OSM GPX upload visibility, the
  trip-recording max-speed and slope widgets, and a simplified quick-search dialog.
- **Translations**: 54 Weblate commits across roughly 25 languages.

## 5.4.0+16 — 2026-07-31

Base unchanged (OsmAnd `master` at `d328a7693c`).

### 保存復元 — every category says whether it starts ticked (new)
- **`LIST_CATEGORIES` gained a fourth field**: each line is now `id⇥label⇥parent⇥on|off`,
  with the third field left **empty** on a group row so the fourth keeps its position. The
  field is positional and optional in the contract — absent means `on` — so nothing written
  against the old three-field reply breaks. 白い熊 自由作業盤 redraws its backup-item picker
  from this reply every time it is opened, so the starting selection is now this app's
  answer to give rather than the caller's to guess.
- **Unticked by default**: the whole `maps` group and each of its parts — standard maps,
  wiki & travel, depth data, road maps, terrain — being gigabytes of downloaded map files,
  and `resources.tts_voice` / `resources.voice`, the downloaded voice packages. All of them
  are re-obtainable from OsmAnd's own servers. Everything under `settings` and `my_places`
  stays ticked: favourites, tracks, attached media and profiles are authored and cannot be
  re-downloaded. The maps rule is structural rather than a list of ids, so an
  upstream-renamed map type cannot silently flip back to ticked.
- **The in-app picker reads the same flag**: the Export / Import panel seeds every checkbox
  from the shared catalogue instead of its own hardcoded defaults, so the sheet and the
  automation picker open on one answer instead of two.
- **An absent `items` extra** still means "the default set" — but that set is now the ticked
  categories rather than every category. Naming a group id explicitly still takes all of its
  parts, ticked or not: asking for a group is an explicit ask, not a default.

### 保存復元 — `CANCEL_EXPORT` (new)
- **A third exported action**, `shiroikuma.chizu.action.CANCEL_EXPORT`, on the same receiver
  and behind the same token, with an optional `reply_id` (absent = whatever is running, which
  is unambiguous because two exports at once are forbidden). It exists because a cancelled run
  used to carry on to the end and deliver a backup that had already been stopped.
- **Fire-and-forget**: it is never answered — not on success, not on a bad token, and not when
  nothing is running, where it is a silent no-op rather than an error or a crash. Safe to send
  at any time, including after the export it names has already finished.
- **The export it stops unwinds cleanly**: the cancel flag is polled between entries, so the
  run ends at the next boundary — never a killed process, never a thread interrupted
  mid-`write()`. It deletes the half-written archive in the very same unwind that handles
  every other failure, leaving the backup directory exactly as it found it — no short archive,
  no stray partial — and sends the one terminal reply for the original request,
  `ERROR:cancelled`, through the existing single-shot replier.
- **Reachable during a cold start**: the flag is published for the whole request rather than
  just the write, so a cancel arriving while a cold-started process is still waiting for the
  app to finish initializing — up to three minutes — lands instead of being ignored.
- **One way to unwind**: the panel's own Cancel button and the broadcast end at the same flag,
  and that path deletes the partial archive for both.

## 5.4.0+15 — 2026-07-25

Base unchanged (OsmAnd `master` at `d328a7693c`).

### 保存復元 — headless, token-gated state export (new)
- **Two exported broadcast actions**, `shiroikuma.chizu.action.EXPORT_STATE` and
  `shiroikuma.chizu.action.LIST_CATEGORIES`, carried by one receiver with no
  `android:permission` — the automation token is the gate. A sister automation app
  (白い熊 自由作業盤) can now back this app up in its one-run batch over every sister app.
- **`LIST_CATEGORIES`** answers `OK:` plus one `id⇥label[⇥parent]` line per category: the
  groups `maps`, `settings`, `my_places`, `resources` with every stock export type as an
  indented part (`maps.standard_maps`, `settings.profile`, `my_places.favorites`, …), plus
  `settings.chizu_ui` for the 白い熊 地図 theming. The caller renders it as a checkbox picker.
- **`EXPORT_STATE`** runs the very same export headlessly — no Activity, no interaction.
  `items` selects categories (absent = everything; a group id expands to all of its parts),
  `path` overrides the configured backup directory (created if missing). Directory
  precedence: `path` → the configured backup directory → `ERROR:no-directory`.
- **The reply is a fresh broadcast** with `FLAG_INCLUDE_STOPPED_PACKAGES` — EMUI will not
  reliably carry a live binder between third-party apps, so no `ResultReceiver`,
  `PendingIntent` or `Messenger`; the ordered result is set too but never relied upon.
  Exactly one terminal reply per request, guarded by an `AtomicBoolean`:
  `OK:<path>|<bytes>|<human size>|<n> categories`, or `ERROR:<reason>` —
  `automation disabled` and `bad token` reported distinctly.
- **Progress broadcasts in real numbers**, never a percentage: a display line
  (`1.20 GB / 4.20 GB`), plus structured `current`/`total`/`unit` extras and the app label,
  throttled to at most one every 500 ms with a final one at completion.
- **Automation gate in the UI**, inside the Export / Import section right under
  「Export / Import…」: an **Automation export** switch (**off by default**) and a token row
  showing `80922d8c…4c49a87c`, copying the full token to the clipboard on tap, with a
  **Regenerate** action that warns pasted copies must be updated. Token = 24 `SecureRandom`
  bytes, hex, generated lazily on first read, compared constant-time; switch and token live
  in the device-local prefs file that is never part of any export.
- **A cold-started process waits for app initialization** before collecting, so a backup
  triggered while the app is not running exports complete data.

### Export / Import (changed)
- **Family file-name convention**: every backup — from the panel as well as the automation
  path — is now `shiroikuma-chizu_<yyyy-MM-dd_HH-mm-ss>.zip`, one ZIP per run, with no
  version and no `-export` infix, so all sister apps' backups sort and read uniformly in one
  directory. The "Last export:" query still recognises the older `.osf` names.
- **One export core** (`ChizuBackup`): the category catalogue, `items` resolution, plain-file
  and SAF destinations, byte-counting progress and the 白い熊 地図 sidecar now live in one
  headless class that the panel and the receiver both call — no duplicated export logic.
- **Export progress** shows real byte counts (`1.20 GB / 4.20 GB`) through both phases,
  collection and writing, in place of the item counter; Cancel still aborts the stock task
  and removes partial files.

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

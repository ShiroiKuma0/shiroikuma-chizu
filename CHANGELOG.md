# Changelog — 白い熊 地図

Everything built on top of stock OsmAnd (`upstream/master`). The version is
`<upstream base>+<fork build>`; the base commits track OsmAnd's development line.

## 5.4.0+039 — 2026-09-08

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### A 地図 backup carries the pointers, not the shared folder

Main storage on 白い熊's phones is `/storage/emulated/0/〇/[60] 地図` — a folder that lives in a tree
already carried between machines by its own means. Its files are not this app's to back up, and
copying them made the archive **4.3 GB across 24,953 entries**, of which 24,856 were cached raster
tiles. Restoring it wrote a second copy of a tree the target phone already had.

- **The export drops file-backed items when the folder in use lies outside this app's own
  directories** — every `.obf`, wiki, srtm and geotiff, the colour palettes, the tile caches and the
  tracks: 69 `FILE` items and 6 `GPX` in that archive. What travels instead is what is genuinely
  ours: settings, profiles, quick actions, favourites, search history, the 地図 UI sidecar, and
  `selected_gpx` — the pointer saying which tracks are drawn. On the far side those pointers find
  the files already sitting in the shared folder.
- **Decided from where the resources are now, not from the configured storage type.** Those two
  disagree in exactly the case that matters: with Main storage set to a shared folder but All-files
  access missing, the type still reads SPECIFIED while the app has already fallen back to a private
  directory and is genuinely working there — where its files *are* the only copy and *do* belong in
  a backup. The test compares the directory actually in use against every private root the platform
  gives the app.

### The Main storage folder itself, which no backup had ever contained

`general_settings.json` carries `media_storage_type` and `media_storage_manual_uri` — but those are
the setting for photo and video **notes**. Main storage lives in `external_storage_dir_V19` and
`external_storage_dir_type_V19`, raw preference keys rather than registered `OsmandPreference`s, so
the stock exporter never walks them.

- **The folder now rides in a `chizu_storage.json` sidecar entry**, restored through
  `setExternalStorageDirectoryV19`. Without it a restored phone came up on whatever
  `initExternalStorageDirectory` chose on its first run — normally `Android/data/…/files` — and
  every restored pointer resolved to nothing: six tracks present, none drawn.
- **It travels regardless of the 地図 UI tickbox.** It is not a UI preference, and a correct restore
  must not depend on remembering to tick something. The export re-zips whenever there is a folder to
  record, not only when the sidecar was asked for.
- **Restored without checking the folder exists**: on a phone that has not been granted All-files
  access the folder is unreadable and would fail an existence test while being perfectly present.

### Fifteen minutes of silence in the middle of every import

`applySidecar` looked for two small entries with a `ZipInputStream`, which has no index and can only
walk an archive from the front, inflating everything it passes.

- **`ZipFile` instead — the central directory, not a full decompression pass.** Measured on the
  phone at **15 minutes 17 seconds of one core at 100 %**, silent, uncancellable, before the stock
  import had begun; the 15-minute import bound only ever covered the stage *after* it, so the
  timeout fired on work that had barely started. It is also the correct reader: a local file header
  may omit the UTF-8 name flag the central directory sets — OsmAnd's own export does exactly that
  for the `favorites-*.gpx` entries — so a stream reader saw those names as mojibake.

### Every exit from the data door answers

Two paths stopped without a word, leaving a caller to burn its ten-minute watchdog and report that
this app had gone quiet.

- **The 60-second reaper** that reclaims a descriptor whose service was never delivered now sends
  `ERROR:the data service was never delivered`. The reply address travels with the descriptor so it
  can answer under the right job id.
- **The early return for an already-drained job id** answers instead of calling `stopSelf` silently.
- **One shared sender** for both, so the reaper's answer cannot drift from a normal one.
- **The progress label reached nobody.** It travelled as `text` where the contract says `result`, so
  every stage line was discarded on arrival — a long import showed an unmoving byte pair and nothing
  else. Sent under both names now.

### Progress that climbs

- **A five-second heartbeat for the whole import**, because a caller treats an app that is both
  silent and burning no CPU as dead — and the stock import is exactly that shape for minutes at a
  stretch, being two AsyncTasks that report nothing.
- **It reports bytes that have landed**, read off the archive's entry table up front and summed from
  the destinations each beat, with the file being written named. Clamped to a high-water mark so it
  can never fall — a falling count is read as a deliberate "second pass" marker — and every failure
  path falls back to the standing figure rather than skipping a beat. A proxy, not a measurement:
  some settings items are preferences and never appear on disk.

### What is offered is what can be delivered

- **`LIST_CATEGORIES` omits a category whose files cannot travel** rather than marking it off. "Off"
  means *not recommended*, which a caller may legitimately override — and ticking Maps then promised
  a backup this app would not perform. A group heading goes when nothing under it survives.
- **`describe()`'s `contains` follows the same test**, so a caller counting the header against an
  export cannot report "all 7" for an archive holding three.
- **The Export / Import panel greys those rows**, unticked and disabled, under a line naming the
  folder they actually live in.
- **An export that would carry nothing says so** instead of writing a sidecar nobody asked for and
  reporting it as *n* categories.

### The permission no backup can carry

- **The map screen asks for All-files access on start** when the configured Main storage folder is
  out of reach, going straight to the system toggle with a toast giving the reason. The grant is an
  app-op, not a runtime permission, so no restore contract can supply it — a freshly restored phone
  came up configured for a folder it could not open and said nothing at all.
- **A clean-phone restore never fails for want of it.** The archive now carries nothing that needs
  storage permission to land.

### Packaging

- **No Google Play Billing components.** The library arrives transitively through upstream's
  purchase code and contributes `ProxyBillingActivity` and `ProxyBillingActivityV2` to the merged
  manifest, which 白い熊 応用管理 lists as trackers — Play-store plumbing in an app that is
  sideloaded, has no Play install to talk to and sells nothing. Removed in the flavour manifest
  rather than by dropping the dependency, so an upstream rebase does not have to be re-reconciled.

## 5.4.0+029 — 2026-09-04

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### 保存復元: the token becomes optional, and a data door that knows its caller

Contract v2. The token was a 48-character secret 白い熊 pasted from this app's settings into the
caller's, and a pasted secret cannot survive a wipe — which is fatal for the case the family now
exists to serve: 応用管理 restoring apps *and their data* onto a clean phone, where nothing has been
configured and nobody has pasted anything. A gate that only works once the phone is already set up
is no gate for setting the phone up.

- **The switch ships on, the token became optional.** `automation_enabled` now defaults **true** and
  a new `automation_require_token` defaults **false**. Both checks live in one
  `ChizuAutomation.refuse()` returning null-to-proceed or the exact `ERROR:` line, and every action
  routes through it — the export receiver, the track receiver, and the cancel branch that used to
  test the token inline. **A token sent to an app that does not require one is ignored, never
  refused**: tokens outlive the setting they were pasted for, and refusing them would turn one
  switch being turned off into half a batch mysteriously failing. `OPEN_TRACK` stays outside the
  gate, as it always was.
- **The three flag writes are `commit()`, not `apply()`.** The gate **fails open** now that the
  default is true: a lost "turn this app off" does not fall back to off, it falls back to **on** —
  and the caller force-stops this app with a `SIGKILL`, which runs no shutdown hook for a queued
  write to finish in. Turning the app off is the one action 白い熊 has to shut a sister app out.
- **New data door: a `ContentProvider` at `<pkg>.automation`**, exported with no permission,
  answering `describe` / `export` / `import` / `cancel`. A broadcast cannot tell you who sent it,
  and the caller supplies the destination — so the door that moves data identifies its caller from
  the framework instead: **exact package name** (never a `shiroikuma.` prefix, which is not an
  identity — any sideloaded app may take one, making a prefix check strictly weaker than the token
  it replaces), **the uid as the kernel reports it** rather than as the caller declares it, and a
  **pinned signing certificate**, since whichever caller package is absent from a device is a name
  anyone can take and a clean phone is precisely where not everything is installed yet. Refusals
  are returned, never thrown across the binder.
- **The payload is a file descriptor the caller opens** — not a path, not a `content://` URI. A
  backup is not a stable directory while it is being assembled, its encryption and its
  `checksums.txt` are built per file the caller knows about, and a file dropped in from outside
  would sit in plaintext inside an encrypted archive and go unverified rather than
  verified-and-failing. A descriptor is also a capability that **expires when it is closed**, which
  is the property the walk contract's URI grants could not give.
- **`import` exists only on the provider.** It never gets a broadcast action: an import overwrites
  this app's data, and the automation receivers are exported without a permission, so an import
  there would let any app on the phone wipe 地図.
- **`ChizuAutomationDataService`** runs both directions as a `dataSync` foreground service. It goes
  foreground **before any early return** — once `startForegroundService` has been called the
  platform requires it whatever the service then decides, so a caller retrying with a stale job id
  would otherwise kill the very app it is backing up — and drains the descriptor handover **in the
  same `finally`**, so a refused start cannot strand a caller's descriptor held open. An import is
  **spooled to a temp file**, never read into memory, because a 地図 archive can carry downloaded
  regions.
- **`flushPreferences()` before an import reports success**, committing `chizu_exim`, the theme
  prefs, `OsmandSettings`' global file **and every `ApplicationMode`'s file**. The caller
  force-stops this app the instant it hears success — it has to, since a live process writes its
  cached preferences back out at orderly shutdown and would silently undo the import — and a
  `SIGKILL` runs no shutdown hook, so anything stock OsmAnd still had queued would die with the
  process.
- **`ChizuProgress`**: the progress sender extracted from the export receiver and parameterised
  rather than copied, so the data door reports in the same §3 shape with the job id as its
  correlation id, instead of a second implementation drifting apart from the first.
- **`<queries>` now names both `shiroikuma.jiyusagyoban` and `shiroikuma.oyokanri`.** It named
  neither — so the reply broadcasts of the walk round trip, proven end to end on `+028` and called
  done, had been resting on the package visibility Android grants **implicitly** after a prior
  interaction. That holds right up until it does not, and it would have presented not as an error
  but as the map request simply going quiet. The dependency on an accident is now gone.
- Three `<meta-data>` entries (`contract` 2, `format` 1, `min_format` 1) let 応用管理 decide whether
  this app can be backed up **without waking it**, which matters because a frozen app cannot be
  asked anything.

**Known limit:** `describe` reports `"requires_launch_first": false`. The data service waits on
`isApplicationInitializing()` before it collects or imports, which is the right construction, but a
restore onto a *never-launched* package has not been measured. If one lands wrong on a fresh
install, that field is the first thing to flip.

## 5.4.0+028 — 2026-09-04

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### 歩行記録: the hand-over in content URIs, so neither app needs a shared folder

自由作業盤 moved its whole workout archive into its own database, so that everything is covered by
the app's own export/import. With it went the directory the two apps met in —
`/sdcard/〇/[666] 私資料/[666][147] tracks`, of which only the 288 KB of raw band data was
irreplaceable; the GPX and the pictures were both derived. Neither side has a path to name any
more, so the contract takes content URIs, granted per request and revoked after.

- **`IMPORT_TRACK` accepts `gpx_uri`**, a `content://` URI read through the ContentResolver, in
  precedence `gpx_uri` → `gpx_data` → `gpx_path`. **`EXPORT_BASEMAP` accepts `out_uri`** in place
  of `out_path`, written with `openOutputStream(uri, "wt")` — write-and-truncate, so a shorter
  picture cannot leave a tail of the one before it.
- **New: `gpx_out_uri`, `thumb_out_uri`, `map_out_uri`** — where the caller wants each artefact
  put, if it wants it at all.
- **URI mode.** Any URI extra puts the request in it, and then nothing reaches shared storage:
  `out_dir` is read and discarded, and an artefact is produced **only** where the caller named a
  URI to put it. Naming no picture is an ordinary request, not an empty one — since 自由作業盤
  caches one tile-block cutout per neighbourhood and strokes every route that crosses it, the
  usual import now files and measures a walk and renders nothing at all.
- **`out_dir`'s default was the trap.** It is not merely a parameter: absent, it falls back to the
  very directory 自由作業盤 retired, and `mkdirs()` would put it back. URI mode never reads it.
- **The path form is untouched**, so neither app had to ship first.

### Read at delivery, because a broadcast's grant does not live long enough

`gpx_uri` is read inside `onReceive`, before the worker thread starts and before the init wait,
while the caller's grant is youngest. It has to be: the receiver goes async and a cold-started 地図
waits up to 180 s for the app to initialize before it looks at anything, which is far past the
broadcast a flag-scoped grant is tied to. For the same reason the caller's own grant must be an
explicit `grantUriPermission` rather than intent flags. A `SecurityException` on the read is
reported as `cannot read gpx_uri: no read grant for this app` rather than surfacing raw, because
that failure otherwise reads as a FileProvider path problem and is not one.

### What a URI costs: the rename into place

`EXPORT_BASEMAP` writes a file beside itself and renames it, so nothing under the real name is ever
half a picture. A URI cannot be renamed. The bitmap is therefore rendered whole in memory and only
then written, but past the moment the stream opens **only the reply says whether the bytes are
complete** — on anything but `OK:` the caller must discard what is at the far end. 自由作業盤
answers for that at its end: `out_uri` points at a temp file of its own, committed to its database
only on `OK:`.

### Replies

The field count is unchanged. A destination comes back under the name of the form it took —
`gpx_path`/`thumb_path`/`map_path`, or `gpx_uri`/`thumb_uri`/`map_uri`, or `out_uri` — and the
family that does not apply is present but **empty**, so a reader never has to tell "not produced"
from "extra not sent". `zoom` and `map_detail` come out of the render, so with no picture asked for
they are empty rather than a `0` that would read as a real zoom. `stored_path` and every
measurement, `active_time_s` included, are untouched.

Both refusal strings are **reworded deliberately**: `no gpx: pass gpx_uri or gpx_data` and
`no out_uri or out_path`. 自由作業盤 matches the previous wording to tell 白い熊 that the installed
地図 predates this contract, so those exact strings had to stop being reachable here.

## 5.4.0+027 — 2026-08-31

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### 歩行記録: the map under a tile block, so a walk needs no picture of its own

`IMPORT_TRACK` renders one picture per walk. Most of 白い熊's walks are the same few streets over
and over, so that is dozens of near-identical 2.5 MB pictures of one neighbourhood — and each also
becomes a permanent entry in this app's list of tracks, put there by an automation rather than
chosen. The new action renders the **area** instead: 自由作業盤 caches the cutout once and strokes
every walk that fits inside it locally, so a hundred walks around one neighbourhood cost one map
and no library entries. `IMPORT_TRACK` is untouched and is still the right call for "show me this
one walk in 地図".

- **`shiroikuma.chizu.action.EXPORT_BASEMAP`**, on the same exported receiver, behind the same
  switch and the same 24-byte token. Takes `zoom`, `tile_x`, `tile_y`, `tiles_w`, `tiles_h`,
  `tile_px`, `out_path` and `night`; writes a PNG of the map under that tile block and nothing
  else. **No track, no marker, no library entry** — the basemap path never touches the tracks
  database or the selection helper, so that holds by construction rather than by care.
- **What it answers.** `OK:<out_path>|<width>|<height>|<map_detail>`, with each value again as its
  own string extra, plus the request echoed back — `zoom`, `tile_x`, `tile_y`, `tiles_w`,
  `tiles_h`, `tile_px` — so a reply identifies its own cutout without having to be matched up
  against the request that produced it.
- **`map_detail` is the point of the reply, not decoration.** `basemap` means only the bundled
  world map was underneath, so the caller can ask again once the region has been downloaded
  instead of caching a pale rectangle for ever and never knowing why every walk looks empty. Same
  `map`/`basemap`/`none` vocabulary, read from the same rendered-state bits, as `IMPORT_TRACK`.

### Why the request is in tiles and never a bounding box

A bounding box would let this app choose a framing, and a framing the caller did not choose is one
it cannot project onto: every walk drawn over the cutout would need a stored transform kept in step
with the pixels. `z/x/y` plus a size fully determines the geography, so both apps compute the same
extent from the same published definition and neither has to remember what the other decided.

- **The grid maps onto the renderer exactly.** OsmAnd's own tile math is already the ordinary
  slippy-map convention, and `RotatedTileBox` measures `2^zoomFloatPart · 256 · mapDensity` pixels
  to the tile — so with `mapDensity = tile_px/256`, no rotation and no float zoom, the block lands
  on the grid: centre from `getLatitudeFromTile(z, tile_y + tiles_h/2)`, size `tiles_w · tile_px`
  by `tiles_h · tile_px`, which is also the bitmap the rasterizer allocates. Nothing is auto-fitted
  and nothing is padded anywhere along that path.
- **`tile_px` rides on the screen density too**, not only the map density. Otherwise labels sized
  for a phone screen are drawn onto a 256-pixel tile and swamp it. Tied to both, it is a clean
  scale knob: ask for 512 and the same geography comes back with everything twice the size.
- **Anything that would move the geography is refused, never quietly corrected** — with the value
  and the range in the error. A cutout whose extent is not the one asked for cannot be drawn on,
  and would sit in the caller's cache for months before anyone noticed. That covers a zoom outside
  1–19, a block that leaves the world at that zoom, a picture over 3072 px a side, and an odd
  `tile_px`: the tile box centres on a whole pixel, so an odd edge would shift the block half a
  pixel off the grid.
- **The ceilings are deliberately looser than the contract asks for** — 8 tiles a side rather than
  6, `tile_px` from 64 to 1024 — so a later version of the sister app needs no build here.

### The picture takes its name only once it is whole

- **Written beside itself and renamed into place**, replacing whatever held the name before. The
  caller guards against a blank cutout by checking the file is over 1 kB, which a half-written PNG
  clears easily — and under the right name it would blank every walk in the area rather than
  failing visibly.
- **One rasterizer path for both actions.** The offscreen render moved out of the track drawing
  into a shared step, so `IMPORT_TRACK` and `EXPORT_BASEMAP` share one lock, one night-mode
  override and one `map_detail` derivation; the track is simply stroked on afterwards.

## 5.4.0+026 — 2026-08-24

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only.

### 歩行記録: `active_time_s`, the one figure the band also measures

The import reply already carried `duration_s` (the span) and `moving_time_s`, and neither stands
against anything: the HUAWEI Band 11 Pro publishes no equivalent of either, so a disagreement
between the two devices was never a finding — only two instruments answering different questions.
Active time is the number the band does report, so it is the one place a mismatch means something.

- **New string extra `active_time_s`**, beside `moving_time_s`. The walk with its gaps taken out:
  the deltas between consecutive points summed, dropping any longer than the gap threshold and
  never bridging a segment boundary.
- **The positional `OK:` line is deliberately unchanged.** The sister app reads the named extra,
  and lengthening that line would break anything still splitting it on six fields.
- **The threshold was measured, not chosen.** The reference walk is one `<trkseg>` of 1763 timed
  points spanning 7669 s, its points a second apart, with exactly three deltas above ten seconds:
  18 s, 1025 s and 4865 s. Every threshold from 20 s to 1024 s therefore returns the same 1779 s,
  and anything at or below 15 s returns 1761 s. **60 s** sits in the middle of a plateau over a
  thousand seconds wide, far from either edge.
- **It agrees with the band, and with the other side of the wire.** 自由作業盤 computes 1779 s
  independently; the band's own figure is 1767 s. Twelve seconds apart — the kind of agreement that
  makes the three-percent distance shortfall believable as a band defect rather than a decoding
  artefact.
- **Why the obvious implementation fails.** The band's GPX has no chunk structure at all: one
  segment holds the whole day-window, pauses included. Summing per-segment spans hands back the
  7669 s span unchanged. The chunks exist only as these gaps.

## 5.4.0+025 — 2026-08-24

Base unchanged (OsmAnd `master` at `7c597b19bd`). Fork-side only. Covers the unreleased
`+022`–`+024` builds as well.

### 歩行記録: a walk comes in from 自由作業盤 and goes out as a map

[白い熊 自由作業盤](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban) drives 白い熊's HUAWEI
Band 11 Pro without any Huawei software, and decodes a recorded outdoor walk into a GPX. It cannot
draw a map, and should not try. This release is 地図's end of that contract: hand it the walk, it
files it as a track and hands back two rendered pictures of it — all with nothing on screen.

- **`shiroikuma.chizu.action.IMPORT_TRACK`**, behind the same switch and the same 24-byte token as
  the 保存復元 export. The GPX travels inline in `gpx_data` when it is small enough, or by
  `gpx_path` when it is not; either is accepted and the inline copy wins. Optional `name`,
  `track_id`, `folder`, `out_dir`, `thumb_w`/`thumb_h`/`map_w`/`map_h` (or a square `thumb_px`/
  `map_px`), `track_color`, `night`, `show` and `density`. Every numeric extra parses from a string
  or a number, since the sister apps send string extras only.
- **What it writes.** OsmAnd's own re-serialization of the track into `tracks/自由作業盤/<name>.gpx`
  with its row and analysis in the tracks database, selected but hidden so a shared walk never
  silently redraws the map; then the normalized GPX, a large PNG and a thumbnail into a folder both
  apps can read — `/sdcard/〇/[666] 私資料/[666][147] tracks` unless the caller names another.
- **What it answers.** `track_id`, the three paths, `map_detail`, `zoom`, and the numbers OsmAnd
  computes over the track itself: `distance_m`, `duration_s`, `moving_time_s`, `points`,
  `start_time`, `end_time`, `elevation_up`, `elevation_down`, `avg_speed`, `max_speed`. Each value
  travels twice — as its own string extra and packed into `result` behind the `OK:` — so a bridge
  that surfaces only `result` still receives everything.
- **Re-sharing corrects rather than duplicates.** The `track_id` is the path relative to the tracks
  directory; sent back, the same file is rewritten. It is canonicalized first, so a hand-written id
  carrying a leading `tracks/` or a whole stored path reduces to the same track instead of filing a
  second copy at `tracks/tracks/…`.

### The map picture, with no map on screen

- **Rendered by the legacy rasterizer**, which turns the installed `.obf` maps into a bitmap on a
  worker thread. The OpenGL core cannot: it needs a live GL surface, and there is no map view here.
- **Not upstream's `MapBitmapDrawer`.** That one asks `updateRenderedMapNeeded()` first, which
  answers "no" for a tile box it has already drawn — and then never calls back, so a repeated
  request hangs for ever. Every render here is forced, serialized on a lock, and under a timeout, so
  a request always ends in an answer.
- **Two renders, never one downscale**, because vector map labels turn to mush when an image is
  shrunk. Each size is drawn at its own zoom.
- **Legible on any ground.** Every line is stroked twice — a casing beneath the colour, black under
  a bright line and white under a dark one, chosen by Rec. 709 luma. Our yellow is luma 226 and so
  always carries a black casing, which is what keeps it readable on the palest day-style map. The
  ends carry a filled dot and its inverse, so a walk's direction reads in a 480-pixel cell.
- **Framed with air.** The zoom walks down from z17 to the tightest one at which the whole track
  still sits inside a margin, rather than grazing the edge.
- **Honest about what is underneath.** `map_detail` answers `map`, `basemap` or `none`, so a walk in
  a region with no offline map is labelled as a missing download rather than reading as a failed
  render. With no map at all the route is drawn on black, which is cheap and still recognisable.
- **Pinned to the day style.** These pictures are kept for years and shown side by side in a grid;
  one drawn after dusk under the night style would read as a broken cell. `MapRenderRepositories`
  gains a night-mode override, set for the duration of one render and cleared in a `finally` — the
  rasterizer otherwise takes day or night from the live theme with no way to ask.

### Opening a walk in 地図

- **`shiroikuma.chizu.action.OPEN_TRACK`**, a translucent trampoline activity taking `track_id`.
  A broadcast receiver has no window, and since Android 10 an app without one may not start an
  activity — so the older `SHOW_TRACK` could find the walk and never bring the map up. It was
  defeated, not incomplete. Here the calling app hands over the foreground and this activity holds
  it, invisibly, until the track is found, waiting out a cold start if it has to. No token: it does
  nothing a launcher icon cannot, and gating it would break the button silently whenever the token
  drifted.
- **The camera actually moves now.** Both paths write the walk into the last known map location,
  which `MapActivity` restores at the top of every `onResume`. Setting the camera directly does not
  survive that restore, and neither does the pending target: `onPause` blocks the animation thread,
  `onResume` spends the target at `readLocationToShow()` and only re-enables animations a hundred
  lines later, so `startMoving()` returns having done nothing and the cleared target is gone. The
  pending target is still set, for its other effect — reading it unlinks the map from the GPS fix,
  so a live recording cannot drag the view off the walk.
- **`SHOW_TRACK` stays** as the headless path, and now works whichever order the caller uses.

### Internal

- The reply plumbing moves out of `ChizuStateExportReceiver` into a shared `ChizuReplier`,
  unchanged in behaviour: one terminal reply per request, a fresh broadcast with
  `FLAG_INCLUDE_STOPPED_PACKAGES`, the ordered result set but never relied on, and never a binder.
  It now carries named extras alongside `result`.

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

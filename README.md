<div align="center">

<img src="graphics/icon.png" width="120" alt="白い熊 地図 icon" />

# 白い熊 地図

**Offline OpenStreetMap navigation, restyled in black and yellow.**

A fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) with **major additions**: a full black-yellow rebrand, a live-preview theming page for colors, fonts and sizes, one-archive export/import of everything settable (maps included), a headless token-gated backup that an automation app can trigger, an always-visible position marker, shared main storage, themed in-app flashes, and the same look carried into Android Auto.

Installs **side-by-side** with the official OsmAnd (app id `shiroikuma.chizu`).

**📥 Latest release: [`5.4.0+021`](https://github.com/ShiroiKuma0/shiroikuma-chizu/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-chizu/releases)

</div>

---

## 🖤💛 Black-yellow everywhere
The dark theme is the default and means it: pure-black backgrounds, cards, app bars and map buttons, with yellow text, icons, accents and dividers. The splash is black with the traced-pin logo — no wordmarks — and the launcher icon is a yellow edge-traced pin on black.

Anything drawn **on** the yellow accent — the trip-recording Start button, every primary dialog button, the navigation Go button, accent-filled FABs — takes a contrasting dark label rather than stock's near-white, which would be unreadable. That contrast is derived, not hard-coded: change the accent to a dark color in the theming page and the labels flip to white on their own, by relative luminance.

---

## 🎨 One theming page for the whole app
A dedicated 白い熊 地図 settings page (also on long-press of the drawer map button — it opens the instant the long-press lands): RGBA slider color pickers with prior-color swatches and live preview for every themed color, external font import via the system file picker with a glyph-rendered font list, weight selection and live sample, plus map button size / roundness / opacity and map text size sliders — all applied at runtime, no rebuild. Styled kxkb-style: bold yellow headings with text-wide underlines, hairline section separators, deep indents.

---

## 📦 Export / Import everything, one archive
The top of the UI page: pick a backup directory once, then export **everything settable in the app** — every stock category (profiles, favorites, tracks, rendering, routing, voices, …), the 白い熊 地図 theming (colors, fonts, sizes, imported font files riding inside the same archive), and optionally the downloaded **maps** with their subcategories, size-counted live in the panel. One file per backup, named `shiroikuma-chizu_<yyyy-MM-dd_HH-mm-ss>.zip`; live byte-counting progress with a working Cancel; import restores the selected categories and offers a one-tap restart. The stock Settings export/import is replaced by this.

---

## 🗄️ Backed up headlessly, on a token
The same export runs **without opening the app**: three exported broadcast actions let a sister automation app ([白い熊 自由作業盤](https://github.com/ShiroiKuma0/shiroikuma-jiyusagyoban)) ask this one for its category list — each entry saying whether it **starts ticked**, so the gigabytes of downloadable maps and voice packages come pre-excluded while everything authored comes pre-selected — then have it export itself, writing one ZIP and replying with the path, byte count and human size, and **stop it mid-run**: a cancel unwinds at the next entry boundary and deletes the half-written archive, leaving the backup directory exactly as it found it. Every request is gated by a 24-byte token — generated on the device, compared constant-time, kept out of every backup — behind a switch that is **off by default**, both sitting in the Export / Import section. While it works it broadcasts progress in **real numbers** (`512 MB / 4.2 GB`), never a percentage, and the archive it produces is an ordinary backup that the panel's Import restores.

---

## 📍 A position marker you can actually see
The location and navigation markers always use the crisp 2D icons — never the OpenGL 3D models that can silently fail and leave you with no marker while driving — and they are drawn yellow with a **black** border instead of stock's white.

---

## 🗂 Main storage in a shared folder
Declares All-files access (as stock builds do), so the main storage can point at any shared folder — including one used by the official OsmAnd, sharing the downloaded maps between both installs.

---

## 🔔 Themed flashes
In-app toasts are no longer the unthemable white system pill: they render as black rounded cards with yellow text and a yellow border, following the theming page's color overrides.

---

## 🚗 Android Auto, in the same colors
The car screen is black and yellow as far as the car API reaches. Stock declares no car theme at all, so the host picked its own grey for everything — including the card behind the turn instructions, which OsmAnd was asking the host to colour for it. This fork declares one, and paints the rest itself: black navigation card, yellow glyphs on every action, filled yellow buttons for Stop / Start / Apply / Allow / permission and purchase prompts, yellow row subtitles, yellow ETA and distance, yellow destination pin and map pins. The launcher icon renders full-bleed (no white rim) and car flashes are dark navigation alerts rather than white toast pills.

The colours come from the same theming page as the rest of the app, so recolouring there recolours the car — except for the few the host resolves for itself out of the manifest theme, the ETA numbers among them, which hold the fork's own yellow. What the car API keeps to itself stays host-drawn — row **titles** cannot take a colour span at all, and neither can the list background or header bar. The map keeps the full OpenGL 3D renderer, with a self-healing guard that re-enables it after a crash-loop auto-disable.

---

## Built on OsmAnd
A fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) (app id `shiroikuma.chizu`, so it coexists with the official build). OsmAnd is the definitive offline OpenStreetMap map & navigation app — full credit to the OsmAnd team for the platform this fork builds on. The code remains under GPL-3.0.

## Building
```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-chizu.git
git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git resources  # sibling checkout
cd shiroikuma-chizu
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/android-sdk ./gradlew buildApk
```
Builds the signed `androidFullOpenglArm64Release` variant (full feature set, 3D OpenGL core, arm64) and copies `shiroikuma-chizu_<version>_arm64-v8a.apk` to `~/tmp/`. Signing expects a gitignored `keystore.properties` (see `keystore.properties.example`). The build downloads the mini world basemap and the prebuilt OpenGL core from `builder.osmand.net` at build time.

<div align="center">

<img src="OsmAnd/res/mipmap-xxxhdpi/icon.png" width="120" alt="白い熊 地図 icon" />

# 白い熊 地図

**Offline OpenStreetMap navigation, restyled in black and yellow.**

A fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) with **major additions**: a full black-yellow rebrand, a live-preview theming page for colors, fonts and sizes, an always-visible position marker, shared main storage, themed in-app flashes, and the same look carried into Android Auto.

Installs **side-by-side** with the official OsmAnd (app id `shiroikuma.chizu`).

**📥 Latest release: [`5.4.0+8`](https://github.com/ShiroiKuma0/shiroikuma-chizu/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-chizu/releases)

</div>

---

## 🖤💛 Black-yellow everywhere
The dark theme is the default and means it: pure-black backgrounds, cards, app bars and map buttons, with yellow text, icons, accents and dividers. The splash is black with the traced-pin logo — no wordmarks — and the launcher icon is a yellow edge-traced pin on black.

---

## 🎨 One theming page for the whole app
A dedicated 白い熊 地図 settings page (also on long-press of the drawer map button): RGBA slider color pickers with prior-color swatches and live preview for every themed color, external font import via the system file picker with a glyph-rendered font list, weight selection and live sample, plus map button size / roundness / opacity and map text size sliders — all applied at runtime, no rebuild.

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
The launcher icon renders full-bleed on the car screen (no white rim), every map button glyph is yellow, and car flashes appear as dark navigation alerts instead of white toast pills. The map keeps the full OpenGL 3D renderer, with a self-healing guard that re-enables it after a crash-loop auto-disable.

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

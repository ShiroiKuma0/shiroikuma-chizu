---
name: build-apk
description: Build the signed release APK of shiroikuma-chizu (the "白い熊 地図" OsmAnd fork) with the `buildApk` Gradle task, then deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no prompt). Always build first without asking permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the chizu release APK and deliver it

This is **shiroikuma-chizu** — 白い熊's fork of [OsmAnd](https://github.com/osmandapp/OsmAnd),
renamed to `shiroikuma.chizu` ("白い熊 地図") so it installs side-by-side with the official OsmAnd.

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or
> you've made functional changes ready to test), run the build immediately. There is **no** transfer
> question either: after a successful build, deliver the APK automatically via the global
> **`/after-build`** skill — no prompts at all.

> **Never `adb install` / `adb uninstall`** — 白い熊 installs manually from `/sdcard/tmp/`.
> Every `adb push` goes ONLY to `/sdcard/tmp/`. Every `adb` call runs UNSANDBOXED.

> **Never `git commit` or `git push` on your own.** Building does not include committing. Only when
> 白い熊 explicitly says **"Push"** do you commit and `git push origin custom`.

## Build environment (this machine)

- Default `java` is **JDK 11** — Gradle needs JDK 21. Always export `JAVA_HOME`.
- `ANDROID_HOME` is not set in background shells; `local.properties` (gitignored) carries
  `sdk.dir=/home/shiroikuma/android-sdk`, but export `ANDROID_HOME` too for safety.
- **Sibling checkout required:** `~/git/resources` (osmandapp/OsmAnd-resources) — gradle references
  it as `../../resources`. If missing:
  `git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git ~/git/resources`.
- **Network required at build time:** mini world basemap + stars DB from `builder.osmand.net`, and
  the OpenGL core AAR (`net.osmand:OsmAndCore_android*:master-snapshot`) from the
  `builder.osmand.net` ivy repo.

## Steps

1. **Note the output filename / version.** Read the base + counter:
   - `grep -E 'versionCode 5|versionName "' OsmAnd/build.gradle | head -2` (upstream base, e.g.
     `5.4.0` / `5399`)
   - `grep -E '^BUILD_NUMBER' gradle.properties` (the `N` used for THIS build, **before** the task
     bumps it)
   - APK will be `shiroikuma-chizu_<base>+<BUILD_NUMBER>_arm64-v8a.apk`;
     versionCode = `<baseCode> * 10000 + BUILD_NUMBER`.

2. **Build** (release, signed) — from the repo root:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
   ./gradlew buildApk --console=plain < /dev/null
   ```
   - `buildApk` runs `assembleAndroidFullOpenglArm64Release` (our variant: full feature set, 3D
     OpenGL core, arm64, R8 minified, signed from `keystore.properties`), copies the signed APK to
     `~/tmp/<apk name>`, and auto-increments `BUILD_NUMBER` in the root `gradle.properties`.
   - It prints `>>> <path>` and `>>> versionCode <n>` (cyan). Confirm `BUILD SUCCESSFUL` and use
     those for the exact filename/code.
   - OsmAnd is a big build — a cold build (fresh deps + external resources) can take 10–20+ min;
     run it with `run_in_background` if it may exceed the foreground timeout. Warm builds are much
     faster.
   - **`SDK location not found`** → recreate `local.properties` with
     `sdk.dir=/home/shiroikuma/android-sdk`.
   - **Signing failure** → check `keystore.properties` exists at the repo root (gitignored; points
     at `~/.android-keystores/shiroikuma-chizu.jks`, alias `chizu`; template in
     `keystore.properties.example`, password in the android-keystores.org ledger).
   - **`../../resources` errors** → the sibling `~/git/resources` checkout is missing or stale.

3. **At the end of every successful build, deliver the APK via the global `/after-build` skill** —
   no exceptions, no asking. It runs `/adb-check` UNSANDBOXED, then `/adb-push` to `/sdcard/tmp/`
   if a phone is connected, otherwise `/scp` to `skhw:~/tmp/`, and announces what landed.

## Versioning (how the numbers are formed)

- The upstream base (`versionName "5.4.0"` / `versionCode 5399` in `OsmAnd/build.gradle`
  `defaultConfig`) is **upstream's own lines** — they update automatically on rebase; never edit
  them by hand. Our fork lines directly after them compute
  `versionName = "<base>+N"`, `versionCode = <baseCode> * 10000 + N`.
- `BUILD_NUMBER` (root `gradle.properties`) is our fork increment — bumped on every `buildApk`,
  reset to `1` on each new upstream version (see the `upstream-new-version` skill).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)

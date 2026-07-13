# CLAUDE.md — guide for Claude Code in this repo

**shiroikuma-chizu** — 白い熊's fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) (OSM Automated
Navigation Directions), the offline OpenStreetMap map & navigation app for Android. Renamed to install
**side-by-side** with the official OsmAnd: applicationId `shiroikuma.chizu`, label **白い熊 地図**.

This repo (`ShiroiKuma0/shiroikuma-chizu`) is a fork. We track upstream (`osmandapp/OsmAnd`) on
`master` and layer our customizations on `custom`.

## Read this first

Before any work, read **`.claude/skills/build-apk/SKILL.md`** (canonical build + delivery) and
**`.claude/skills/upstream-new-version/SKILL.md`** (upstream sync + rebase).

## Fork workflow — READ THIS FIRST

### Git remotes & branches
- `origin` → `git@github.com:ShiroiKuma0/shiroikuma-chizu.git` (push here).
- `upstream` → `https://github.com/osmandapp/OsmAnd.git` (fetch only).
- `master` — mirrors `upstream/master`, **fast-forward only**, no fork work.
- `custom` — all our work; rebased onto `master` on each upstream sync.

Upstream does NOT tag releases in this repo; it cuts **release branches** (`r5.0` … `r5.3`) and bumps
the version literals on `master` when a release line closes. We base on `upstream/master` (the dev
line — this is what OsmAnd nightlies ship) and treat a change of the `versionName` literal in
`OsmAnd/build.gradle` as "new upstream version".

### Our customizations (install identity + build)
| What | Value | Where |
| --- | --- | --- |
| Installed app id | `shiroikuma.chizu` | `OsmAnd/build.gradle` → `productFlavors.androidFull.applicationId` |
| Code namespace | `net.osmand.plus` (**unchanged** from upstream) | `OsmAnd/build-common.gradle` |
| App label | `白い熊 地図` | `productFlavors.androidFull` `resValue "string", "app_name"` |
| FileProvider authority | `${applicationId}.fileprovider` (code derives it from `getPackageName()`) | `OsmAnd/AndroidManifest.xml` |
| Version tail | `versionName = "<base>+N"`, `versionCode = <base>*10000+N` | fork lines at the end of `defaultConfig` in `OsmAnd/build.gradle` |
| Build counter | `BUILD_NUMBER` | root `gradle.properties` (bumped by `buildApk`, reset to 1 on upstream sync) |
| Signing | gitignored `keystore.properties` → `~/.android-keystores/shiroikuma-chizu.jks` (alias `chizu`) | `signingConfigs.fork` + release buildType in `OsmAnd/build.gradle` |
| Build task | `buildApk` (assemble + copy to `~/tmp` + bump counter) | end of `OsmAnd/build.gradle` |
| Icon | black-yellow traced pin (yellow `#FFFF00` edge-trace on black) | `OsmAnd/res/drawable/ic_launcher_chizu_foreground.xml`, `values/chizu_colors.xml`, `mipmap-anydpi-v26/icon.xml`, `mipmap-*/icon.png` |

The upstream base version literals (`versionCode 5399` / `versionName "5.4.0"`) in
`OsmAnd/build.gradle` `defaultConfig` are **upstream's own lines** — they update automatically on
rebase; never edit them by hand. Our fork lines sit AFTER them and multiply/append.

### Versioning & APK naming
- Fork `versionName = "<upstreamBase>+N"` (e.g. `5.4.0+1`), `versionCode = <upstreamCode> * 10000 + N`
  (e.g. `5399 * 10000 + 1 = 53990001`). When upstream's code climbs, the new line's codes exceed the
  old — upgrades stay monotonic.
- `BUILD_NUMBER` (root `gradle.properties`) is bumped by `buildApk` after every successful build and
  reset to `1` on each new upstream version.
- APK: `shiroikuma-chizu_<versionName>_arm64-v8a.apk`, copied to `~/tmp/`.

### Build variant & external prerequisites
- We build **`androidFullOpenglArm64Release`** (full feature set incl. the 3D OpenGL core, arm64).
  `buildApk` wraps it.
- **Sibling checkout required:** `~/git/resources` = a clone of
  [osmandapp/OsmAnd-resources](https://github.com/osmandapp/OsmAnd-resources) (the gradle tasks
  reference it as `../../resources` for voice, fonts, styles, POI data, 3D models). It's a shallow
  clone; refresh it on each upstream sync: `git -C ~/git/resources pull`.
- The build **downloads at build time** (network needed): the mini world basemap + stars DB from
  `builder.osmand.net`, and the prebuilt OpenGL core AAR
  (`net.osmand:OsmAndCore_android*:master-snapshot`) from the `builder.osmand.net` ivy repo.

### Build commands
```bash
# Our build: signed release → ~/tmp + bump BUILD_NUMBER (use this)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew buildApk < /dev/null
# Release APK only (no copy / no bump)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk ./gradlew assembleAndroidFullOpenglArm64Release
```

### Toolchain
- JDK **21** at `/usr/lib/jvm/java-21-openjdk-amd64` (host default `java` is JDK 11; always set
  `JAVA_HOME`). Android SDK at `~/android-sdk` (`sdk.dir` in gitignored `local.properties`).
- `compileSdk`/`targetSdk` 35, `minSdk` 24, build-tools 35.0.0, AGP 8.7.3, Kotlin 2.1.20
  (see `versions.gradle` / root `build.gradle` — upstream's, tracks upstream).

## Repo layout (upstream OsmAnd)
- `OsmAnd/` — the Android app module (Java + some Kotlin/Compose; `src/net/osmand/plus/...`).
- `OsmAnd-java/` — the platform-independent core library; `OsmAnd-shared/` — KMP shared code;
  `OsmAnd-api/` — the AIDL API; `OsmAnd-telegram/` — the Telegram tracker app (not ours);
  `plugins/` — Nautical/SRTM/Skimaps plugin APK modules.
- Product flavors: `version` dimension (`androidFull` = ours, `nightlyFree`, `gplayFree`, `gplayFull`,
  `huawei`) × `coreversion` (`opengl` = ours, `opengldebug`, `legacy`) × `abi` (`arm64` = ours,
  `armv7`, `armonly`, `x86`, `fat`).

## Hard rules
- **After ANY functional change, build and deliver via the global `/after-build` skill** — no asking.
  `/after-build` runs `/adb-check` UNSANDBOXED, then `/adb-push` to `/sdcard/tmp/` if a phone is
  connected, else `/scp` to `skhw:~/tmp/`. Never `adb install` / `adb uninstall` — 白い熊 installs
  manually from `/sdcard/tmp/`.
- **Always run `adb` with `dangerouslyDisableSandbox: true`** (the sandbox blocks adb's server socket).
- **Never commit/push unprompted.** Build, let 白い熊 test, and only commit/push on an explicit
  **"Push"**. After an upstream rebase, `custom` needs `git push --force-with-lease origin custom`.
- **Every build bumps `+N`** — never reuse a build number, never overwrite an older APK in `~/tmp`.
- `keystore.properties`, `*.jks`, `local.properties` are gitignored — never commit them.
- On a new upstream version, run the `upstream-new-version` skill (proceed-gated feature table BEFORE
  the rebase, then rebase `custom`, reset `BUILD_NUMBER=1`, build `+1`).
- Keep our changes a **small, legible layer** on top of upstream — prefer new files / minimal edits;
  never rename the `net.osmand.plus` namespace.

## Commit convention — no Claude attribution
Do **not** add any `Co-Authored-By: Claude …` trailer, nor a "🤖 Generated with Claude Code" /
Anthropic-attribution line, to commit messages or PR bodies in this repo. End the message at the last
line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)

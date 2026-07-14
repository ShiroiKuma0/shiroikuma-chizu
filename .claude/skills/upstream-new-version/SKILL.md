---
name: upstream-new-version
description: Check osmandapp/OsmAnd for a new upstream version and sync the shiroikuma-chizu fork to it — show a proceed-gated TABULAR summary of the new upstream features BEFORE any rebasing, then fast-forward `master`, rebase `custom` onto it (reconciling conflicts — automatically when small, stopping to plan with 白い熊 when significant), reset BUILD_NUMBER, and build the new +1 via the build-apk skill. Use whenever 白い熊 runs /upstream-new-version, says a new OsmAnd version is out, or asks to update/sync/rebase to the latest upstream.
---

# Sync shiroikuma-chizu onto a new upstream OsmAnd version

This fork tracks [osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd). `master` mirrors
`upstream/master` (fast-forward only); `custom` carries our patches and is rebased onto each new
upstream base. This skill is the front-end: detect → **feature table + proceed gate** → mirror →
rebase → reset counter → build (via **build-apk**).

> **Never `git push` or `git commit` unprompted, and never `adb install`.** After the rebase +
> build you stop and let 白い熊 test; you only push on an explicit **"Push"** (`custom` needs
> `--force-with-lease` after a rebase; `master` is a plain fast-forward).

## Background — how OsmAnd versions work

OsmAnd does **not** tag releases in this repo. It cuts release **branches** (`r5.0` … `r5.3`) and
develops on `master`; when a release line closes, `master`'s version literals bump (e.g.
`versionName "5.4.0"` → `"5.5.0"` in `OsmAnd/build.gradle` `defaultConfig`). We base `custom` on
`upstream/master`. Two update modes:

- **Release bump** — the `versionName` literal on `upstream/master` differs from the one our base
  commit carries. Rebase onto the new tip; the new base literals flow in automatically (they are
  upstream's own lines); **reset `BUILD_NUMBER=1`** so the first new build is `+1`.
- **Master-tip refresh** — same `versionName`, but `upstream/master` advanced. Rebase onto the tip;
  **leave `BUILD_NUMBER` alone** — our `+N` keeps growing on the same base.

## Steps

### 1 — Preflight & detect

```bash
cd ~/git/shiroikuma-chizu
git status --porcelain          # must be empty — if not, STOP and surface it
git fetch upstream --tags
git fetch origin

OLD_BASE=$(git merge-base custom upstream/master)
OLD_VN=$(git show $OLD_BASE:OsmAnd/build.gradle | grep -oP 'versionName "\K[^"]+' | head -1)
NEW_VN=$(git show upstream/master:OsmAnd/build.gradle | grep -oP 'versionName "\K[^"]+' | head -1)
AHEAD=$(git rev-list --count $OLD_BASE..upstream/master)
echo "base $OLD_VN, upstream $NEW_VN, $AHEAD new commits"
```

- `NEW_VN != OLD_VN` → **release bump**. `AHEAD > 0` with same version → **master-tip refresh**.
- `AHEAD = 0` → report "already current (base `<OLD_VN>`)" and STOP.
- Scope the conflict surface up front — only files BOTH sides touched can conflict:

  ```bash
  comm -12 <(git diff --name-only $OLD_BASE..custom | sort) \
           <(git diff --name-only $OLD_BASE upstream/master | sort)
  ```

  Feed this list into the step-2 table's conflict flags; an empty intersection predicts a clean
  rebase.
- Also refresh the sibling resources checkout: `git -C ~/git/resources pull` (the build reads
  `../../resources`; a stale checkout breaks asset collection on new bases).

### 2 — Proceed-gated feature table (STOP — ALWAYS, before ANY rebasing)

**白い熊's standing requirement: before the rebasing step, ALWAYS present a proceed-gated, tabular,
descriptive summary of the new features introduced in the upstream version.** Both modes, every
time, no exceptions.

Build it from:
- `git log --no-merges --format='%h%x09%s' $OLD_BASE..upstream/master` (plus
  `git log --stat` to spot the areas touched);
- on a **release bump**, additionally the release announcement on the OsmAnd blog
  (`https://osmand.net/blog` — each X.Y release has a post with the user-facing feature list) so
  the table is *descriptive*, not raw commit subjects.

Present a markdown table, columns: **Area** (Map/Navigation/Routing/UI/Search/Plugins/Engine/Fix),
**Feature / change** (plain-language description), **Commits** (representative short hashes).
Group and summarize — dozens of commits become a dozen digestible rows. Flag rows that touch files
our patches own (`OsmAnd/build.gradle`, `OsmAnd/AndroidManifest.xml`,
`res/mipmap-anydpi-v26/icon.xml`, `res/mipmap-*/icon.png`, root `gradle.properties`, `.gitignore`)
— those are the likely conflict sites.

Also state: the mode (release bump vs master-tip refresh) and what it means for the version, the
stack size (`OLD_COUNT=$(git rev-list --count $OLD_BASE..custom)`), and the plan.

**Proceed only on 白い熊's explicit OK.**

### 3 — Fast-forward the mirror + back up custom

```bash
git checkout master && git merge --ff-only upstream/master   # if it can't FF, STOP — upstream rewrote history
git branch custom-pre-<NEW_VN or shortsha> custom            # safety backup (stable label, no dates)
```

### 4 — Rebase the custom stack

```bash
git checkout custom
git rebase --onto upstream/master $OLD_BASE custom
```

Conflict triage — reconcile, don't drop:
- **Small** (context drift around one of our edits; an obvious re-application of a known patch;
  whitespace) → resolve inline, `git rebase --continue`.
- **Significant** (upstream refactored a file we patch — e.g. restructured
  `OsmAnd/build.gradle`'s flavors/signing, moved the manifest provider block, replaced the launcher
  icon system; a semantic conflict; many commits conflicting) → **STOP, leave the rebase paused,
  and plan with 白い熊** (AskUserQuestion with concrete options). Never improvise a large
  reconciliation silently.

### 5 — Counter (mode-dependent)

- **Release bump:** set **`BUILD_NUMBER=1`** in the root `gradle.properties`. The base literals
  updated themselves in the rebase (upstream's lines).
- **Master-tip refresh:** leave `BUILD_NUMBER` untouched — `+N` keeps growing.

### 6 — Verify our customizations survived

| What | Expected | Where |
| --- | --- | --- |
| Installed app id | `applicationId "shiroikuma.chizu"` | `OsmAnd/build.gradle` → `productFlavors.androidFull` |
| App label | `resValue "string", "app_name", "白い熊 地図"` | same flavor block |
| Namespace | `net.osmand.plus` (unchanged) | `OsmAnd/build-common.gradle` |
| Fork version tail | the two fork `versionCode`/`versionName` lines at the END of `defaultConfig` | `OsmAnd/build.gradle` |
| Signing | keystore.properties loading + `signingConfigs.fork` + release `signingConfig` ternary | `OsmAnd/build.gradle` |
| Build task | `buildApk` (assemble + `~/tmp` copy + counter bump) | end of `OsmAnd/build.gradle` |
| Provider authority | `${applicationId}.fileprovider` | `OsmAnd/AndroidManifest.xml` |
| Icon | `@color/chizu_icon_background` + `@drawable/ic_launcher_chizu_foreground` in `icon.xml`; black-yellow `mipmap-*/icon.png` | `OsmAnd/res/…` |
| Counter | `BUILD_NUMBER` present (=1 on a release bump) | root `gradle.properties` |
| Gitignore | `keystore.properties`, `*.jks`, `*.apk`, `.claude/settings.local.json` ignored | `.gitignore` |

Stack completeness: `git rev-list --count upstream/master..custom` must equal `OLD_COUNT` from
step 2 (fewer ⇒ a commit was silently dropped — STOP and investigate).

### 7 — Build the new build via **build-apk**

Invoke the **build-apk** skill (`JAVA_HOME=… ANDROID_HOME=… ./gradlew buildApk < /dev/null`); it
delivers automatically via `/after-build`. On a release bump this is `<NEW_VN>+1`; on a refresh
it's the same base with the next `+N`.

### 8 — Stop

Let 白い熊 test on-device. On an explicit **"Push"**:
```bash
git push origin master
git push --force-with-lease origin custom
```
After the confirmed push, offer to delete the step-3 backup branch.

## Hard rules
- **The feature table + proceed gate fires before EVERY rebase** — both modes, always.
- `master` is FF-only; if it can't fast-forward, stop and discuss.
- Back up `custom` before rebasing; keep the backup until the new `custom` is pushed.
- Significant conflicts ⇒ plan with 白い熊; never push through a mis-resolved rebase to "get it
  building".
- `BUILD_NUMBER` resets to 1 only on a release bump; never reuse a `+N`.
- No Claude attribution in commits (see `CLAUDE.md`).

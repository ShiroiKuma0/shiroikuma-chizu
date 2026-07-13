---
name: publish-version
description: Publish the latest local build as a GitHub release of this fork — refresh the README (fork-style, major features), write a very specific CHANGELOG entry, tag the bare versionName, ensure the default branch is `custom`, and create the release with the ~/tmp APK attached. Use when 白い熊 says publish / release / cut a version / ship it to GitHub.
---

# Publish a version of shiroikuma-chizu to GitHub

Ship the **latest already-built** APK as a GitHub release, with a polished README and an exhaustive
CHANGELOG, landing the repo homepage on our fork work (`custom`).

> **Never rebuild to publish.** Attach the newest APK already in `~/tmp/`. The version you publish
> = that APK's versionName. If there is no `~/tmp/shiroikuma-chizu_*.apk`, stop and tell 白い熊 to
> build first — do **not** build it yourself.

## 0. Detect the version

```bash
APK=$(ls -t ~/tmp/shiroikuma-chizu_*.apk 2>/dev/null | head -1)
VERSION=$(basename "$APK" | sed -E 's/^shiroikuma-chizu_(.+)_arm64-v8a\.apk$/\1/')   # e.g. 5.4.0+3
TAG="$VERSION"   # bare versionName, NO "v" prefix
```

## 1. Ensure the homepage lands on `custom`

```bash
gh repo view ShiroiKuma0/shiroikuma-chizu --json defaultBranchRef --jq '.defaultBranchRef.name'
# if not "custom":
gh repo edit ShiroiKuma0/shiroikuma-chizu --default-branch custom
gh repo edit ShiroiKuma0/shiroikuma-chizu \
  --description "白い熊 地図 — an OsmAnd fork: side-by-side install (shiroikuma.chizu), black-yellow branding, own signing. Offline OSM maps & navigation."
```

## 2. Refresh `README.md` (fork-style, major features)

Keep the sister-repo house style (centered header, "**a fork of X with major additions**"):
- Centered header block: the icon (`OsmAnd/res/mipmap-xxxhdpi/icon.png`, width 120), title
  **白い熊 地図**, a one-line tagline (offline OSM maps & navigation), and a
  "A fork of [OsmAnd](https://github.com/osmandapp/OsmAnd) with …" sentence naming the headline
  features.
- The side-by-side install note (package `shiroikuma.chizu`).
- The latest-release line:
  ``**📥 Latest release: [`<VERSION>`](…/releases/latest)** — [all releases & APK downloads »](…/releases)``.
- A section per major fork feature (emoji heading + a few sentences), importance order — real,
  inviting prose, not a bullet dump.
- A closing "Built on OsmAnd" + license (GPL-3) note.

## 3. Update `CHANGELOG.md` — exhaustive

Add a new `## <VERSION> — <YYYY-MM-DD>` section above the previous one. List **everything** built
since the last release (cross-check `git log --oneline <lastTag>..custom`), grouped with `###`
subsections. On the very first publish, enumerate the whole fork layer (identity, versioning,
signing, icon, skills).

## 4. Commit, tag, push, release

```bash
git add README.md CHANGELOG.md
git commit -m "docs: changelog + README for <VERSION> release"
git push origin custom

git tag -a "$TAG" -m "白い熊 地図 $VERSION"
git push origin "$TAG"

# Release notes = this version's CHANGELOG section. LITERAL match (index($0,h)==1), NOT regex —
# the "+N" tail contains "+", a regex metachar.
mkdir -p .scratch
awk -v h="## $VERSION" 'index($0,h)==1{p=1;next} /^## /{if(p)exit} p' CHANGELOG.md > .scratch/release-notes.md
gh release create "$TAG" "$APK" -R ShiroiKuma0/shiroikuma-chizu \
  --target custom \
  --title "白い熊 地図 $VERSION" \
  --notes-file .scratch/release-notes.md
```

Verify: `gh release view "$TAG" -R ShiroiKuma0/shiroikuma-chizu --json url,assets`. Report the URL.

## Hard rules
- **Always pin `-R ShiroiKuma0/shiroikuma-chizu`** — the worktree has an `upstream` remote and bare
  `gh` calls would hit osmandapp/OsmAnd.
- Scratch files go in the gitignored `.scratch/`, never `~/tmp/`. `gh`/`git push` run UNSANDBOXED.
- Tag is the **bare versionName** (e.g. `5.4.0+3`), no `v` — distinct from upstream's branches.
- Don't touch `master` here; releases are cut from `custom`.
- **No Claude/Anthropic attribution** in the commit, tag, or release body.

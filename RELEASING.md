# Releasing

<!--- TOC -->

* [What a release is](#what-a-release-is)
* [Versioning](#versioning)
* [Cut a release](#cut-a-release)
  * [1. Pick a version](#1-pick-a-version)
  * [2. Dry run](#2-dry-run)
  * [3. Create the release branch](#3-create-the-release-branch)
  * [4. Release](#4-release)
  * [5. Merge the changelog back to `main`](#5-merge-the-changelog-back-to-main)
  * [6. Check it](#6-check-it)
* [The changelog](#the-changelog)
* [If it fails halfway](#if-it-fails-halfway)
* [Rehearsing locally](#rehearsing-locally)
* [Publishing to Maven Central](#publishing-to-maven-central)
* [What must never be published](#what-must-never-be-published)
* [Repository setup](#repository-setup)

<!--- END -->

Same process as [element-call-ios](https://github.com/element-hq/element-call-ios/blob/main/RELEASING.md): a
release is cut by the `Release` workflow from a `release/<version>` branch, never by hand and never from `main`.
What differs here is what a release *is*: this is a binary library, so the release carries artifacts.

## What a release is

One version for the four artifacts and the BOM, under `io.element.android`:

| Artifact | Project |
| :--- | :--- |
| `element-call-api` | `call/api` |
| `element-call` | `call/impl` |
| `element-call-ui` | `call/ui` |
| `element-call-test` | `call/test` |
| `element-call-bom` | `bom` |

Each is published by the `io.element.call.publish` convention plugin (`com.vanniktech.maven.publish`) as its
release variant with a sources jar, an empty javadoc jar, Gradle module metadata, and a POM naming both licences.
The published `element-call` POM depends on the core at `io.element.android:matrix-rtc-android`, which
`element-hq/matrix-rust-rtc` publishes to Maven Central; see [What must never be published](#what-must-never-be-published).

They go to **Maven Central**, and the GitHub release carries them too. `scripts/release.sh` publishes the five
modules to a Maven layout under `build/dist` and flattens it into `build/release-assets`: for each module the `.aar`
or `.jar`, `.pom`, `.module`, `-sources.jar` and `-javadoc.jar`, named `<artifactId>-<version>…`, plus one
`SHA256SUMS`. The workflow attaches them, with the sample APK, to the release on the tag `v<version>`. A host, or a
fork without a Central namespace, can resolve them from `releases/download/v<version>/` through an Ivy repository
that reads the module metadata (README, "Consuming a release"), so **the asset names are part of the contract**.
The core is not among the assets: it comes from Maven Central either way.

## Versioning

Semantic versioning at `0.x`: the host pins each artifact exactly, so at `0.x` a renamed port method or a changed
test tag is a breaking change and gets a **minor** bump. Everything else is a **patch**. A prerelease, `0.2.0-rc.1`, is what a host tries before it becomes the
version everyone gets: marked prerelease on GitHub, so it never becomes "latest".

Tags are `v<version>`, as `matrix-rust-rtc`'s are and as the Ivy pattern a host declares expects. The workflow
input and `VERSION_NAME` are the bare version, `0.2.0-rc.1`, which is also what a host writes in its catalog.

**Nothing is bumped ahead of time.** `VERSION_NAME` in `gradle.properties` is the one version in the repository
and `scripts/release.sh` stamps it into the commit it tags, so a contributor never edits it and a pull request never
carries a version. On `main` between releases it reads as the previous release (`0.0.0` before the first), which is
only ever visible in a build made from this repository rather than from a tag; a local publication overrides it
(`-PVERSION_NAME=0.0.0-local`, `docs/local_stack.md`).

## Cut a release

Releases are cut from a **`release/<version>` branch**, never from `main`. `main` is protected, and nothing in
this pipeline needs a token that can bypass that: the release commit and the tag go to the release branch, and the
changelog reaches `main` afterwards through an ordinary reviewed pull request. The workflow runs on the built-in
`GITHUB_TOKEN`.

### 1. Pick a version

- **Patch** (`0.2.0` → `0.2.1`) for fixes behind an unchanged API and SDK pin.
- **Minor** (`0.2.1` → `0.3.0`) for anything else. At `0.x` this is also where breaking changes go, which is why
  every such pull request wants the `pr-api` label: `.github/release.yml` gives it its own "⚠️ API Changes"
  heading precisely so a host reads it before bumping.
- **A prerelease** (`0.3.0-rc.1`) when a host should try it first. The core this library pins (`matrix_rtc` in
  `gradle/libs.versions.toml`) must not be a prerelease when this one is not: a stable version here would present
  that edge as settled.

### 2. Dry run

**Actions → Release → Run workflow**, on `main`, with the version and `dry_run` left **on**. It is on by default, and
a dry run can be dispatched from any branch because it publishes nothing; it does build everything, so it takes as
long as a real run. Dispatch it from the branch you intend to release: `main` for an ordinary release, the release
branch itself for a hotfix, whose notes are scoped to its own line of history.

Then **read the notes it prints.** They are built from the `pr-` labels on the pull requests merged since the last
tag, and this is the moment to check them, because:

> Anything under **Others** is a pull request that was merged without a `pr-` label.

`.github/workflows/pr-label.yml` fails a pull request without exactly one, so this should only catch older ones
or a label removed after merge. When one slips through, add the label to the *merged* pull request and
dry-run again: the notes are generated at release time, not at merge time, so a late label still works. The same
lateness takes an entry *out*: `pr-task` on a merged pull request excludes it from the notes entirely.

The run's artifact holds the notes, the two files the release commit will carry, and the release assets exactly as
the release would attach them. The dry run changes nothing: no commit, no tag, no release, no push.

### 3. Create the release branch

```bash
git fetch --tags --prune
git switch -c release/0.2.0 origin/main
git push -u origin release/0.2.0
```

The name must be exactly `release/<version>`: the workflow refuses to publish when the branch and the version
disagree, so a run dispatched against the wrong branch cannot mint a tag off it.

### 4. Release

**Actions → Release → Run workflow**, this time with `release/0.2.0` selected and `dry_run` **off**. It will:

1. rename `## Unreleased` in `CHANGES.md` to `## 0.2.0 - <date>`, fill it with the generated notes, open a fresh
   empty `## Unreleased` above it, and stamp `VERSION_NAME=0.2.0`;
2. build, run the unit tests and the quality checks, publish to the runner's local Maven repository, build the
   minified consumer against it, lay out the release assets and build the consumer again against them the way a
   host resolves a GitHub release;
3. draft the GitHub release from the same notes, with the assets and the sample APK, marked prerelease if the
   version has a hyphen;
4. publish to Maven Central, while the `MAVEN_CENTRAL_PUBLISH` variable is `true` (see below), before anything
   else is public, so that a failure there leaves only the draft;
5. commit the two files as `Release 0.2.0`, tag `v0.2.0`, and push the commit and tag **atomically**, so the tag
   can never exist without the changelog entry and the version that describe it;
6. publish the GitHub release.

The job summary then links the release and the pull request to open next.

### 5. Merge the changelog back to `main`

Open the pull request the summary links to, `release/0.2.0` → `main`, and get it reviewed like any other. **Merge it
with a merge commit, not a squash.** A squash creates a new commit and leaves the tagged one reachable only through
the tag, so `git describe` on `main` stops finding the release and "which release contains this commit?" stops
having an answer. Gradle resolves the assets either way; this is about the history staying legible.

The tag is already public by now, and the release does not depend on this pull request landing. If review finds a
problem with the changelog *text*, fix it on `main` in a follow-up; the tag stays put. If it finds a problem with the
*code*, that is a new release rather than an edit to this one.

### 6. Check it

```bash
git ls-remote --tags https://github.com/element-hq/element-call-android | grep v<version>
gh release view v<version>
```

And resolve it the way a host will, once Central has synced it (up to half an hour): in a scratch Gradle project
with only `google()` and `mavenCentral()` and
`implementation(platform("io.element.android:element-call-bom:<version>"))` plus the four artifacts, run
`./gradlew dependencies --configuration releaseRuntimeClasspath` and check that every `element-call-*` and
`matrix-rtc-android` line resolves. Element X's `-PelementCallLocalVersion` must **not** be set: that would read
`~/.m2` instead.

## The changelog

[`CHANGES.md`](CHANGES.md) keeps a `## Unreleased` heading at the top. Releasing renames it to
`## <version> - <date>` and opens an empty one above, so the file reads newest-first and always has somewhere for
the next entry to go.

**For an ordinary change there is nothing to write.** Label the pull request and give it a title that reads as a
changelog line; the release generates the list from those.

**For a change no host could observe, label it `pr-task`**: CI plumbing, test-only churn, a repository chore.
`.github/release.yml` excludes that label before categorising, so the pull request gets no line under any heading,
not even the *Others* catch-all. An absent entry is then a decision someone made and a reviewer could see.

**Write under `## Unreleased` by hand only when a host has to act**: a renamed port method, a port gaining a
requirement, a new build setting, a core or SDK bump. Anything found there at release time is carried into that
version's section and kept *above* the generated list, where someone bumping the version will actually read it.

The workflow refuses to release if the `## Unreleased` heading is missing, since there would be nowhere to record
the version.

## If it fails halfway

The mutating steps are, in order: **draft the release** with its assets, **publish to Maven Central**, **push**
(commit and tag, atomically), then **publish the release**. Everything before them changes nothing, so a failure there
needs no cleanup: fix the cause and dispatch again.

| What happened | What to do |
| --- | --- |
| Drafting failed | Nothing is public and no tag is pushed. Dispatch again, same branch and version: the step finishes an existing draft, uploading its assets again. |
| Central publish failed | Only a draft exists and no tag is pushed. Look at the deployment on central.sonatype.com, fix the cause, and dispatch again. |
| Central published, push failed | The version is on Central, but no tag is pushed. Central refuses a version twice, so set `MAVEN_CENTRAL_PUBLISH` to `false`, dispatch again, same branch and version, then set it back to `true`. The push is `--atomic`, so the branch and the tag moved together or not at all. |
| Push landed, publishing failed | Dispatch again, same branch and version: both release steps are idempotent. Should an earlier check refuse first, do it by hand: `gh release edit v<version> --draft=false --verify-tag`. |
| Published, an asset is missing or wrong | Releases are immutable, so nothing can be added or replaced: release a new patch. |

**Never delete and re-push a tag to retry.** A host may already have resolved the assets under it, and Gradle
caches by module and version, so a tag that changes meaning is far worse than a missing release page. Release a new
patch instead.

## Rehearsing locally

`scripts/release.sh` is the whole of the validation, note generation, changelog, version stamp and build, and it
**never touches the remote**: no commit, no tag, no push, no release. The worst it can do is leave a modified
`CHANGES.md` and `gradle.properties` in the working tree, a `release-notes.md` (gitignored), and build output.

```bash
gh auth login                             # it asks GitHub for the notes
git fetch --tags --prune --prune-tags      # it validates against local tags
./scripts/release.sh 0.2.0 --skip-build   # the notes, the changelog and the stamp, in seconds
./scripts/release.sh 0.2.0                # the whole thing, assets in build/release-assets
git restore CHANGES.md gradle.properties
```

The `git fetch` is not optional: the script reads **local** tags to decide what the previous release was and
whether the version goes backwards, so a stale clone will happily tell you a version is fine when the remote
disagrees. CI has no such problem; it checks out with `fetch-depth: 0`.

## Publishing to Maven Central

`release.yml` publishes while the repository variable `MAVEN_CENTRAL_PUBLISH` is `true`. Setting it to anything else
turns the step off, for instance to cut a release that only exists on GitHub. It needs:

- **A Central Portal user token** for the `io.element.android` namespace, as the secrets `MAVEN_CENTRAL_USERNAME`
  and `MAVEN_CENTRAL_PASSWORD`: generated under *View Account* on central.sonatype.com, never the login itself.
- **A signing key**, as `MAVEN_SIGNING_KEY` (the armoured private key) and `MAVEN_SIGNING_KEY_PASSWORD` (its
  passphrase). Signing is off by default so a local publish needs no key; `-PRELEASE_SIGNING_ENABLED=true` turns it on.

A Central release is immutable and public: a version that reached it is spent, whatever happens next.

## What must never be published

This repository never publishes the core: the artifact is `element-hq/matrix-rust-rtc`'s, and a local build of it
(`0.0.0-local`, `docs/local_stack.md`) lives in `~/.m2` only. The release script only ever publishes the modules on
the `io.element.call.publish` plugin.

## Repository setup

One-off, and required before the first release:

```bash
# The labels .github/release.yml categorises by, in its order, then pr-task, which it excludes rather than
# categorises, plus the screenshot trigger label. --force so this is safe to re-run.
while read -r label colour; do
    gh label create "$label" --color "$colour" --force
done <<'LABELS'
pr-feature        0E8A16
pr-change         1D76DB
pr-bugfix         D73A4A
pr-api            D93F0B
pr-build          C5DEF5
pr-doc            0075CA
pr-wip            FBCA04
pr-dependencies   0366D6
pr-misc           CFD3D7
pr-task           EDEDED
Record-Screenshots FEF2C0
LABELS
```

| Secret or variable | Used for |
| --- | --- |
| *(none)* | The release workflow runs entirely on the built-in `GITHUB_TOKEN`. |
| `MAVEN_CENTRAL_PUBLISH` (variable), `MAVEN_CENTRAL_*`, `MAVEN_SIGNING_*` | The Central publish only. |

Three settings this depends on:

- **Release immutability is on** (Settings → General → Releases), so a published release's assets and tag never
  change under a host that resolved them.
- **`release/*` must not be protected**, or the workflow cannot push the release commit to it.
- **The pull request back to `main` is opened by hand.** A pull request opened with `GITHUB_TOKEN` does not trigger
  other workflows, so the checks would not run on it; the job summary gives you the link rather than the workflow
  creating it.

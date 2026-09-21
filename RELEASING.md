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

One version for the five artifacts and the BOM, under `io.element.android`:

| Artifact | Project |
| :--- | :--- |
| `element-call-api` | `call/api` |
| `element-call` | `call/impl` |
| `element-call-ui` | `call/ui` |
| `element-call-matrix` | `call/matrix` |
| `element-call-test` | `call/test` |
| `element-call-bom` | `bom` |

Each is published by the `io.element.call.publish` convention plugin (`com.vanniktech.maven.publish`) as its
release variant with a sources jar, an empty javadoc jar, Gradle module metadata, and a POM naming both licences.
The published `element-call` POM depends on the core at `io.element.android:matrix-rtc-android`, its own
coordinate, with an `aar` artifact selector; see [What must never be published](#what-must-never-be-published).

Until Maven Central, **the GitHub release is the repository**. `scripts/release.sh` publishes the six modules to a
Maven layout under `build/dist` and flattens it into `build/release-assets`: for each module the `.aar` or `.jar`,
`.pom`, `.module`, `-sources.jar` and `-javadoc.jar`, named `<artifactId>-<version>…`, plus one `SHA256SUMS`. The
workflow attaches them, with the sample APK, to the release on the tag `v<version>`. A host resolves them from
`releases/download/v<version>/` through an Ivy repository that reads the module metadata (README, "Consuming a
release"), so **the asset names are part of the contract**. The core is not among the assets: the same host
resolves it from the core's own release.

## Versioning

Semantic versioning at `0.x`: the host pins each artifact exactly, so at `0.x` a renamed port method or a changed
test tag is a breaking change and gets a **minor** bump. So does a change of the Matrix Rust SDK version
`call/matrix` is compiled against, because the published POM declares it and the host's own pin must be at least
that. Everything else is a **patch**. A prerelease, `0.2.0-rc.1`, is what a host tries before it becomes the
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
  every such pull request wants the `PR-Api` label: `.github/release.yml` gives it its own "⚠️ API Changes"
  heading precisely so a host reads it before bumping.
- **A prerelease** (`0.3.0-rc.1`) when a host should try it first. The core this library pins is itself a
  prerelease (`matrix-rust-rtc` `0.3.0-rc.1`); a stable version here would present that edge as settled, so
  **the first stable release waits for the core to reach one.** Until then, release `0.1.0-rc.N`.

### 2. Dry run

**Actions → Release → Run workflow**, on `main`, with the version and `dry_run` left **on**. It is on by default, and
a dry run can be dispatched from any branch because it publishes nothing; it does build everything, so it takes as
long as a real run. Dispatch it from the branch you intend to release: `main` for an ordinary release, the release
branch itself for a hotfix, whose notes are scoped to its own line of history.

Then **read the notes it prints.** They are built from the `PR-` labels on the pull requests merged since the last
tag, and this is the moment to check them, because:

> Anything under **Others** is a pull request that was merged without a `PR-` label.

Nothing enforces that label at merge time. When one slips through, add the label to the *merged* pull request and
dry-run again: the notes are generated at release time, not at merge time, so a late label still works. The same
lateness takes an entry *out*: `PR-Task` on a merged pull request excludes it from the notes entirely.

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
   host resolves the release;
3. commit the two files as `Release 0.2.0`, tag `v0.2.0`, and push the commit and tag **atomically**, so the tag
   can never exist without the changelog entry and the version that describe it;
4. create the GitHub release from the same notes, with the assets and the sample APK, marked prerelease if the
   version has a hyphen;
5. publish to Maven Central, only if the gate below is open.

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

And resolve it the way a host will: in a scratch Gradle project with only the two Ivy blocks from the README and
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

**For a change no host could observe, label it `PR-Task`**: CI plumbing, test-only churn, a repository chore.
`.github/release.yml` excludes that label before categorising, so the pull request gets no line under any heading,
not even the *Others* catch-all. An absent entry is then a decision someone made and a reviewer could see.

**Write under `## Unreleased` by hand only when a host has to act**: a renamed port method, a port gaining a
requirement, a new build setting, a core or SDK bump. Anything found there at release time is carried into that
version's section and kept *above* the generated list, where someone bumping the version will actually read it.

The workflow refuses to release if the `## Unreleased` heading is missing, since there would be nowhere to record
the version.

## If it fails halfway

The mutating steps are, in order: **push** (commit and tag, atomically), **create the release**, then the gated
Central publish. Everything before them changes nothing, so a failure there needs no cleanup: fix the cause and
dispatch again.

| What happened | What to do |
| --- | --- |
| Push failed | Nothing landed: the push is `--atomic`, so the branch and the tag move together or not at all. Fix the cause and dispatch again. |
| Push landed, release creation failed | Dispatch again, same branch and version. The release step is idempotent: it leaves an existing release alone and creates one for a tag that is already pushed. Should an earlier check refuse first, do it by hand: `gh release create v<version> --verify-tag --title v<version> --notes-file release-notes.md build/release-assets/*` (add `--prerelease` for a hyphenated version), taking the notes and the assets from the run's `release-<version>` artifact, uploaded before anything is published for exactly this case. |
| Release created, an asset is missing | `gh release upload v<version> <file>` from the same artifact. Never replace an asset a host may already have resolved. |

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

`release.yml` publishes only when the repository variable `MAVEN_CENTRAL_PUBLISH` is `true`, because a Central
release is immutable and public and three things have to be in place first:

- **A Central Portal account** for the `io.element.android` namespace (Element owns it; the `element-call-embedded`
  artifacts are already under it), as the secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
- **A signing key**, as `MAVEN_SIGNING_KEY` (the armoured private key) and `MAVEN_SIGNING_KEY_PASSWORD`. Signing is
  off by default so a local publish needs no key; `-PRELEASE_SIGNING_ENABLED=true` turns it on.
- **A core artifact on Central.** The `element-call` POM depends on `io.element.android:matrix-rtc-android`, which
  `element-hq/matrix-rust-rtc` publishes to GitHub Packages (a token even to read) and attaches to its GitHub
  release, not yet to Maven Central. A Central release of `element-call` would resolve for a host only with the Ivy
  repository over that release added by hand, which is what a Central release exists to avoid. Until the core is on
  Central (`docs/FEEDBACK.md` item 29), the gate stays closed and the exit condition of the plan's L4 - "resolves
  from Maven Central in a clean project" - is not met.

Two more things a first release should say out loud in its notes: the Element Call compatibility is pinned to the
state-event generation while the library builds against the released SDK (`docs/FEEDBACK.md`), and the core is a
prerelease.

## What must never be published

`rtc/local` publishes the core AAR in place to the **local** Maven repository as
`io.element.android:matrix-rtc-android:<MATRIX_RTC_VERSION>`, the core's own coordinate, so that `tests/consumer`
and a host on layer 3 resolve the dependency the library's POMs name. That project has no remote repository
configured, on purpose: the artifact is the core's to publish, and a copy of it, or a local build under its name,
must never appear on Central from this repository, nor among this repository's release assets (the release script
only ever publishes the modules on the `io.element.call.publish` plugin, which `rtc/local` is not). When the core
is on Central, this publication and the Ivy repository in `settings.gradle.kts` go, and nothing else changes.

## Repository setup

One-off, and required before the first release:

```bash
# The labels .github/release.yml categorises by, in its order, then PR-Task, which it excludes rather than
# categorises, plus the screenshot trigger label. --force so this is safe to re-run.
while read -r label colour; do
    gh label create "$label" --color "$colour" --force
done <<'LABELS'
PR-Feature        0E8A16
PR-Change         1D76DB
PR-Bugfix         D73A4A
PR-Api            D93F0B
PR-Build          C5DEF5
PR-Doc            0075CA
PR-Wip            FBCA04
PR-Dependencies   0366D6
PR-misc           CFD3D7
PR-Task           EDEDED
Record-Screenshots FEF2C0
LABELS
```

| Secret or variable | Used for |
| --- | --- |
| *(none)* | The release workflow runs entirely on the built-in `GITHUB_TOKEN`. |
| `MAVEN_CENTRAL_PUBLISH` (variable), `MAVEN_CENTRAL_*`, `MAVEN_SIGNING_*` | The gated Central publish only. |

Two settings this depends on:

- **`release/*` must not be protected**, or the workflow cannot push the release commit to it.
- **The pull request back to `main` is opened by hand.** A pull request opened with `GITHUB_TOKEN` does not trigger
  other workflows, so the checks would not run on it; the job summary gives you the link rather than the workflow
  creating it.

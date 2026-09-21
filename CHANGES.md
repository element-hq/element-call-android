# Changes

Entries under a released version are **generated when the release is cut**, from the `pr-` label on each merged
pull request (`.github/release.yml`), see [RELEASING.md](RELEASING.md). There is nothing to add here for an
ordinary change: label the pull request and give it a title that reads as a changelog line.

`Unreleased` is for the exception, something a host has to **act** on: a renamed port method, a port gaining a
requirement, a new build setting, a core or SDK bump that changes what the host resolves. Write that here by hand,
and the release carries it into its own section above the generated list, where a host bumping the version will
actually read it. The matrix-rust-rtc core's own history is in `docs/CHANGELOG.md`.

## Unreleased

_Nothing yet._

## 0.1.0-rc.1 - 2026-09-21

First release, for Element X to integrate behind its `NativeCall` flag. What a host has to know:

- Core: matrix-rust-rtc `0.3.0-rc.1` as `io.element.android:matrix-rtc-android`, resolved from its GitHub release
  through an Ivy repository (README, "Consuming a release"); bindings in `org.matrix.rtc`.
- Element Call compatibility is pinned to the state-event generation; calls ring in that mode.
- Raised hands and reactions are read and dropped until plan 002.
- Screen sharing is opt-in: `ElementCallOptions(isScreenSharingEnabled = true)`, off by default. A host turning it
  on also declares the `mediaProjection` service type and `FOREGROUND_SERVICE_MEDIA_PROJECTION` (README, host
  requirements).



### What's Changed

🧱 Build
* chore: Update to matrix-rust-rtc v0.3.0-rc.1 by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/5


**Full Changelog**: https://github.com/element-hq/element-call-android/commits/v0.1.0-rc.1


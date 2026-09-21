# Changes

The library's changelog, one section per release, newest first. The matrix-rust-rtc core's own history is
in `docs/CHANGELOG.md`. Entries are the pull request titles (`CONTRIBUTING.md`).

## Unreleased

First release, for Element X to integrate behind its `NativeCall` flag. What a host has to know:

- Core: matrix-rust-rtc `0.3.0-rc.1` as `io.element.android:matrix-rtc-android`, resolved from its GitHub release
  through an Ivy repository (README, "Consuming a release"); bindings in `org.matrix.rtc`.
- Element Call compatibility is pinned to the state-event generation; calls ring in that mode.
- Raised hands and reactions are read and dropped until plan 002.

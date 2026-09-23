# Changes

Entries under a released version are **generated when the release is cut**, from the `pr-` label on each merged
pull request (`.github/release.yml`), see [RELEASING.md](RELEASING.md). There is nothing to add here for an
ordinary change: label the pull request and give it a title that reads as a changelog line.

`Unreleased` is for the exception, something a host has to **act** on: a renamed port method, a port gaining a
requirement, a new build setting, a core or SDK bump that changes what the host resolves. Write that here by hand,
and the release carries it into its own section above the generated list, where a host bumping the version will
actually read it. The matrix-rust-rtc core's own history is the core's:
[its CHANGELOG](https://github.com/element-hq/matrix-rust-rtc/blob/main/CHANGELOG.md) and its release notes.

## Unreleased

- **Needs matrix-rust-rtc with the call tile roster** (`MediaSession.nextRoster`). Until it is released and pinned,
  build it with `./tools/rtc/build-rust-rtc`.
- `MatrixRtcCall` gains `tiles` and `localState`. A fake or a host transport implementing it must supply both.
- `ElementCallSnapshot`: `tiles` (remote, ranked) and `ownTile` added. `spotlightMemberId` becomes `spotlightTileId`,
  the head of `tiles`. `activeSpeakerIds` is removed; speaking is `MatrixRtcTile.isSpeaking`.
- A member sharing their screen is two tiles, the share a hero. Test tags and keys are unchanged
  (`memberId`, `memberId#SCREEN_SHARE`).
- `isScreenSharing` now reflects the screen-share publication rather than what was asked for.
- `toCallTiles` and `screenShareTileId` are gone; build a `CallTileData` (formerly `CallParticipant`) with `MatrixRtcTile.toCallTileData`.
- Composables renamed: `CallParticipantTile` is `CallTile`, and `ElementCallPictureInPictureView` is
  `ElementCallPictureInPictureContent`. Same parameters.

## 0.1.0-rc.5 - 2026-09-25



### What's Changed

🐛 Bugfixes
* bugfix: back should minimize the call by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/26


**Full Changelog**: https://github.com/element-hq/element-call-android/compare/v0.1.0-rc.4...v0.1.0-rc.5

## 0.1.0-rc.4 - 2026-09-24



### What's Changed

🐛 Bugfixes
* Fix: Keys wrongly discarded causing no video or audio. by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/23


**Full Changelog**: https://github.com/element-hq/element-call-android/compare/v0.1.0-rc.3...v0.1.0-rc.4

## 0.1.0-rc.3 - 2026-09-23



### What's Changed

✨ Features
* Add mute and hang up to picture-in-picture by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/15

🐛 Bugfixes
* Fix Picture-in-picture window stays open after the call ends by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/14
* Size the floating tile to the video's aspect ratio by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/17
* Keep the notification's mute button in sync with the call mute state by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/18


**Full Changelog**: https://github.com/element-hq/element-call-android/compare/v0.1.0-rc.2...v0.1.0-rc.3

## 0.1.0-rc.2 - 2026-09-22



### What's Changed

✨ Features
* Add an overflow menu to see the component version by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/9

⚠️ API Changes
* Feat: Make the screen sharing feature opt-in by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/10

🧱 Build
* chore: Bump rust-rtc to 0.3.0-rc.2 by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/11

Others
* Release 0.1.0-rc.1 by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/8


**Full Changelog**: https://github.com/element-hq/element-call-android/compare/v0.1.0-rc.1...v0.1.0-rc.2

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


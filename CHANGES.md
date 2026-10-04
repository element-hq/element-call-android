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

- The RTC API follows the core's room-first names: `MatrixRtcClient.room()` opens a `MatrixRtcRoom`, whose `joinCall()`
  returns a `MatrixRtcCall` (was `MatrixRtcSession`), whose `connectMedia()` returns a `MatrixRtcMediaSession` (was
  `MatrixRtcCall`). `ElementCallStack.Builder.rtcService` is `rtcClient`. `MatrixRtcTransport` is gone: the join picks it.
- `ElementCallOptions.elementCallCompat` is `membershipFormat`, a `MatrixRtcMembershipFormat` (`CURRENT`, `STICKY2025`,
  `ROOM_STATE`; were `OFF`, `STICKY_EVENTS`, `STATE_EVENTS`).

## 0.1.0-rc.6 - 2026-09-29

- `call/matrix` requires Matrix Rust SDK `org.matrix.rustcomponents:sdk-android` **26.09.26** (was 26.09.08), the
  release Element X moved to. Its widget bindings changed binary shape (`WidgetCapabilitiesProvider.acquireCapabilities`
  became `suspend`, `WidgetDriverHandle.send` stopped being one), so this library built against 26.09.08 fails at
  runtime on 26.09.26 and the other way round: a host on 26.09.26 needs this version, and one still on an older SDK
  has to move with it.
- Core: matrix-rust-rtc `0.4.0-rc.1` (the call tile roster, `MediaSession.nextRoster`). A host's Ivy repository over
  the core's release asset (README, "Consuming a release") must point at the `v0.4.0-rc.1` asset.
- A tile's kind is a `MatrixRtcTileKind` - `PERSON` or `SCREEN_SHARE` - not a stream kind: `MatrixRtcTileId.kind`,
  with `videoStreamKind` for the stream a tile draws. `MatrixRtcParticipant.cameraTile()` is `personTile()`.
- `MatrixRtcCall` gains `tiles` and `localState`. A fake or a host transport implementing it must supply both.
  `MatrixRtcTileRef` carries `userId`, so a tile outside the detail window still has a name and an avatar.
- `ElementCallOverlay` takes a nullable controller. Compose it always, with `null` before there is one: switching
  between it and bare content rebuilds the host's content and loses its state.
- `ElementCallSnapshot`: `roster` (every remote tile in rank order, with full records for the declared detail window)
  and `ownTile` added; `tiles` and `spotlightMemberId`/`spotlightTileId` are gone. The spotlight is the layout's
  choice, not the head of the ranking. `activeSpeakerIds` is removed; speaking is `MatrixRtcTile.isSpeaking`.
- `ElementCallController.setDetailWindow` and `MatrixRtcCall.setDetailWindow` (`MatrixRtcDetailWindow(ranks, also)`):
  the layout declares which tiles it needs full records for; a host transport implementing `MatrixRtcCall` must
  supply it. Without a declaration the core sends detail for every tile.
- `MatrixRtcVideoConstraints` gains `isEnabled`, with `live(w, h)`, `Paused` (was `NotVisible`) and `Released`;
  `Released` lets a stream go rather than pausing it.
- The call screen is the spec 003 layout: a two-column 4:3 grid scrolling vertically under a sticky 16:9 spotlight,
  the control bar floating over it in both orientations. There is no spotlight without a hero unless the call has
  more than ten remote members, and a direct message is a grid of two: the full-bleed one-to-one arrangement, its
  `CallLayout`, `CallTileAppearance.FullBleed`/`Thumbnail` and `ElementCallScreenState.spotlightTileId` are gone.
  `CallTile` takes `appearance` (`Grid`/`Spotlight`), `fit` and `showName` in place of `isSpotlight`.
- `rememberElementCallScreenState` takes a `CallSpotlightMemory` (`rememberCallSpotlightMemory()` by default): what
  the spotlight shows survives rotation and minimising, and `ElementCallPictureInPictureContent` and
  `ElementCallFloatingTile` take the same `spotlightId` to show it (`pictureInPictureCandidate`).
- `ElementCallStack.Builder.rtcService(service)` builds the stack over a `MatrixRtcService` of the host's own, or a
  fake, in place of the Rust core.
- Double-tapping a tile fills the stage with it (spec 000): fitted with no cropping, pinch to zoom up to 4x, a single
  tap for the HUD. `ElementCallScreenState` gains `fullscreenTileId` and `isFullscreenChromeVisible`;
  `CallTileAppearance.Fullscreen` and `CallTile`'s `videoTransform` are new; `ElementCallTestTags.EXIT_FULLSCREEN`
  is the HUD's close button, which leaves fullscreen and never the call.
- `ElementCallStageDriver`, provided with `ElementCallStageDriverProvider`, drives the stage the way a finger would
  (scroll, hero switch, fullscreen) for a harness playing layout scenarios; a host never needs one.
- A member sharing their screen is two tiles, the share a hero. Test tags and keys are unchanged
  (`memberId`, `memberId#SCREEN_SHARE`).
- `isScreenSharing` now reflects the screen-share publication rather than what was asked for.
- `MatrixRtcCallEvent.ActiveSpeakers` and `MatrixRtcSpeakingMember` are removed; speaking is `MatrixRtcTile.isSpeaking`.
  Remote audio playback follows the tile roster's order rather than `StreamStarted`/`StreamStopped`, so a lagging
  event consumer can no longer leave anyone silent. `MatrixRtcCall.participants` and `ElementCallSnapshot.participants`
  are read once at connect and no longer kept live; `ElementCallSnapshot.hasVideo` reads the tiles.
- The stats overlay's `NO MIC STREAM` line and `CallTileData.hasMicrophone` are gone; the call layer still logs
  once, at warn, when a member's microphone cannot be opened.
- Receive statistics are per stream and bounded to what is drawn. `MatrixRtcCall.receiveStats` and
  `ElementCallSnapshot.receiveStats` are keyed by `MatrixRtcStreamRef(memberId, kind)` (were member id,
  microphone only), and hold each composed tile's stream plus its member's microphone. The screen declares
  the composed set through `ElementCallController.setComposedTiles`; a host transport implementing
  `MatrixRtcCall` must supply it, and one round trip (`receiveStatsFor`) per second serves the whole set.
  `TileStats` gains `audioStats`.
- `toCallTiles` and `screenShareTileId` are gone; build a `CallTileData` (formerly `CallParticipant`) with `MatrixRtcTile.toCallTileData`.
- Composables renamed: `CallParticipantTile` is `CallTile`, and `ElementCallPictureInPictureView` is
  `ElementCallPictureInPictureContent`. Same parameters.



### What's Changed

✨ Features
* Feat: New grid-layout with hero spotlight when needed by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/29

🐛 Bugfixes
* Minimize a maximized call on back before the host's back handlers by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/31
* Fix: The composer draft of EXA is lost when a call is started by @BillCarsonFr in https://github.com/element-hq/element-call-android/pull/32


**Full Changelog**: https://github.com/element-hq/element-call-android/compare/v0.1.0-rc.5...v0.1.0-rc.6

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


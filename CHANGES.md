# Changes

The library's changelog, one section per release, newest first. The matrix-rust-rtc core's own history is
in `docs/CHANGELOG.md`. Entries are the pull request titles (`CONTRIBUTING.md`).

## Unreleased

Everything so far: the first extraction of the native call from the Element X Android spike.

- `element-call-api`: the contract. `ElementCallController` and `ElementCallSnapshot`, the host ports
  (`ElementCallMatrixTransport` and `ElementCallMatrixRoom`, `ElementCallLifecycleListener`,
  `ElementCallRoomContextProvider`, `CallAudioDeviceController`, `AudioFocus`), `ElementCallOptions` and
  `ElementCallNotificationConfig`, the `MatrixRtc*` types of the core wrapper.
- `element-call`: `ElementCallStack.Builder`, `DefaultElementCallController`, the foreground service and
  its notification, the audio routing, the picture-in-picture binder, and the matrix-rust-rtc core wrapper.
  Works with no UI attached.
- `element-call-ui`: `ElementCallScreen`, `ElementCallMinimizedBar`, `ElementCallFloatingTile`,
  `ElementCallPictureInPictureView` and `ElementCallOverlay`, over an `ElementCallStyle` port with
  Compound's dark tokens as defaults. Previews for every main state.
- `element-call-matrix`: `ElementCallSdkTransport` and `SdkElementCallRoomContext` over the Matrix Rust
  SDK, with the widget-driver stopgap for what the released bindings do not expose (`docs/FEEDBACK.md`).
- `element-call-test`: fakes for every port, the RTC service fakes, fixtures, and `ElementCallTestPattern`.
- The core is matrix-rust-rtc v0.2.0-rc.1, fetched from its release asset by `tools/rtc/fetch-rust-rtc`
  against the sha256 pinned in `gradle.properties`; its bindings live in `org.matrix.rtc`.
- Security hardening from the 2026-09-18 audit: no TLS bypass in the screenshot push, no persisted token while
  a pull request's build runs, network-derived values reach workflow shells through `env`, and
  `ElementCallOpenIdToken.toString()` redacts the bearer token. The core's provenance gap is `docs/FEEDBACK.md` item 28.
- Known limits of this first version: the Element Call compatibility is pinned to the state-event
  generation; the core AAR has no Maven coordinate of its own; the core's raised hands and reactions are
  read and dropped, and the room events they send are refused as unsupported, until the roster media model
  (plan 002) carries them.

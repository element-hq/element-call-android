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
- Known limits of this first version: the Element Call compatibility is pinned to the state-event
  generation; the core AAR is a local build with no Maven coordinate of its own and is not 16 KB aligned.

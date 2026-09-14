# Element Call Android

The native MatrixRTC call component for Android: a library a Matrix client embeds to place and receive calls
without a WebView. Extracted from the Element X Android spike; consumed by Element X Android behind a feature flag.

**Status: pre-release.** The repository skeleton is in place; the code is being imported module by module. Nothing is
published yet.

<!--- TOC -->

* [Modules](#modules)
* [Building](#building)
* [Host requirements](#host-requirements)
* [Documentation](#documentation)
* [Copyright & License](#copyright-license)

<!--- END -->

## Modules

Five published artifacts under `io.element.android`, one version:

| Artifact | Project | What it is |
| :--- | :--- | :--- |
| `element-call-api` | `call/api` | the contract: controller, ports, state types, options |
| `element-call` | `call/impl` | the stack, the controller, the foreground service, the Rust core wrapper; no Compose |
| `element-call-ui` | `call/ui` | the composables: screen, minimized bar, floating tile, picture-in-picture, style port |
| `element-call-matrix` | `call/matrix` | the turnkey Matrix transport over the Rust SDK |
| `element-call-test` | `call/test` | fakes and fixtures for hosts' tests |

See [AGENTS.md](AGENTS.md) for the boundaries between them.

## Building

The build needs the `matrix-rust-rtc` core as an Android AAR at `rtc/local/matrixrtc-release.aar` (not committed):

```
./tools/rtc/build-rust-rtc            # builds ../matrix-rust-rtc and drops the AAR in place
./gradlew assemble test runQualityChecks
```

[docs/local_stack.md](docs/local_stack.md) explains how to work on the core, this library and Element X at once.

## Host requirements

Numbered so an integration can be checked against them. Items marked *pending* are settled when the corresponding
code lands.

1. `minSdk` 24 or higher.
2. ABIs `armeabi-v7a`, `arm64-v8a`, `x86_64`. There is no `x86` build of the core; a host that ships `x86` must
   exclude it or accept that the call is unavailable there.
3. The host's Rust SDK version must be at least the one this library was compiled against (see
   `gradle/libs.versions.toml`, `matrix_sdk`).
4. Picture-in-picture: `android:supportsPictureInPicture="true"` and `smallestScreenSize` in `configChanges` on
   the host Activity; `ElementCallPictureInPicture.attach(activity, controller)` from its `onCreate`, and
   `ElementCallPictureInPicture.onUserLeaveHint(activity, controller)` from its `onUserLeaveHint()` override.
5. Screen sharing: the library's service declares `microphone|camera`; a host that turns screen sharing on adds
   `FOREGROUND_SERVICE_MEDIA_PROJECTION` and the `mediaProjection` type to `ElementCallForegroundService` in its
   own manifest, with `tools:node="merge"`. That type is Play-reviewed, so the library does not declare it.

## Documentation

- [AGENTS.md](AGENTS.md): boundaries, commands, conventions.
- [docs/local_stack.md](docs/local_stack.md): the local development stack.
- [CONTRIBUTING.md](CONTRIBUTING.md): how to submit a change.

## Copyright & License

Copyright (c) 2026 Element Creations Ltd.

This software is dual licensed by Element Creations Ltd (Element). It can be used either:

(1) for free under the terms of the GNU Affero General Public License (as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any later version); OR

(2) under the terms of a paid-for Element Commercial License agreement between you and Element (the
terms of which may vary depending on what you and Element have agreed to).

Unless required by applicable law or agreed to in writing, software distributed under the Licenses is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the Licenses for the specific language governing permissions and limitations under the
Licenses.

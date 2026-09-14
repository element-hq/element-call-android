# AGENTS.md — Element Call Android

> **Repo:** `element-hq/element-call-android` — the native MatrixRTC call component for Android, published
> as `io.element.android:element-call-*` and consumed by Element X Android behind a feature flag.
> Conventions are Element X Android's, copied on purpose: a change that would be rejected there is rejected here.

<!--- TOC -->

* [What this repository is](#what-this-repository-is)
* [Modules and boundaries](#modules-and-boundaries)
* [Build and check](#build-and-check)
* [Conventions](#conventions)
  * [Code style](#code-style)
  * [Logging](#logging)
  * [Strings](#strings)
  * [Previews and screenshots](#previews-and-screenshots)
  * [Tests](#tests)
  * [No DI framework, no Appyx, no Activity](#no-di-framework-no-appyx-no-activity)
* [What is temporary](#what-is-temporary)
* [The SDK edge](#the-sdk-edge)
* [Pull requests](#pull-requests)
* [Keep this file current](#keep-this-file-current)

<!--- END -->

## What this repository is

A standalone Android library. A host app gives it a Matrix transport (the turnkey one over the Rust SDK, or its
own), a few ports (lifecycle, room context, audio routing, style) and gets back a call controller that works with
no UI attached, plus composables for the call screen, the minimized bar, the floating tile and picture-in-picture.
The host decides where those composables sit. The plan this repository follows is
`element-call-feature-hq/plans/001.element_call_native_repo/android.md`; the spec is `specs/001.element_call_native_repo.md`.

## Modules and boundaries

| Gradle project | Artifact | Holds | May import |
| :--- | :--- | :--- | :--- |
| `call/api` | `element-call-api` | the contract: RTC types and ids, `ElementCallController`, the host ports (incl. `ElementCallMatrixTransport`), state types, options | coroutines only |
| `call/impl` | `element-call` | `ElementCallStack`, the controller, foreground service, receiver, audio routing, and the Rust core wrapper in `…impl.rtc` | `call/api`, the RTC AAR (only in `…impl.rtc`), JNA. **No Compose.** |
| `call/ui` | `element-call-ui` | the ONLY Compose module: screen, tiles, bar, floating tile, PiP content, `ElementCallStyle`, previews | `call/api`; libwebrtc only in `…ui.video` |
| `call/matrix` | `element-call-matrix` | the ONLY module importing `org.matrix.rustcomponents.sdk`: `ElementCallSdkTransport` and the `temporary/` widget-driver stopgap | `call/api`, the SDK |
| `call/test` | `element-call-test` | fakes for every port and for the RTC service, fixtures, test-pattern frames | `call/api` |
| `rtc/local` | not published | the locally built `matrixrtc-release.aar` (gitignored) | |
| `tests/konsist`, `tests/testutils` | not published | the rules below, test helpers | |

Everything in `call/impl` and `call/matrix` is `internal` unless it is on the allowlist in `KonsistBoundaryTest`.
The rules are enforced by `tests/konsist` (`KonsistBoundaryTest`, one assertion per rule) and by Gradle:
`verifyNoComposeDependencies` fails `check` if any `androidx.compose` artifact reaches the runtime classpath of
`call/api`, `call/impl` or `call/matrix` (the annotation-only `runtime-annotation` excepted: `androidx.activity`
drags it in and it carries no runtime).

Package root: `io.element.android.call`. Distinct from Element X's packages so both can share a classpath.

## Build and check

The build needs the `matrix-rust-rtc` AAR at `rtc/local/matrixrtc-release.aar`. Build it with
`./tools/rtc/build-rust-rtc` from a sibling checkout, or copy one in. See [docs/local_stack.md](docs/local_stack.md)
for the three layers (local core, library from source inside Element X, library as an AAR inside Element X).

- Build everything: `./gradlew assemble`
- Unit tests: `./gradlew test`
- Konsist, lint, detekt, ktlint, no-Compose check, docs TOC: `./gradlew runQualityChecks`
- Everything CI runs, before a PR: `./tools/quality/check.sh`
- Format: `./gradlew ktlintFormat`
- Update docs TOC: `./gradlew generateDocsToc`

There is no binary-compatibility check yet: neither the Kotlin binary-compatibility-validator plugin nor KGP's
built-in ABI validation works with AGP 9's built-in Kotlin (plan appendix). A public API change is reviewed by hand
until one does.

## Conventions

### Code style

- Style enforced by `.editorconfig` (Element X's). Hard wrap at 160 chars.
- Every source file carries the licence header (checked by `KonsistLicenseTest`):

```kotlin
/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
```

- `runCatching` is always `runCatchingExceptions` (it rethrows `CancellationException`).
- Classes implementing an interface are `Default…`, never `…Impl`. Fakes are `Fake<Interface>`.
- State classes have no default constructor values; events are sealed interfaces; sealed interfaces passed to
  composables are `@Immutable` or `@Stable`; use `toImmutableList()` not `toPersistentList()`.

### Logging

- Timber, never `android.util.Log`. The host owns the trees; there is no logging port.
- Never log secrets, keys or user content. Matrix IDs are safe to log.

### Strings

- English strings live in each module's `res/values/strings.xml`, keys prefixed `element_call_`. A host can overlay
  a key by declaring it with the same name. No Localazy project yet.

### Previews and screenshots

- Every main state of a public composable has a preview, annotated `@PreviewsDayNight` (this library's, in
  `io.element.android.call.ui.preview`; day and night) and
  wrapped in `ElementCallPreview { }`. Preview functions are `internal` and named `<View>Preview`.
- States come from a `<State>PreviewParam : PreviewParameterProvider`.
- Screenshots are recorded by CI (`Record-Screenshots` label), never locally.

### Tests

- JUnit4, Truth, Turbine, coroutines-test, Molecule. Import assertion methods; `isTrue()` not `isEqualTo(true)`.
- Test classes end with `Test`. Helpers live in `tests/testutils`; fakes for the library's own ports live in `call/test`.
- Behaviour is tested where it lives: the call itself in `call/impl` (`DefaultElementCallControllerTest`, the real
  controller over the fakes), what the screen makes of a snapshot in `call/ui` (`ElementCallScreenStateTest`, over
  `FakeElementCallController`). A test that drives a real controller through the screen tests two things at once.

### No DI framework, no Appyx, no Activity

- Plain constructors and one builder, `ElementCallStack.Builder`. Metro stays in the host.
- The library ships composables and a PiP binder. The sample app owns the only Activity.

## What is temporary

Anything that exists because the SDK or the core does not yet expose what the call needs lives in a folder named
`temporary/`, is annotated `@ElementCallTemporaryApi`, and has its removal recipe in `docs/FEEDBACK.md`. Today:

- `rtc/local`: the core has no Maven coordinate, so the AAR is a local file.
- `call/matrix/…/temporary/widget/`: the widget-driver stopgap for membership, delayed events and to-device keys.

## The SDK edge

`call/matrix` is compiled against one `org.matrix.rustcomponents:sdk-android` version (the catalog's `matrix_sdk`)
and publishes it as a plain `require`. Element X pins its own SDK `strictly`, so a newer host SDK wins and the
library runs on it; an older one fails at configuration time. When bumping the SDK here, run the tests and check
`SdkSurfaceBinaryCompatibilityTest` (arrives with `call/matrix`'s code).

## Pull requests

- Sentence-style titles; the title is the changelog entry. Exactly one `PR-` label (see `.github/release.yml`).
- 500 production lines max; tests can be larger. No history rewrites. Commits have a title and a description.
- Add the `Record-Screenshots` label when previews change.
- Plans are frozen once implementation starts; discoveries go in the plan's appendix, not its body.

## Keep this file current

When a boundary, a command or a convention changes, change it here in the same PR.

# The local stack

Three repositories, three layers. A developer working on the core, the library and Element X at once must be able
to point each layer at the one below it without touching a committed file. This is the operating manual: one
section per layer, with the command, the line to expect, and how to switch back. Read it before debugging a call
that misbehaves: the first question is always *which layer is live*.

```
matrix-rust-rtc  ──(1)──►  element-call-android  ──(2, 3)──►  element-x-android
```

<!--- TOC -->

* [Layer 1: a locally built core in the library](#layer-1-a-locally-built-core-in-the-library)
* [Layer 2: the library from source in Element X](#layer-2-the-library-from-source-in-element-x)
* [Layer 3: the library as an AAR in Element X](#layer-3-the-library-as-an-aar-in-element-x)
* [Two rules](#two-rules)

<!--- END -->

## Layer 1: a locally built core in the library

The `matrix-rust-rtc` core, `io.element.android:matrix-rtc-android`, is on GitHub Packages (a token even to read)
and not yet on Maven Central, so the library consumes it as a file: `settings.gradle.kts` declares an Ivy repository
over `rtc/local` that serves `rtc/local/matrixrtc-release.aar` (gitignored) under that coordinate, whatever version
the catalog names, and `call/impl` and `call/ui` depend on the catalog entry with an `aar` selector. There is no
fallback: without the file the build fails at resolution with a message pointing here.

**Fetch the pinned release** (what CI does in every workflow, and what a developer who is not changing the core
does). `gradle.properties` pins the release asset and its checksum, `MATRIX_RTC_AAR_URL` and
`MATRIX_RTC_AAR_SHA256`; the script downloads it, verifies the sha256 and drops it in place. It is idempotent, and a
mismatch leaves nothing behind:

```
./tools/rtc/fetch-rust-rtc                        # the pinned release
./tools/rtc/fetch-rust-rtc --url URL --sha256 SUM # another build, without editing gradle.properties
```

Bumping the core is a pull request that changes those two lines; CI validates it. The asset lives on the core's
repository (the fork's releases until the code moves to `element-hq/matrix-rust-rtc`, then that one), never here:
a `.aar` tracked by this repository is refused by `tools/quality/check.sh`.

**Or build the core and drop it in place**, to work on the core itself (from a sibling checkout of
`matrix-rust-rtc`; prerequisites are listed in the script and in the core's `mobile/PACKAGING.md`):

```
./tools/rtc/build-rust-rtc                 # ../matrix-rust-rtc, full media build
./tools/rtc/build-rust-rtc --rtc ~/src/rtc # another checkout
./tools/rtc/build-rust-rtc --slim          # signalling only, much faster, no media
```

The script also keeps a dated copy in `rtc/local/sdks/`, so a previous core can be restored by copying it back over
`matrixrtc-release.aar` without rebuilding.

**Check which core is live:** Gradle prints `Note: rtc/local/matrixrtc-release.aar is not the pinned matrix-rust-rtc
release` at configuration whenever the file's sha256 differs from `MATRIX_RTC_AAR_SHA256`; otherwise
`shasum -a 256 rtc/local/matrixrtc-release.aar` and compare with the dated copies. The unit test
`MatrixRtcAarClasspathTest` in `call/impl` proves the AAR resolves and that its nested `libwebrtc.jar` is on the
classpath.

**Switch back:** `./tools/rtc/fetch-rust-rtc` puts the pinned release back over a local build. When the core
reaches Maven Central, `rtc/local` and the Ivy repository go away and the catalog entry resolves like any other.

## Layer 2: the library from source in Element X

Element X includes this repository as a composite build and substitutes the five published artifacts with the
projects here, so every change recompiles inside Element X and a breakpoint set in the library stops in
Element X's debugger. This is the day-to-day mode while the ports settle.

Clone `element-call-android` **outside** the Element X tree (Element X's Konsist scans everything under its root)
and run Element X with the absolute path:

```
cd element-x-android
./gradlew :app:assembleGplayDebug -PelementCallAndroidDir=/abs/path/element-call-android
```

or put `elementCallAndroidDir=/abs/path/element-call-android` in `~/.gradle/gradle.properties`, which never reaches
git. Gradle prints `Note: element-call-android from <path> (composite build)`.

Layer 1 composes with layer 2 automatically: the included build carries its own `rtc/local`, so the core Element X
runs is whatever file is there.

**Switch back:** drop the property.

**Until Element X's settings carry the property** (that lands with the second Element X pull request of the plan),
the same effect is obtained by adding this to Element X's `settings.gradle.kts` locally, without committing it:

```kotlin
includeBuild("/abs/path/element-call-android") {
    dependencySubstitution {
        substitute(module("io.element.android:element-call-api")).using(project(":call:api"))
        substitute(module("io.element.android:element-call")).using(project(":call:impl"))
        substitute(module("io.element.android:element-call-ui")).using(project(":call:ui"))
        substitute(module("io.element.android:element-call-matrix")).using(project(":call:matrix"))
        substitute(module("io.element.android:element-call-test")).using(project(":call:test"))
    }
}
```

The project names (`api`, `impl`) differ from the artifact ids, which is why every substitution is explicit.

## Layer 3: the library as an AAR in Element X

Source substitution hides what only an artifact shows: visibility, R8 and the consumer rules, manifest merging, the
POM's SDK `require`. For that, publish to the local Maven repository and point Element X at it:

```
cd element-call-android
./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local

cd element-x-android
./gradlew :app:assembleGplayDebug -PelementCallLocalVersion=0.0.0-local
```

Element X's settings turn the property into a `mavenLocal()` repository filtered to
`io.element.android:element-call-*` and a version override; its repositories are otherwise credential-free and
`mavenLocal()`-free on purpose, so the repository only exists under the property.

**Switch back:** drop the property.

The library side exists: `publishToMavenLocal` publishes the five artifacts and the BOM through the
`io.element.call.publish` plugin, and, from `rtc/local`, the core file as
`io.element.android:matrix-rtc-android:<MATRIX_RTC_VERSION>` (the coordinate the published POMs name; `RELEASING.md`
says why it goes no further than `~/.m2`). `tests/consumer` is a minified app built this way on every change, and,
with `-PelementCallDistDir`, against a directory of release assets the way a host resolves a release (README,
"Consuming a release"). Element X's `-PelementCallLocalVersion` property arrives with its integration pull requests
(plan §10, PR 2).

## Two rules

Both were learned the hard way in the spike.

1. **Never commit a layer switch.** Neither property, nor a local AAR, nor a local `includeBuild`. The
   `.gitignore` covers `rtc/local/*.aar` and `checkouts/`; `tools/quality/check.sh` refuses a tracked `.aar`
   and a tracked `elementCallAndroidDir` or `elementCallLocalVersion`.
2. **Confirm which layer is live before debugging.** A stale AAR that a file check silently picked up cost a day
   once. Compare checksums (layer 1), look for the composite note in the Gradle output (layer 2), or check the
   resolved version with `./gradlew :app:dependencies --configuration gplayDebugRuntimeClasspath | grep element-call`
   (layer 3), before reading logcat.

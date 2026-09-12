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

The `matrix-rust-rtc` core has no Maven coordinate yet, so the library consumes it as a file: `rtc/local` is a bare
Gradle project that publishes `rtc/local/matrixrtc-release.aar` (gitignored) on its `default` configuration, and
`call/impl` and `call/ui` depend on that project. There is no fallback: without the file the build fails at
configuration with a message pointing here.

**Build the core and drop it in place** (from a sibling checkout of `matrix-rust-rtc`; prerequisites are listed in
the script and in the core's `mobile/PACKAGING.md`):

```
./tools/rtc/build-rust-rtc                 # ../matrix-rust-rtc, full media build
./tools/rtc/build-rust-rtc --rtc ~/src/rtc # another checkout
./tools/rtc/build-rust-rtc --slim          # signalling only, much faster, no media
```

The script also keeps a dated copy in `rtc/local/sdks/`, so a previous core can be restored by copying it back over
`matrixrtc-release.aar` without rebuilding.

**Check which core is live:** `shasum -a 256 rtc/local/matrixrtc-release.aar` and compare with the dated copies.
The unit test `MatrixRtcAarClasspathTest` in `call/impl` proves the AAR resolves and that its nested
`libwebrtc.jar` is on the classpath.

**Switch back:** there is nothing to switch back to yet. When the core publishes to Maven, `rtc/local` goes away
and this section becomes a substitution over the catalog entry.

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

*The publish plugin and Element X's property arrive with the distribution step of the plan (L4). Until then this
layer is documented, not available.*

## Two rules

Both were learned the hard way in the spike.

1. **Never commit a layer switch.** Neither property, nor a local AAR, nor a local `includeBuild`. The
   `.gitignore` covers `rtc/local/*.aar` and `checkouts/`; `tools/quality/check.sh` refuses a tracked `.aar`
   and a tracked `elementCallAndroidDir` or `elementCallLocalVersion`.
2. **Confirm which layer is live before debugging.** A stale AAR that a file check silently picked up cost a day
   once. Compare checksums (layer 1), look for the composite note in the Gradle output (layer 2), or check the
   resolved version with `./gradlew :app:dependencies --configuration gplayDebugRuntimeClasspath | grep element-call`
   (layer 3), before reading logcat.

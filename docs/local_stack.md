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

The `matrix-rust-rtc` core, `io.element.android:matrix-rtc-android`, comes from Maven Central at the version
`matrix_rtc` in `gradle/libs.versions.toml`. Bumping the core is a pull request that changes that line.

**Build the core and publish it locally**, to work on the core itself (from a sibling checkout of
`matrix-rust-rtc`; prerequisites are listed in the script and in the core's `mobile/PACKAGING.md`). The script
builds the AAR and publishes it to `~/.m2` as `io.element.android:matrix-rtc-android:0.0.0-local`, POM and module
metadata included:

```
./tools/rtc/build-rust-rtc                 # ../matrix-rust-rtc, full media build
./tools/rtc/build-rust-rtc --rtc ~/src/rtc # another checkout
./tools/rtc/build-rust-rtc --slim          # signalling only, much faster, no media
```

Then build here with the property, on the command line or in `~/.gradle/gradle.properties`:

```
./gradlew assemble -PmatrixRtcLocalVersion=0.0.0-local
```

`settings.gradle.kts` turns it into a `mavenLocal()` repository filtered to the core and a version override for the
catalog's `matrix_rtc`; without it, `mavenLocal()` is not declared at all. Republishing `0.0.0-local` is picked up by
the next build: Gradle does not cache what it resolves from `mavenLocal()`.

**Check which core is live:** Gradle prints `Note: resolving io.element.android:matrix-rtc-android 0.0.0-local from
mavenLocal` at configuration. The unit test `MatrixRtcAarClasspathTest` in `call/impl` proves the AAR resolves and
that its nested `libwebrtc.jar` is on the classpath.

**Switch back:** drop the property.

## Layer 2: the library from source in Element X

Element X includes this repository as a composite build and substitutes the four published artifacts with the
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

Layer 1 does not compose with layer 2 on its own: Element X resolves the core from its own repositories, so it gets
the version this repository's catalog names, from Maven Central. To run a locally built core, add this ahead of
`mavenCentral()` in Element X's `settings.gradle.kts`, without committing it, and pass
`-PmatrixRtcLocalVersion=0.0.0-local` so that the included build asks for that version:

```kotlin
mavenLocal {
    content { includeModule("io.element.android", "matrix-rtc-android") }
}
```

**Switch back:** drop the property.

**Until Element X's settings carry the property** (that lands with the second Element X pull request of the plan),
the same effect is obtained by adding this to Element X's `settings.gradle.kts` locally, without committing it:

```kotlin
includeBuild("/abs/path/element-call-android") {
    dependencySubstitution {
        substitute(module("io.element.android:element-call-api")).using(project(":call:api"))
        substitute(module("io.element.android:element-call")).using(project(":call:impl"))
        substitute(module("io.element.android:element-call-ui")).using(project(":call:ui"))
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

The library side exists: `publishToMavenLocal` publishes the four artifacts and the BOM through the
`io.element.call.publish` plugin; their POMs name the core at the catalog's version, which Element X resolves from
Maven Central. `tests/consumer` is a minified app built this way on every change, and, with `-PelementCallDistDir`,
against a directory of release assets the way a host resolves a GitHub release (README, "Consuming a release"). Element X's `-PelementCallLocalVersion` property arrives with its integration pull requests
(plan §10, PR 2).

## Two rules

Both were learned the hard way in the spike.

1. **Never commit a layer switch.** No property, no local AAR, no local `includeBuild`. The `.gitignore` covers
   `checkouts/`; `tools/quality/check.sh` refuses a tracked `.aar`, a tracked `elementCallAndroidDir` or
   `elementCallLocalVersion`, and a committed `matrixRtcLocalVersion`.
2. **Confirm which layer is live before debugging.** A stale AAR that a file check silently picked up cost a day
   once. Look for the `mavenLocal` note (layer 1), look for the composite note in the Gradle output (layer 2), or check the
   resolved version with `./gradlew :app:dependencies --configuration gplayDebugRuntimeClasspath | grep element-call`
   (layer 3), before reading logcat.

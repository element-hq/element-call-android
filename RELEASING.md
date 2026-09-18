# Releasing

<!--- TOC -->

* [Versioning](#versioning)
* [What a release is](#what-a-release-is)
* [Cutting a release](#cutting-a-release)
* [Publishing to Maven Central](#publishing-to-maven-central)
* [What must never be published](#what-must-never-be-published)

<!--- END -->

## Versioning

Semantic versioning at `0.x`: the host pins each artifact exactly, so at `0.x` a renamed port method or
a changed test tag is a breaking change and gets a **minor** bump. So does a change of the Matrix Rust SDK
version `call/matrix` is compiled against, because the published POM declares it and the host's own pin
must be at least that. Everything else is a **patch**.

`VERSION_NAME` in `gradle.properties` is the single source of the version. It ends in `-SNAPSHOT` between
releases. Release candidates are ordinary versions, `0.1.0-rc.1`, and are as immutable as any other once
published. Not CalVer: Element X's dates suit an app; a library pinned exactly by its consumer needs
breaking-change signalling. Not tag-derived: a git call at configuration time destabilises the
configuration cache.

## What a release is

One version for the five artifacts and the BOM, under `io.element.android`:

| Artifact | Project |
| :--- | :--- |
| `element-call-api` | `call/api` |
| `element-call` | `call/impl` |
| `element-call-ui` | `call/ui` |
| `element-call-matrix` | `call/matrix` |
| `element-call-test` | `call/test` |
| `element-call-bom` | `bom` |

Each is published by the `io.element.call.publish` convention plugin (`com.vanniktech.maven.publish`) as
its release variant with a sources jar, an empty javadoc jar, Gradle module metadata, and a POM naming
both licences. The published `element-call` POM depends on the core at `org.matrix.rtc:matrixrtc-android`,
the coordinate the plan reserves for the core's own publication; see the last section for what that
means today.

## Cutting a release

1. Set `VERSION_NAME` in `gradle.properties` to the version, without `-SNAPSHOT`.
2. Move the `## Unreleased` entries of `CHANGES.md` under a `## <version>` heading with the date.
3. Rehearse: `./scripts/release.sh v<version>`. It checks the tag against `VERSION_NAME`, that the
   working tree is clean and `CHANGES.md` has the section, then runs `build runQualityChecks`,
   `publishToMavenLocal` and the minified consumer in `tests/consumer` against what was published. It
   touches nothing beyond `~/.m2`.
4. Commit, tag `v<version>`, push the tag. `release.yml` runs the same script, publishes if the gate
   below is open, builds the sample APK and drafts the GitHub release with the `CHANGES.md` section as
   its notes.
5. Set `VERSION_NAME` to the next `-SNAPSHOT` and open a fresh `## Unreleased`.

A host developing against an unreleased version uses the local Maven repository instead
(`docs/local_stack.md`, layer 3): `./gradlew publishToMavenLocal -PVERSION_NAME=0.0.0-local`.

## Publishing to Maven Central

`release.yml` publishes only when the repository variable `MAVEN_CENTRAL_PUBLISH` is `true`, because a
Central release is immutable and public and three things have to be in place first:

- **A Central Portal account** for the `io.element.android` namespace (Element owns it; the `element-call-embedded`
  artifacts are already under it), as the secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
- **A signing key**, as `MAVEN_SIGNING_KEY` (the armoured private key) and `MAVEN_SIGNING_KEY_PASSWORD`.
  Signing is off by default so a local publish needs no key; `-PRELEASE_SIGNING_ENABLED=true` turns it on.
- **A core artifact the POMs can name.** The `element-call` POM depends on `org.matrix.rtc:matrixrtc-android`.
  Today that coordinate exists only in the local Maven repository, published from `rtc/local` (below)
  out of the release asset `gradle.properties` pins, so a Central release of `element-call` would be
  unresolvable for every host. Until `matrix-rust-rtc`
  publishes to Maven (the core feedback in the plan's §14), or an interim artifact is decided, the gate
  stays closed and the exit condition of the plan's L4 - "resolves from Maven Central in a clean
  project" - is not met.

Two more things a first release should say out loud in its notes: the core's `libmatrix_rtc_ffi.so` is
not 16 KB aligned, which Android 16 tells users about on install, and the Element Call compatibility is
pinned to the state-event generation while the library builds against the released SDK (`docs/FEEDBACK.md`).

## What must never be published

`rtc/local` publishes the locally built core AAR to the **local** Maven repository as
`org.matrix.rtc:matrixrtc-android:<MATRIX_RTC_VERSION>`, so that the library's POMs name a real
dependency and `tests/consumer` resolves it. That project has no remote repository configured, on purpose:
the group belongs to the core, and a build of it from this repository must never appear on Central under
it. When the core publishes its own artifact, the coordinate stays, this publication goes, and
`call/impl` and `call/ui` switch to a catalog entry.

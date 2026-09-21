# rtc/local

The `matrix-rust-rtc` core as an Android AAR. The file is not committed.

```
./tools/rtc/fetch-rust-rtc                  # fetches the release gradle.properties pins and checks its sha256
./tools/rtc/build-rust-rtc [--rtc PATH]     # or builds ../matrix-rust-rtc and drops matrixrtc-release.aar here
```

Every build of `call/impl` and `call/ui` needs the file to be present: an Ivy repository in `settings.gradle.kts`
serves it as `io.element.android:matrix-rtc-android`, and this project publishes it to `~/.m2` under the same
coordinate for `tests/consumer` and a host. Gradle warns when the file is not the
pinned release, so a local build never goes unnoticed. `build-rust-rtc` keeps dated copies in `sdks/` so a
previous build can be restored without rebuilding the core. See [docs/local_stack.md](../../docs/local_stack.md).

# rtc/local

The `matrix-rust-rtc` core as an Android AAR, built locally. The file is not committed.

```
./tools/rtc/build-rust-rtc [--rtc PATH]     # builds ../matrix-rust-rtc and drops matrixrtc-release.aar here
```

Every build of `call/impl` and `call/ui` needs the file to be present. Dated copies land in `sdks/` so a
previous build can be restored without rebuilding the core. See [docs/local_stack.md](../../docs/local_stack.md).

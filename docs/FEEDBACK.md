# matrix-rust-rtc — integration feedback

Notes from integrating the prebuilt `matrixrtc-release.aar` — first as a spike inside Element X Android, now as this
library — to validate the architecture of a frame-in / frame-out RTC library on Android. The integration lives in
`call/impl` (`…impl.rtc` is the only package importing `uniffi.matrix_rtc_ffi`; capture and playback are in
`…impl.rtc.media`) and `call/ui`.

Feedback is a first-class output of that work, not a by-product — this file is the deliverable.

The companion document is [`INTEGRATION.md`](INTEGRATION.md), which is the same work written the other way round:
what a host has to *do* to integrate the library, in the order it has to do it. Where a step there exists only to
work around something open here, it says so and points at the item.

**Status: item 16 — the abort that killed every video call — is fixed and verified on device.** The library pins
JNA's callback threads, and the scenario that used to abort within minutes now runs clean; the numbers are in the
entry. Also landed with it: build IDs on the Android `.so`, so a future tombstone can be matched to the library that
produced it rather than symbolicated hopefully.

**Item 14 — the per-frame copies that were 82% of the app's CPU — is done, and the answers about `planePtr`
lifetime are what unblocked it.** The receive path now wraps the core's own memory and copies nothing; garbage
collection fell from 20% of the process to 6% while decoding three times the pixels. Adopting it cost us three
ownership bugs that all reached a device and none of which raised an error, so the entry now carries what a host
actually needs beyond the current contract — the short version is that "release when you are done" reads as
single-ownership and a real UI has several holders at once. A worked reference-counting example in `frames.rs`
would be worth more than more prose.

**The one item that decides whether the feature works at all is 17**: the core never asks the host to send an
`m.rtc.notification`, so a call placed from here rings nobody.

**Item 18 — a screen share that could not be stopped, only muted — is fixed.** `MediaSession::unpublish(kind)`
landed and is integrated; a stopped share is now retracted, so receivers drop the stream instead of drawing an empty
tile for the rest of the call. The entry records the three things the answer settled that the method signature alone
would not have.

**Items 24 and 25 are new, and 24 was the most serious thing in this file after 17; a fix for 24 shipped in the
AAR of 2026-09-02 and awaits verification on device.** Testing item 18 turned up a
call in which the far end could not decrypt our video at all, for its whole duration: our outbound key was
distributed to a membership that had already left, the member who actually arrived four seconds later was treated as
already holding it, and the rotation that would have healed that was deferred and never fired. Nothing on the host
side can see it happen — every local signal, `FrameEncryption` included, says the call is healthy — and leaving and
rejoining was the only cure.

**The first round of feedback has been acted on.** The v0.2.0 sweep addressed every API and ergonomics item
we raised and fixed the on-device bugs we reported, so this file has been cut back to what is still open. What was
resolved is recorded in the library's own changelog rather than duplicated here; the host-side workarounds those
items forced — a `runBlocking` bridge over the command sender, a 1 Hz `memberCount` poll, a shadow copy of our own
mute state, reconciling `localIdentity()` against the media roster, and boxing every PCM sample — have all been
deleted from the wrapper.

The v0.2.0 AAR also brought Element Call interop, which the app exposes as a three-way developer setting
(*Developer settings → Native calls → Element Call compatibility*) read when a call is placed. All three modes are
now complete on the host side: matrix-rust-sdk grew the room-state read that item 1 under `matrix-rust-sdk` asked
for, so `STATE_EVENTS` reads the other side's memberships as well as publishing its own, and there is nothing left
that the host cannot hold up its end of.

**Video works in both directions.** The camera captures and publishes, remote members' cameras are decoded and
drawn, and a tile per publishing member appears alongside the existing per-member diagnostics. Nothing in the
library was missing for any of it — `publish(kind = CAMERA)`, `captureVideo`, `videoStream` and `VideoFrameRef` were
all already there and `FfiStreamKind.CAMERA` already round-tripped through the event mapper. So this round of video
feedback is almost entirely about things the FFI does not *say* rather than things it cannot do: items 2, 13, 14 and
15. There are two exceptions, both real faults rather than documentation gaps: item 13, a trap that cost a debugging
session, and item 16, which was the most serious entry in this file until it was fixed — JNA detached libwebrtc's
decode thread on every frame, and the process aborted on a JNI invariant after a few minutes of any call with cameras
on. That one was found by measuring rather than reading, after four crashes we had each wrongly attributed to our own
UI; the diagnosis we sent with it was itself wrong, and item 16 is now mostly a record of the corrections.

**Screen sharing and `setConstraints` have since been exercised too**, and between them account for items 18 to 20.
Screen share works end to end from `ScreenCapturerAndroid` over `MediaProjection` and Element Call web receives it,
and stopping it now retracts the publication (item 18, resolved). What is left there is item 19: `simulcast` is the
wrong axis for a screen and we cannot opt out of it. `setConstraints` turned out to be the largest single
performance lever on the receive side and the reason the phone was hot — reporting each tile's real size took
remote video from 5-9 fps to 20-30 and the phone from 38 C to 34 C (item 20). That one was our omission rather than
a library fault, and is recorded because the symptom was indistinguishable from one.

## How this was tested

Two arm64 phones, an encrypted DM, LiveKit discovered via well-known (the `/_matrix/client/v1/rtc/transports`
endpoint answered `401` throughout, so the app falls back). Diagnosis was from `adb logcat` on both sides, filtering
the core's own `matrix-rtc` tag and our `org.matrix.rust.sdk` tag.

Two caveats that cost real time and are worth knowing before reading any evidence from these runs:

- **The two phones' clocks differ by roughly 2 s.** A key is logged as *received* before the other device logs
  *sending* it. Nothing here relies on comparing timestamps across the two logs — every conclusion rests on
  ordering within a single device's log.
- **Once a side goes quiet its `rx` packet counter slows to ~4–6/s** while `0% invented` holds. That is DTX, not a
  fault, and it looks alarming next to a `level 0.00` audio line.

## Still open

An item whose title is ~~struck through~~ is resolved and is **not** open. They are kept in place, with their
numbering, because the diagnosis is the useful part and because renumbering would break the cross-references
between entries.

### Packaging

1. **No `x86` ABI.** The AAR ships `arm64-v8a`, `armeabi-v7a` and `x86_64`. The app's ABI splits list `x86`
   (`app/build.gradle.kts`), so a 32-bit emulator build installs without the RTC native library and fails at first
   use rather than at build time.
2. **`libs/libwebrtc.jar` is now load-bearing for video, and we still do not know whether it is meant to be.**
   Previously a curiosity; asking again because the answer now decides whether video works at all. Both halves of
   our camera support are that jar: `Camera2Enumerator` / `Camera2Capturer` / `SurfaceTextureHelper` to capture,
   `JavaI420Buffer` / `SurfaceViewRenderer` to display. The `Java_livekit_org_webrtc*` symbols are exported from
   `libmatrix_rtc_ffi.so` and `MatrixRtc.initialize()` is the loader that runs `JNI_OnLoad`, so the classes work
   today — but nothing says they are part of the library's contract, and a build that dropped the nested jar would
   take video with it while leaving audio untouched. **Please either commit to keeping it exported, or say it is
   internal so hosts write their own capture and rendering.** What makes it worth keeping is
   `VideoFrame.Buffer.toI420()`: it converts whatever the device produced into exactly the three-plane layout
   `captureVideo` takes, in native code every host would otherwise reimplement — CameraX hands out
   `YUV_420_888` with pixel strides that need de-interleaving by hand. We guard the assumption with a
   `Class.forName` test (`MatrixRtcAarClasspathTest`) so a change fails our build rather than a device.
26. **The AAR's `proguard.txt` is not ProGuard syntax, and it breaks every minified host.** The file is a
    47-byte C-style comment, `/* Placeholder for consumer ProGuard rules */`. ProGuard and R8 only know `#`
    comments, so R8 stops the host's release build at "Compilation failed to complete, position: offset: 0,
    line: 1, column: 1, origin: … proguard.txt" the moment the AAR is on its classpath - Element X's release
    build included, the day it consumes this library as an artifact. Found by `tests/consumer`, the minified
    consumer build; until the core ships a valid file, `rtc/local` repackages the AAR without it and
    `call/impl` carries the rules the core actually needs (`consumer-rules.pro`: JNA, `uniffi.matrix_rtc_ffi`,
    `org.matrix.rtc`, `livekit.org.webrtc`, `livekit.org.jni_zero`). Those rules, or real ones, belong in the AAR.
27. **`libmatrix_rtc_ffi.so` is not 16 KB page aligned.** Android 16 shows the user an "Android App
    Compatibility" dialog on install naming the library ("LOAD segment not aligned") and runs the app in
    compatibility mode; Play requires 16 KB support for new apps and updates targeting Android 15+. Build the
    `.so` with the NDK's 16 KB flags (`-Wl,-z,max-page-size=16384`) and check it with `check_elf_alignment.sh`
    in the release pipeline. Seen on the Android 16 emulator; also flagged by lint's `Aligned16KB` on every
    build of `call/ui`.

### Deployment

3. **Android honours only the bundled root certificate store for the SFU connection.** `rustls-native-certs` finds
   nothing on Android and the connection now carries the bundled webpki roots, which fixes the common case — but a
   deployment fronted by an enterprise or internal CA still fails the TLS handshake with no way for a host to
   install its own trust. Needs `rustls-platform-verifier` support in `livekit-api`.

### Semantics we still cannot see from the FFI

4. **~~The keep-alive driver never runs: `run_heartbeat`'s future is built and dropped, never polled.~~ —
   fixed upstream and confirmed fixed on device.** Kept for the diagnosis, which took three runs to pin down;
   the confirmation is at the end of the entry.
   `start_heartbeat` (`crates/matrix-rtc-ffi/src/lib.rs:774`) did:

   ```rust
   async fn run_heartbeat(...)                                    // :292

   .spawn(move || run_heartbeat(manager, room_id, slot_id, stop_rx));   // :779
   ```

   `run_heartbeat` is `async`, so calling it only *builds* a future. The closure returns that future as
   the OS thread's result and nothing polls it — there is no runtime in that path. The thread exits at
   once, the spawn is `Ok`, and the log cheerfully says `keep-alive driver started`. `join()` is
   documented to start this driver, so by default nothing beats at all.

   The suggested fix is to poll it on the runtime that already exists in `runtime.rs`
   (`runtime().block_on(run_heartbeat(...))`), or to drop the thread entirely and
   `runtime().spawn(run_heartbeat(...))`. Worth a test that asserts a beat reaches the command sender,
   since the current failure mode is indistinguishable from success in the log.

   This explains the earlier observation, which we had recorded as unexplained: across three live runs
   against Element Call not one `restartDelayedEvent` reached the command sender — windows of 20 s, 40 s
   and 20 s against a 20 s `keepAliveTimeoutMs` — and the `cancelDelayedEvent` issued at hangup answered
   `404 Delayed event not found`, so the dead man's switch had already fired mid-call.

   What that costs depends entirely on the dialect, which is why it is easy to miss. With a sticky membership
   the delayed leave is itself non-sticky and clears nothing from the sticky map (item 5 below), so a fired
   switch is invisible until the sticky TTL — an hour — expires, and the call looks healthy. In
   `ElementCallCompat.STATE_EVENTS` the leave is a delayed *state* event that genuinely empties the membership,
   so the identical fault drops us out of a live call after 20 s and the far end watches us vanish. The two
   observations only lined up once we compared them.

   Caught in `STATE_EVENTS` once its inbound half started working: our own state membership came back
   through the room-state subscription as a departure exactly `keepAliveTimeoutMs` after the join, with
   no `restartDelayedEvent` anywhere in between.

   **Reported, fixed upstream, and confirmed fixed.** The wrapper carried a workaround — a 10 s
   coroutine ticker calling `heartbeat()`, the entry point the FFI documents "for hosts that would rather
   drive the keep-alive from their own scheduler" and the only one that actually beat. That ticker has been
   deleted and the host now relies on the driver `join()` starts, as documented. Verified in
   `STATE_EVENTS`, the mode where a fired switch is visible: `restartDelayedEvent` reaches the command
   sender every 10.2 s from the join, and the membership survives well past the 20 s `keepAliveTimeoutMs`
   that evicted us on the previous build at +19.9 s.
5. **Crash cleanup lasts the sticky TTL, not `keepAliveTimeoutMs`.** The delayed leave is a plain, non-sticky
   delayed event, so a client that dies without leaving lingers as a ghost for up to `stickyDurationMs`. Known and
   documented in the library; recorded here because it is what a user sees, and an hour is a long time to show
   someone as being in a call they crashed out of. Blocked on ruma carrying `delay` and `sticky_duration_ms` on the
   same send.
6. **Frame encryption is reported per participant, not per stream.** A failure does not say which of their tracks
   it came from. This has stopped being hypothetical: a member can now publish a microphone and a camera at once,
   so `MISSING_KEY` for that member no longer says whether their audio, their video or both are affected — and the
   two are separately diagnosable faults. `receiveStats(memberId, kind)` is already keyed by both; the encryption
   report is the one per-member signal that is not.
7. **`subscribeMembershipSnapshots` delivers one snapshot at subscribe time and then goes silent
   through membership changes the core is visibly acting on.** Seen against Element Call in
   `STICKY_EVENTS`, with the subscription established *before* anything was fed:

   ```
   23:53:27.641  0 member(s) in !room/m.call#ROOM: []          ← the only snapshot, ever
   23:53:27.681  feeding 1 raw membership(s) [@bob  … slot=m.call#ROOM encrypted=true]
   23:53:35.864  feeding 2 raw membership(s) [@bob …, @alice … slot=m.call#ROOM encrypted=true]
   23:53:35.885  sendToDeviceMessage(io.element.call.encryption_keys to [@alice/V5cP8FErcB])
   ```

   The last line is what makes this unambiguous: the core can only choose Alice as a media-key
   recipient from an *accepted* membership, and media then flowed both ways with frame encryption
   `Ok` on both identities. So the membership is there and the subscriber is not told. The host is
   left rendering "0 in call" over a working two-way call, with no way to notice.

   Two candidates, needing opposite fixes, which is why we now log `memberCount` right after each
   feed: either the projection is correct and subscribers are not woken, or `setCurrentMembership`
   updates the session the key path reads without updating the one the projection reads. The first
   would be the more serious, since `subscribeMembershipSnapshots` is also documented as how a host
   learns a call has *started*.

   Note it is specifically the compat entry point in play here — `setCurrentStickyState` drove this
   subscription correctly in earlier Element X to Element X testing.

   `STATE_EVENTS` now drives that same entry point, so it is a second way to reproduce this, and a
   cleaner one: that dialect has no sticky traffic at all, so `setCurrentMembership` is the *only*
   thing touching the session. Note also that the 8 s gap above has a host-side explanation we have
   since fixed — the room member list had not been fed when the first membership arrived, so the core
   would have excluded Bob as `SenderNotInRoom`, and the key went out only once the members landed and
   the second feed re-offered him. The membership feeds now wait for the member list before
   subscribing, which should remove that confound from any re-run.

   Re-run in `STATE_EVENTS` on the build that fixed item 4, and it reduces to the smallest case there
   is — a call whose only live membership is our own:

   ```
   09:03:10.916  joined !room/m.call#ROOM … compat STATE_EVENTS
   09:03:10.922  0 member(s) in !room/m.call#ROOM: []            ← the only snapshot, ever
   09:03:11.014  feeding 22 state membership(s) (ours=1, departures=21)
   09:03:11.058  core reports 1 member(s) … after feeding 22 state membership(s)
                 … 40 s and four keep-alive beats later, still no second snapshot …
   ```

   Twenty-one of the twenty-two state events are `{}` departures, so exactly one membership is live and
   it is ours. No peer, no second dialect, no sticky traffic, one `setCurrentMembership` the core
   demonstrably accepted — and `memberCount` and `subscribeMembershipSnapshots`, given the same room
   and slot, answer 1 and 0. Whatever wakes the projection, `setCurrentMembership` does not reach it.
8. **The membership projection never contains our own join until it echoes back.** `join()` mints our
   `member.id` and the session is unambiguously ours, but `subscribeMembershipSnapshots` reports us
   only once our own membership returns through `setCurrentStickyState` / `setCurrentMembership`.

   The sharpest instance of this is gone: `ElementCallCompat.STATE_EVENTS` used to make the echo
   *impossible*, because the membership was room state the host could not read back, so a host that
   had joined, connected media and was publishing audio was told the call had zero members including
   itself. The host now reads that state and feeds it, and our own event comes back through the same
   subscription.

   What remains is a window rather than a permanent hole: in every dialect the count is short by one —
   ourselves — for as long as our own membership takes to round-trip through sync, which is precisely
   when a user is watching the call connect. Seeding the projection from the join would close it, and
   would cost nothing where the echo arrives anyway.
9. **`FrameEncryptionState` is still event-only.** The `diagnostic` addition answers most of what we asked — a
   `MissingKey` now says whether any key was installed — but the host's picture is still a replay of what it
   happened to be subscribed for. A getter for the current state per member would let a host that attached late,
   or that dropped an event, render the truth rather than a gap. The same argument applies to `KeyDiscarded`.
10. **No core-side audio level per member.** `ActiveSpeakers` now carries a level, which covers "who is talking and
   how loudly", but only for members the SFU currently counts as speaking. A level per member — the transport
   already reads the RTP audio-level header, which survives E2EE — would let a host distinguish "quiet" from "not
   decoding" without metering the PCM itself. We still meter, and for remote members that measures the wrong end:
   a concealed stream and a genuinely silent one both read as flat.

### Not in the core

11. **`on_room_slots_received` has no data source** in matrix-rust-sdk, so the host cannot obtain MSC4143 slot state
   at all and the core falls back to its default slot handling. Our two ends of a call agree on a hardcoded slot id.

   **A corollary that cost us a live test: `join` accepts a slot id that `openSlot` would refuse.** Because
   `openSlot` has no data source here we never call it, so nothing ever validated ours — we joined with a bare
   `m.call` instead of `m.call#…` and everything on this side looked perfect: membership published, media
   connected, audio flowing. Element Call refused the membership on sight (`slot_id must start with m.call#`) and
   the only place that was visible was the *other* client's console. Validating in `join` too — the one call every
   host must make — would turn a silent mutual invisibility into an immediate error.
12. **Transport discovery is not in the core**, so every host reimplements an authenticated
    `GET /_matrix/client/v1/rtc/transports` (`RtcTransportDiscovery.kt`). A helper — even just the response
    parsing — would remove duplicated work. In practice the endpoint answered `401` for us and we fell back to
    well-known.

### Video

Everything below came out of building two-way camera video on top of the existing audio call. The numbering
continues from above rather than restarting, because entries elsewhere in this file refer to these by number.

13. **`simulcast = false` silently sends no video at all, and every signal on the publishing side says it is
    working.** This cost us a debugging session and we would not have found it without the core's own log, so it is
    the highest-value thing in this round.

    What happens: LiveKit's dynacast pauses any encoding the SFU reports no subscriber for. A peer rendering us in
    a small tile subscribes to the **low** layer. With `simulcast = false` there is a single full-resolution
    encoding, so the only layer we produce is the only one never asked for — it gets paused, and nothing leaves the
    device.

    What it looks like from here, and why it is so hard to see: capture is healthy, the frame counter climbs,
    `captureVideo` returns without error, the self view is a perfect picture, MediaCodec even starts. The far end
    shows grey and never creates an `inbound-rtp` for the track at all. The single piece of evidence anywhere is
    one line of the core's log:

    ```
    livekit::room: dynacast: SFU quality update for TR_…: subscribed_codecs="vp8:[Low=true, Medium=false, High=false]"
    ```

    Two asks, either of which would have saved the session:

    - **A single-encoding publisher should be forwarded whatever quality is requested.** Pausing a publisher's only
      layer because a subscriber asked for a size it does not offer is a configuration that cannot work, so it
      should not be reachable. Failing that, `publish` could warn when `simulcast = false` on a video track.
    - **Expose send-side statistics**, so a host can see this for itself rather than from the far end's console.
      Item 15.

    We now hardcode `simulcast = true`. Since dynacast cannot be disabled through `FfiPublishOptions`, that flag is
    the host's only defence, which makes `false` a value with no safe use for video.

14. **Every video frame is copied twice in each direction, and neither copy is avoidable today. On a four-person
    call this is 82% of the app's CPU.** ~~Neither is urgent at VGA.~~ It was written up as a scaling concern for
    later; profiling it turned that judgement over completely, and the numbers are under "what it actually costs"
    below. This is now the highest-value open item in this file for us.

    **Receiving** — `VideoFrameRef` offers `planePtr`/`planeLen` for zero-copy access, which is exactly what a
    renderer wants, but the lifetime contract is not documented and guessing wrong is a use-after-free rather than
    a wrong picture. So we use `data(plane)` instead, which copies native memory into a `ByteArray`, and then copy
    that into a direct `ByteBuffer` because a direct buffer is the only thing a GL renderer can take without
    copying again. Two copies where zero would do. To use `planePtr` we need: how long the pointer stays valid
    relative to the `VideoFrameRef`, whether `destroy()` must happen on the thread that created it, and whether the
    memory may be read from a thread other than the one `next()` returned on.

    **All three are now answered, and written into `frames.rs` so they arrive as KDoc rather than living in a
    thread.** `planePtr` + `stride` is documented as the default read path and `data(plane)` as the fallback that
    costs two full copies per plane. The contract:

    - **Lifetime is the object.** The address is valid for as long as any reference to the `VideoFrameRef` is
      alive, and is stable across calls. The stream does not retain the frame.
    - **No thread affinity, either direction.** Read from any thread, any number at once, unsynchronised - the
      planes are written once at construction and never mutated. Release from any thread; dropping is three
      allocator frees, no runtime and no JNI attach.
    - **The one rule is ordering, not identity:** every read must happen before the release, and the release must
      happen exactly once.
    - **Holding several frames is safe.** Each frame owns its allocations and nothing is recycled between them, so
      a renderer with one in flight and the next arriving is fine. Holding costs memory and, at worst, a dropped
      frame - never a corrupted one.

    It also turns out to be worse than two copies. The library counts three-plus on the receive side alone:
    `to_media_video_frame` does a full `to_i420()` and then `to_vec()` on all three planes, `VideoFrameRef.data()`
    clones them again, and JNA copies once more into a `ByteArray`. At 720p that is ~1.4 MB a copy, per frame, per
    stream - a couple of hundred MB/s of memcpy on two streams at 30 fps, which is the likeliest thing starving the
    bandwidth estimate behind the 8x8 collapse in item 16.

    **Sending** — there is no zero-copy option at all, documented or otherwise: `FfiVideoFrameData` takes
    `ByteArray` per plane. So a captured frame is packed out of the camera's buffers into byte arrays for
    `captureVideo`, and then copied again into direct buffers for the self view. A pointer-and-length variant of
    `FfiVideoFrameData`, mirroring what `VideoFrameRef` already exposes on the receive side, would remove the first
    of those.

    Offered two designs for it - a pointer twin of the existing record, or one that carries the camera's real
    pixel layout so a semi-planar frame needs no de-interleave - **our answer is the pointer twin, and only that.**
    We never touch `YUV_420_888`: capture is `Camera2Capturer` + `SurfaceTextureHelper`, so frames arrive as OES
    textures and `VideoFrame.Buffer.toI420()` converts them on the GPU. What reaches `captureVideo` is a
    `JavaI420Buffer` - genuinely planar, chroma pixel stride 1, by construction. The layout-aware design would only
    pay off if we moved to `ImageReader` capture, and we have no other reason to.

    One thing that changes how much the send side is worth, and which is ours rather than the library's: that
    `toI420()` is a GPU-to-CPU readback, and it stays whatever the FFI accepts. It shows up in the profile as the
    9% attributed to camera capture. So the send-side win is real but bounded - **the 82% is on receive**, which is
    where we are spending the effort first.

    **What it actually costs.** A user reported the phone getting hot, so we profiled instead of guessing: a
    four-person call, three remote video streams decoding, sampling `top -H` every five seconds and attributing by
    thread name over 23 samples.

    | thread family                          | CPU per sample | share |
    | :------------------------------------- | -------------: | ----: |
    | our Kotlin frame path (`DefaultDispatcher`) |          167% |   53% |
    | GC / heap (`HeapTaskDaemon` and friends)    |           90% |   29% |
    | camera capture                              |           28% |    9% |
    | GL render                                   |           15% |    5% |
    | **codec — hardware encode *and* decode**    |        **5%** |**2%**|
    | core / tokio                                |           ~0% |    0% |

    Total process CPU held at **390-500%** - four to five cores saturated - on a mid-range phone. Hardware encode
    and decode together cost 2%. Drawing costs 5%. **Moving the pixels around costs 82%**, split between the copies
    themselves and collecting the garbage they make.

    The allocation side is visible without a profiler: 201 garbage collections during the capture, with the large
    object space climbing 85 -> 115 -> 161 MB. Those are frame buffers. Per frame, per stream, our mapper does six
    copies and allocates three fresh direct `ByteBuffer`s - at 720p that is ~2.8 MB allocated per frame, ~83 MB/s
    for one stream, and we had three.

    One caveat on attribution, since it changes what the 53% proves: `DefaultDispatcher` is where *all* our
    coroutine work runs, so that share is not attributed to the mapper by name. We infer it from the allocation
    signature - large-object churn at exactly frame sizes - rather than from a method-level profile. The 29% in GC
    is unambiguous either way, and GC is caused by allocation.

    **Next step on our side: adopt `planePtr`.** It removes copies 3-5 of the five on the receive path - our
    `data(plane)` call, the JNA marshal, and `packPlaneToDirectBuffer` - and with them the three fresh direct
    `ByteBuffer`s a frame, which is the 29% GC share outright. Two implementation notes, the second of which is
    worth passing to any other Android host:

    - **Turning an address into something a renderer can take needs JNA.** `planePtr` returns a `u64`, and there is
      no pure-Java way to wrap an arbitrary address in a direct `ByteBuffer`. `Pointer(addr).getByteBuffer(0, len)`
      does it - verified present in the JNA the AAR already pulls in (5.19.1) - and the result is a view that owns
      nothing. That feeds `JavaI420Buffer.wrap(...)` with the native stride passed straight through, so the stride
      normalisation our packer does today disappears too.
    - **The host's own conflation buffer is a leak path, and it is the likelier one.** The docs warn that a release
      callback failing to fire leaks the frame. On Android the renderer is not the main risk - a `Flow` is. Our
      receive path does `.buffer(1, DROP_OLDEST)` and then `shareIn`, and a frame dropped by either is never
      collected, so its callback never runs: a leaked megabyte per drop, and dropping is the *normal* case for a
      renderer that falls behind. `buffer(...)` has no undelivered-element hook, so this has to become
      `Channel(1, DROP_OLDEST, onUndeliveredElement = { it.release() })`. Worth a line in the docs beside the
      renderer warning, since anyone bridging this contract to a `Flow` will hit it.

    Sequenced as its own change rather than folded into feature work: it turns `MatrixRtcVideoFrame` from a value
    into a resource that every consumer must close exactly once, and lifetime changes in this area have cost us
    three crashes in a day.

    **Adopted.** The receive path now wraps `planePtr` in place via `Pointer(addr).getByteBuffer(0, len)` and
    passes the native stride straight through to `JavaI420Buffer.wrap`, so no pixel is copied between the decoder
    and GL. Both predictions above held: `data(plane)` and the repacking are gone, and with them the three fresh
    direct `ByteBuffer`s per frame. Two notes for the docs, both about things that were not obvious from the
    contract alone:

    - **One reference is not enough, and this is the part worth documenting.** The contract says "every read
      happens before the release", which reads as single-ownership - but on a real host a frame legitimately has
      several holders at once. The stream holds one while it is in flight; libwebrtc's renderer takes its own and
      keeps it past the call that handed the frame over, because drawing happens later on the GL thread; and a
      frame delivered to two tiles has one holder each. We ended up reference counting, with `JavaI420Buffer.wrap`'s
      release callback as the renderer's handover. A host that reads the contract as "release it when you are done"
      will free memory while the GL thread is still reading it, and the symptom is a native fault in the renderer
      rather than anything pointing back here.
    - **The `Flow` leak is real and we hit it.** As predicted, `buffer(1, DROP_OLDEST)` silently leaks every
      dropped frame, because a dropped element is never collected and so nothing downstream can release it -
      and dropping is the *normal* case for a renderer under load, so it leaks hardest exactly when it is busiest.
      `Channel(1, DROP_OLDEST, onUndeliveredElement = { it.release() })` is the fix. Worth a line beside the
      renderer warning, since it is invisible until a device runs out of memory: nothing fails, the picture is
      perfect, and the process dies several minutes later.

    **Measured on device, four-way call, three remote video streams.** The prediction that GC would go was the
    one worth checking, and it held:

    | thread | before (VGA 640x480) | after (720p) |
    | :--- | ---: | ---: |
    | `HeapTaskDaemon` (GC) | 20.0% | **6.3%** |
    | total process | 306% | 364% |
    | phone temperature | 35.2 C | 38.3 C |

    **The GC number is the clean one and it is the point:** garbage collection fell by two thirds *while decoding
    three times the pixels*, because there is no longer anything per-frame to collect.

    **The totals are not a like-for-like comparison and should not be read as one.** Between the two runs the
    senders moved from VGA to 720p, so the "after" column is doing about three times the work per frame across
    three streams for 19% more CPU. A clean measurement needs both runs pinned to the same resolution, which we
    have not done.

    What did change is *where* the time goes. The frame path is no longer the bottleneck; `EglRenderer` now reports
    **5-13 ms per `swapBuffers`** against 0.5-1.9 ms at VGA, holding the renderers to 5-9 fps. That is GPU
    compositing of four 720p `TextureView`s, not our copies - the cost simply moved. The next lever turned out to
    be entirely on the host side, and is item 20: we were not using `setConstraints`, so every tile asked for full
    resolution however small it was drawn. Adopting it recovered the frame rate completely.

    **Three ownership bugs on the way, all of which reached a device, none of which produced an error.** They are
    recorded because they are all consequences of the same gap in the contract, and any other host bridging
    `planePtr` to a UI will hit them:

    1. **`flowOn` after the conflation.** `flowOn` inserts its own 64-deep channel, so `emit` returned when the
       frame was *queued*, not when a renderer took it - and the release fired on a frame still in the queue.
    2. **`shareIn` is an asynchronous handoff.** `SharedFlow.emit` resumes once a subscriber has been *woken*, not
       once its collector has run. Releasing after `emit` frees the frame while the renderer is being dispatched
       to. No `Flow` sharing operator can carry a borrowed frame safely; we had to write an explicit fan-out that
       retains once per subscriber before offering.
    3. **Two layers each releasing the one producer reference** - a double free, which freed every frame the
       instant it was dispatched.

    The symptoms were, in order: about a third of frames missing, then a black picture, then a black picture again.
    **Not one of them raised anything.** The only reason none of it was a native use-after-free is that our frame
    refuses `retain()` after reaching zero, and the renderer skips a frame it cannot retain - a defensive check
    worth recommending to anyone else doing this, because it converts a memory fault into a visible frame rate.

    The common thread is that the contract's "every read happens before the release" is necessary but reads as
    single-ownership, and a real host has several holders at once - the stream, the renderer's GL thread, one per
    tile. **A worked example of the reference counting a UI actually needs would be worth more in `frames.rs` than
    another paragraph of prose.**

    Both directions still scale with resolution, so both remain the reason a host cannot raise it.

15. **No send-side statistics, which is why item 13 took a debugging session.** There is
    `receiveStats(memberId, kind)` and no counterpart, so everything past `captureVideo` is invisible to a host:
    frames encoded, bytes sent, keyframes emitted, which simulcast layers are live. One-way media is *the* common
    RTC failure, and diagnosing it currently means reading the far end's `chrome://webrtc-internals` — which is
    fine for us with an Element Web peer on the same desk, and no use at all to a user reporting "they cannot see
    me". Even a coarse `sendStats(kind)` with frames and bytes would have turned item 13 from a session into a
    minute.

16. **~~JNA detaches libwebrtc's decode thread on every frame, and the process aborts on a JNI
    invariant.~~ Fixed in the library, verified on device.** It killed any call with video within
    minutes. Kept in full, and not struck through beyond the title, because almost everything under
    it is a correction to something we got wrong - the mechanism, the measurement, and twice over
    what our own data said. Diagnosed by the matrix-rust-rtc team from the report below.

    **What it actually is.** Two libraries share one thread and disagree about who owns its JVM
    attachment. Delivering a frame takes libwebrtc through `AttachCurrentThreadIfNeeded`, which
    attaches the decode thread and caches a `JNIEnv*` in thread-local storage. Microseconds later
    the same thread wakes a uniffi future; uniffi's `Scheduler::wake` invokes the foreign
    continuation *inline on the calling thread* rather than handing it to a runtime, so it lands in
    a JNA callback. JNA believes it owns any thread it attached and detaches on the way out,
    leaving libwebrtc's cached pointer behind. The next frame's JNI call trips
    `RTC_CHECK(!pthread_getspecific(g_jni_ptr))`. One thread, one TID, two frames - the thread count
    is irrelevant.

    **The fix, and it is theirs.** A JNA `CallbackThreadInitializer` with `detach = false`,
    registered in `MatrixRtc.initialize()` before `System.loadLibrary`, pinning the attachment for
    the life of the thread so the cached pointer stays valid. It went further than the single
    callback the diagnosis needed: both future callbacks by name, and the twelve trait-interface
    methods reached by walking each generated vtable's fields, which keeps regenerated bindings
    covered. The log sink matters nearly as much as the frame path - libwebrtc's own C++ log records
    reach Rust on libwebrtc's threads, so `RtcLogSink.log` is a second per-event JVM entry on
    exactly the threads that break.

    Worth recording that this was offered to us as a host-side change and is not one. `MatrixRtc`
    and the bindings are both inside the AAR and the callback singletons are `internal` to that
    module, so a host can only reach them through a Java shim written to bypass Kotlin's visibility
    check on a generated symbol. We prototyped exactly that, confirmed it compiles and resolves
    `INSTANCE`, and then deleted it: it is the library's own initialisation, it would break or go
    redundant on the next AAR, and it is not a coupling a host should carry.

    **Verified on device** with the AAR carrying the fix, on the scenario that used to abort within
    minutes - two remote video streams decoding (3000 frames at 1280x720, 300 at 480x640) for ~90 s:

    | signal                                    | before        | after                 |
    | :---------------------------------------- | :------------ | :-------------------- |
    | `Thread-N` counter during video           | 29 -> 8601    | 29 -> 80, then flat   |
    | threads named `matrixrtc-jna-cb`          | 0             | 7                     |
    | abort                                     | yes, ~2 min   | none                  |

    The counter going flat is the direct signature: JNA now attaches once with a name instead of
    attaching anonymously per callback, so ART stops minting `Thread-N`. We also grepped for
    `Native thread exiting without having called DetachCurrentThread`, the one regression pinning
    could plausibly introduce - **zero hits** across the runs before and after the fix.

    One data point that complicates the predicted codec dependence: the decoder negotiated on the
    aborting calls was `c2.qti.vp8.decoder`, a *hardware* decoder, driven through libwebrtc's
    `AndroidVideoDecoder`. Hardware decode did not avoid it. That is consistent with the tombstone,
    whose crashing thread bottoms out in `__pthread_start` - a native libwebrtc thread, not
    `AndroidVideoDecoder`'s Java output thread.

    **Where our diagnosis went wrong, since the reasoning is worth more than the conclusion.**
    We sampled `ps -T` and watched the `Thread-N` suffix climb to 10,694 in 173 s, tracking frame
    delivery exactly - including collapsing to 1.5/s during a stall, which we took as a control.
    That number is the JVM's process-wide thread counter, not a thread count: JNA attaches with a
    null name, ART falls back to `"Thread-" + nextThreadNum()`, and pushes it down to the thread's
    `comm`. We were measuring the *attach* rate, which is indeed the frame rate.

    Two disproofs were already in our own data and we read past both:

    - The live count from `ps -T | wc -l` sat between 128 and 195 while the counter passed 8601 -
      logged on the same line as the number we quoted.
    - The audio-only row of the table below reads 2.6/s, but audio frames also arrive at ~100/s.
      Under "one thread per delivered frame" audio should have been near 100/s too. The 40x gap is
      explained by the attach reading and not by ours: Android's audio device module is Java-side, so
      those frames already arrive on attached threads and mint no name.

    A third correction, from the library team, on how to settle it: **neither the live count nor
    `/proc/<pid>/status` can discriminate**, which we had asserted they could. ~110 threads a second
    each living about a second also sits at 130-190 live at any instant. Only TID *identity*
    separates the two readings - two `ls /proc/<pid>/task` samples ten seconds apart, intersected.
    Near-total overlap means renaming; a mostly disjoint set means real creation.

    The tombstone, which is what identified the call site:

    ```
    pid: 23586, tid: 25181, name: Thread-10694
    signal 6 (SIGABRT), code -1 (SI_QUEUE)
    # Fatal error in: ../sdk/android/src/jni/jvm.cc, line 124
    # Check failed: !pthread_getspecific(g_jni_ptr)
    # TLS has a JNIEnv* but not attached?
      #00 abort+164
      #09 livekit_ffi$cxxbridge1$194$VideoSinkWrapper$on_frame+72
      #18 __pthread_start(void*)+208
      #19 __start_thread+64
    ```

    `Thread-10694` after 173 s of process uptime. Sampling `ps -T` every two seconds through a call
    with two remote video streams, the rate tracks frame delivery exactly - the numbers were right,
    it is the label on the first column that was wrong:

    | what the call was doing        | JVM attaches | rate    |
    | :----------------------------- | -----------: | ------: |
    | idle, no call                  |            0 |    0 /s |
    | connected, audio only          |           92 |  2.6 /s |
    | two remote videos flowing      |         3759 | 110 /s  |
    | video stalled (see below)      |           49 |  1.5 /s |
    | video flowing again            |         2804 |   85 /s |

    ~110/s is about what two streams at 30 fps plus our own camera come to, and the collapse to
    1.5/s during the stall is a genuine control - when no frames are delivered, almost nothing
    attaches. It is one *attach* per delivered frame, on the same handful of threads.

    Four separate crashes over one afternoon all reduce to this, at two call sites
    (`VideoSinkWrapper::on_frame`, and once with the sink frames absent entirely). It reproduces
    faster with more video: a two-party call survives, three participants with cameras on crashes
    within a few minutes, and toggling a camera off and on brings it forward. All four are gone with
    the pinned AAR.

    Two symptoms we offered as downstream of this, both since reattributed:

    - **Video goes black while RTP keeps arriving.** `receiveStats` kept climbing normally
      (`423 pkts, 0 lost`) while `EglRenderer` logged `Frames received: 0` for a four-second window,
      and no frame reached us for 55 s before recovering. Judged a decoder or keyframe stall rather
      than a threading effect. **A caveat for whoever follows that advice:** `receiveStats` is keyed
      by stream kind, so `frames_decoded` only means anything on the `CAMERA` stream. We were asking
      for `MICROPHONE`, whose report carries no frame counters, and reading a constant zero off it
      while 3000 video frames decoded - which reads exactly like "no video is arriving". We now query
      both kinds and log them apart.
    - **A collapse to an 8x8 frame** immediately before that stall, having stepped
      1280x720 -> 320x180 -> 8x8. On a remote stream that is sender- or SFU-side adaptation, so a
      bandwidth-estimate collapse rather than dynacast; the per-frame copy cost is the likelier thing
      starving it.

20. **~~Receive-side dynacast works exactly as documented — this one was our bug, not yours.~~** Recorded because
    it is the counterpart to item 13 and because the failure looked identical to a library problem: the phone was
    hot, remote video was at 5-9 fps, and every stream was arriving at the sender's best layer no matter how small
    we drew it.

    The cause was that we never called `setConstraints`. With nothing said, the SFU quite reasonably sends its best
    layer, so a 340px strip tile was receiving, decoding and compositing 1280x720 to draw it a quarter that size.
    Reporting each tile's real pixel size fixed it outright:

    | | asking for nothing | asking for the tile's size |
    | :--- | ---: | ---: |
    | `swapBuffers` | 5-13 ms | **0.7-1.0 ms** |
    | render time | 5-8 ms | 1.2-1.9 ms |
    | renderer frame rate | 5-9 fps | **20-30 fps** |
    | process CPU | 364% | ~300% |
    | phone temperature | 38.3 C | **34.2 C** |

    The layer selection is precise and follows the layout live. Promoting a member between the spotlight and the
    strip changes what we ask for, and what arrives tracks it within a second:

    ```
    constraints for @alice CAMERA - 1036x777     ->  decoded ... from @alice, 1280x720
    constraints for @alice CAMERA - 507x380      ->  decoded ... from @alice, 320x180
    constraints for @valere35 CAMERA - 507x380   ->  decoded ... from @valere35, 640x360
    ```

    Two notes for other hosts, since neither is obvious from the signature:

    - **`FfiVideoDetail.Dimensions` is the right variant for a UI**, rather than `Quality(LOW|MEDIUM|HIGH)`. A
      layout already knows the exact pixel size it is drawing at; a quality band is only a guess about that same
      number, and the SFU is better placed to map size to layer than we are.
    - **It needs de-duplicating.** A tile recomputes its size on every layout pass, and an animated promotion
      changes its rectangle sixty times a second. We key on the tile's *target* rectangle and drop unchanged
      values, so a promotion sends one message rather than sixty.

    **One thing we cannot explain**, offered as an observation rather than a claim. During rapid promotion all
    three remote streams briefly dropped to 320x180 together and recovered about a second later, which the layout
    never asks for - we never request the smallest layer for every tile at once. It may be a bandwidth-estimate
    collapse, which item 14 records seeing before (1280x720 -> 320x180 -> 8x8 ahead of a stall), or the SFU
    re-evaluating every layer when several constraint changes arrive together. It self-corrected, and we have not
    chased it.

21. **Two things a receiver cannot find out about a stream it is decoding: what codec it is, and which SFU it came
    from.** Both surfaced while building a per-tile debug readout - resolution, frame rate, bitrate, loss, jitter,
    encryption - which is the tool a host reaches for when a call looks wrong and nothing is obviously broken.
    Everything else we wanted was already available; these two were not.

    **Codec.** `FfiReceiveStats` carries `packetsReceived`, `packetsLost`, `bytesReceived`, `jitter`,
    `framesDecoded`, `framesDropped` and the concealment counters, and nothing identifies the codec or its
    parameters. "Why does this look soft" and "why is this decode expensive" both start with VP8 / VP9 / H.264 /
    AV1, and it is the first column anyone opens `chrome://webrtc-internals` for. It also pairs with item 15: with
    no send-side statistics *and* no codec on the receive side, a host debugging one-way or poor-quality media is
    working from the far end's browser.

    **The SFU behind a participant.** Matrix RTC allows a call to be carried by more than one SFU, so "which SFU is
    this member on" is a per-participant question, not a property of the call - and in a federated deployment it is
    the first question when one member looks different from the rest. `FfiRtcTransport` appears only as session
    *input*: the list `join` accepts and the one `connectMedia` takes. `FfiParticipant` exposes `memberId`,
    `userId`, `deviceId`, `reachable` and `streams`, with no way back to the transport carrying them. The
    information exists on the wire - MSC4143 memberships carry `foci_preferred` - so this is about the FFI not
    surfacing it rather than the core not knowing.

    `reachable` is the nearest thing available and we now show it, but it only answers "can our transport see them
    at all", which is a much coarser question. A transport identifier on `FfiParticipant`, or the member's
    `foci_preferred` on the membership, would cover it.

### Audio

22. **Handing the host PCM frames hands it a real-time obligation, and nothing says so — we shipped a crackle
    because of it.** `FfiLocalTrack.captureAudio` and `AudioFrameStream.next()` put the host on both sides of a
    10 ms deadline: the device drains its buffer whether or not we refilled it, and what it plays in the gap is an
    audible crackle. That is not obvious from an API that looks like a queue, and the docs never mention a thread,
    a priority or a buffer size.

    We ran both loops on `Dispatchers.IO`, which is the obvious choice and, on Android, the wrong one — it is a view
    over the same pool as `Dispatchers.Default`, so the loops sat among ordinary `nice = 0` workers behind video
    decode, encode and GC, while the `AudioTrack` and `AudioRecord` threads they feed run at `nice = -16`.

    Measured on a Pixel 5 in a five-party call, using our own frame counter — 500 frames is 5.00 s of audio by
    construction, so the wall clock between two log lines is the drift:

    | | camera off | camera on |
    | :--- | ---: | ---: |
    | 500 frames of playback | 4.98–5.02 s | **5.45–5.97 s** |
    | `receiveStats` 1 s poll period | ~1.08 s | **1.39–1.53 s** |
    | GC `total` | 0.5–1.4 s per 13 s | **2.2–3.6 s**, near-continuous |

    Roughly 15% of every track's output was under-run samples. Enabling the local camera is what tipped it over and
    turning it off is what made it stop — and `receiveStats` read `0 lost, 0% invented, jitter 2–13 ms` throughout,
    so every counter the FFI offers said the call was healthy while it audibly was not. The fix was a thread per
    loop at `THREAD_PRIORITY_URGENT_AUDIO`, a device buffer floored at 100 ms instead of `getMinBufferSize`, and
    dropping a per-frame allocation on the playback path.

    **The ask is documentation, not API.** `WebRtcAudioTrack` and `WebRtcAudioRecord` in the AAR's own bundled
    `libwebrtc.jar` already set `THREAD_PRIORITY_URGENT_AUDIO` — the knowledge is in the box, it is just in the half
    the FFI does not use. A line on `captureAudio` and on `audioStream` saying "call this from a dedicated
    audio-priority thread, and do not share it with other media work" would have saved this, and will save it for
    every host that ships PCM frames.

    **A second, smaller ask that would have shortened the hunt:** there is no receive-side counter for time the
    stream was *ready* to hand over but nobody took it. `concealedSamples` covers the jitter buffer inventing audio
    it never received; it does not cover the host failing to drain what did arrive, which is the failure we had.
    Everything we used to find this we had to add ourselves.

23. **A member publishing audio can reach us with no microphone stream on their `FfiParticipant`, so we never open
    playback and draw them as muted while everyone else hears them.** Seen on a five-party call: `@johannesm` was
    unmuted and audible to every other participant, including the same account joined to the same call from Element
    Call on web. On Android his roster entry carried a `CAMERA` stream and no `MICROPHONE` stream at all.

    It is not a mute-state desync, and the counters say so. `receiveStats` is asked for every remote member whose
    `streams` contain a `MICROPHONE`, with **no mute filter** — a muted-but-publishing member still produces an
    `rx audio` line. He produced none for the whole call, while his camera was entirely healthy over the same
    window:

    ```
    rx video @johannesm:element.io:5pBJWCmRVx - 14694 pkts, 0 lost, 2134 decoded, 383 dropped
    decoded 450 CAMERA frame(s) from @johannesm:element.io:5pBJWCmRVx, 1280x720
    constraints for @johannesm:element.io:5pBJWCmRVx CAMERA - 1036x777
    ```

    So the stream is missing from the projection rather than present-and-muted, and every consequence follows from
    that: the `participants()` sweep and `StreamStarted` both filter on the stream kind, so playback is never
    opened; `audioStream` is never called; and there is no event, no error and no counter anywhere that says his
    audio is missing. From our side the call is completely healthy — the same one-sided shape as item 17.

    **What we cannot tell from here** is whether the microphone stream never entered the roster, or entered and was
    dropped, or is being filtered on some property we cannot see. `FfiParticipant.streams` is the whole of what we
    get, and a stream that is not in it is indistinguishable from one that never existed.

    Two things would each have been enough to catch this without a second client:

    - **A reason a stream is absent.** Even `streams` carrying the microphone with a state of "not subscribed" or
      "unavailable", rather than omitting it, would turn a silent absence into something reportable.
    - **Send-side or roster-level counters** (item 15, item 21). With no way to ask "is this member publishing
      audio", "we are not receiving them" and "they are not sending" are the same observation.

    We now warn once per member — `<member> publishes no microphone stream ... streams: CAMERA` — and show
    `NO MIC STREAM` on the tile overlay, because the mute badge is the right thing to draw for a viewer and the
    wrong thing to debug from. That is a workaround for the symptom; it does not tell us why the stream is absent.

### Notifications

17. **The core never asks the host to send an `m.rtc.notification`, so a call it places cannot ring anyone.**
    Joining publishes a membership and connects media, and that is all that reaches the wire. There is no
    notification command on the command sender — the trait carries `sendStickyEvent`, `sendStateEvent`,
    `sendDelayedEvent`, `cancelDelayedEvent`, `restartDelayedEvent` and `sendToDeviceMessage`, and nothing else —
    and `MatrixRtcEventTypes` has no notification type because there was never anything to name.

    The consequence is one-sided in a way that hides it: our end of the call is completely healthy, and the other
    end simply never finds out. Element X's own incoming-call path keys entirely off a push for
    `org.matrix.msc4075.rtc.notification` (`CallNotificationEventResolver` refuses anything that is not
    `NotificationContent.MessageLike.RtcNotification`), so a native call rings no Element X, no Element Call and no
    Element Web. Nothing in our logs says so, because from here nothing failed.

    **This is a core responsibility rather than a host one, which is why we are asking rather than working around
    it.** The notification has to reference the membership the core just published (`m.relates_to` → the membership
    event id), carry the intent the call was started with, and have a lifetime the core also owns through
    `keepAliveTimeoutMs`. A host sending it would be reconstructing state the core already has, and would get the
    relation wrong whenever the membership id is one the host never saw — which is every dialect except
    `STATE_EVENTS`, since `sendStickyEvent` returns no event id.

    The shape itself is settled and small (`ruma-events/src/rtc/notification.rs`): `sender_ts`, `lifetime` and
    `notification_type` are required; `m.mentions`, `m.relates_to` and `m.call.intent` are optional. An
    `FfiNotificationOptions` on `join`, or a `sendNotification` command alongside the existing ones, would cover it.

    Two events captured off the wire, for reference. A ring:

    ```json
    {
      "type": "org.matrix.msc4075.rtc.notification",
      "content": {
        "notification_type": "ring",
        "m.call.intent": "audio",
        "lifetime": 30000,
        "sender_ts": 1786308042956,
        "m.mentions": { "room": true, "user_ids": [] },
        "m.relates_to": {
          "event_id": "$dtMn65n3zEsaczxWFQz78-mt4gHdMTF4OBlytfHkq78",
          "rel_type": "m.reference"
        }
      }
    }
    ```

    and a non-ringing notification, differing only in `notification_type` and the intent:

    ```json
    { "notification_type": "notification", "m.call.intent": "video", "lifetime": 30000, "…": "…" }
    ```

    Note `lifetime` is 30 s in both, not the 90 s Element X rings for
    (`ElementCallConfig.RINGING_CALL_DURATION_SECONDS`) - the receiver takes the shorter of the two, so whoever
    owns this value is deciding how long a callee's phone rings.

    Both carry `m.relates_to` pointing at the membership, which is the practical half of the argument above: real
    senders relate the notification to the membership event, and a host cannot do that in any dialect where
    `sendStickyEvent` returns no event id. A host-side implementation would be visibly poorer than what is already
    on the wire.

    **Resolved, in the AAR that added `FfiNotifyConfig`.** `join` now takes a notification request
    (`notificationType`, `intent`, `lifetimeMs`, `mentions`) and the core sends the `m.rtc.notification` itself, so
    a native call rings. Verified on device: a call in a DM joins with `RING` and the notification lands as an
    encrypted `org.matrix.msc4075.rtc.notification` that Element X's own incoming-call path resolves and rings on;
    a call in a group room joins with `NOTIFY`. The suppression is the core's too — passing a config on a join into
    a session someone is already in rings nobody, so the host does not have to tell starting from joining apart.

    **One thing left over, and it is ours rather than the core's.** The notification goes out through
    `sendStickyEvent`, and its `m.relates_to` points at the membership event — which means the relation is only as
    good as the event id that callback returned. In `StateEvents` compat the membership goes out through
    `sendStateEvent`, which reports a real id, so the relation is right; that is the mode the device test above ran
    in. In the sticky dialects the membership goes out through `sendStickyEvent`, where `sendStickyRaw` reports
    nothing and we answer with an empty string (item 9 below) — so there the notification cannot be related to the
    membership that justifies it. Untested on device so far, and worth doing before the sticky dialect is the
    default: it needs the SDK change in item 9, not a core one.

### Screen sharing

Screen share works end to end from Android: `ScreenCapturerAndroid` out of the bundled `libwebrtc.jar` over
`MediaProjection`, published as `FfiStreamKind.SCREEN_SHARE`, and Element Call web sees it. One gap turned up
immediately — the mirror image of the camera's — and has since been closed by `MediaSession::unpublish`.

18. **~~There is no way to un-publish a track, so a screen share cannot be *stopped* — only muted — and receivers
    go on drawing an empty tile for it.~~ — resolved, and the smaller of the two options we asked for is the one
    that landed.**

    `MediaSession` gained `unpublish(kind: FfiStreamKind)`, mirroring `publish`. It removes the publication at the
    SFU, removes it from our own roster entry and emits `StreamStopped`, which is exactly the shape this item asked
    for. Integrated in `RustMatrixRtcCall.setScreenShareEnabled`: stopping a share now stops capture and then
    unpublishes, and the `FfiLocalTrack` is dropped rather than kept, so the next share publishes fresh.

    **Verified on device**, one share started and stopped in a live call. The sequence, ~70 ms end to end:

    ```
    12:27:09.952  MatrixRTC: screen capture stopped after 108 frame(s)
    12:27:09.952  Display device removed: "WebRTC_ScreenCapture" 590x1280
    12:27:09.966  matrix_rtc_ffi::media::session: media: unpublishing our own ScreenShare
    12:27:10.000  → offer: m=video mid:3 … a=inactive          (the screen-share transceiver)
    12:27:10.007  MatrixRTC: screen share stopped
    12:27:10.018  NativeCall: StreamStopped(kind=SCREEN_SHARE)
    ```

    The renegotiated offer is the part worth having in writing: the screen-share `m=video` section goes to
    `a=inactive` while the camera's stays `a=sendonly`, so the retraction is visible in the SDP rather than only in
    the roster. No warning from our unpublish call, no `MediaFfiException` from the capture teardown, and the
    receiver dropped the tile.

    Three things in the answer were worth more than the method itself, because all three were guesses we would
    otherwise have had to make on a device:

    - **The `FfiLocalTrack` is dead after `unpublish`** — `captureAudio`/`captureVideo` fail with a transport error
      rather than silently succeeding. That is the behaviour that lets a host drop the handle with confidence; the
      alternative, a handle that accepts frames into nothing, is the failure we would not have noticed.
    - **A capture thread still inside `captureVideo` during the unpublish is safe**, and does not crash. This is the
      ordering note below, answered. We still stop capture first, because `ScreenCapturerAndroid.stopCapture()`
      blocks until its thread is done and costs nothing, but it is no longer load-bearing.
    - **Dropping or `close()`-ing the `FfiLocalTrack` never retracts a publication**, now stated in the docs. That
      is the assumption we made and got wrong when this item was written, and it was indistinguishable from the
      host side.

    The diagnosis, kept because it is the reason a screen and a camera cannot share one mechanism: for the camera,
    mute-and-keep-published is exactly right and we still rely on it — a peer can tell a deliberate camera-off from
    a sender that has wedged, and re-enabling does not renegotiate. **A screen has no equivalent of "off".** There
    is a screen being shared or there is not, and every client draws a tile for a screen-share track that exists.
    Observed on the first end-to-end run: we stopped sharing, our capture stopped and we muted the track — and
    Element Call web went on showing a screen-share tile, grey, for the rest of the call.

19. **`simulcast` is the wrong axis for a screen, and we cannot opt out of it.** Item 13 records that
    `simulcast = false` makes dynacast pause the only layer and send nothing at all, so we pass `true` for the
    screen share as well. For a camera that is right. For a screen it is close to backwards: the content is mostly
    static text, where one sharp layer beats three soft ones, and the low layer a peer subscribes to for a small
    tile is precisely the one in which text stops being readable.

    `FfiPublishOptions` offers `simulcast` and nothing else, so there is no way to say "one layer, and do not pause
    it". A dynacast switch, or screen-content encoder defaults keyed off `FfiStreamKind::SCREEN_SHARE`, would let
    the trade-off go the right way per stream kind. Until then we ship the soft version, because the alternative
    silently ships nothing.

### Encryption

Found while testing item 18, in a call that was otherwise healthy: the far end could not decrypt our video for the
whole call, and leaving and rejoining was the only cure. Both entries are one bug seen from two sides — a key that
went to the wrong membership, and the rotation that should have healed it and did not.

24. **Our outbound key is distributed to a membership that has already left, and the member who actually arrives is
    then treated as already holding it — so the far end can never decrypt us.**

    One call, one remote member (Element Call web), `element_call_compat=StateEvents`, encrypted room. Our camera
    published fine, `FrameEncryption` reported `OK` for us, we decoded *their* video for 300+ frames with 0 lost —
    and their tile for *us* stayed grey from the first frame to the last. What the log says, all of it from the
    core:

    ```
    12:35:43.565  First outbound key created with index 0
    12:35:43.565  sending key index 0 for member @alice:…:V5cP8FErcB to @alice:…/V5cP8FErcB
    12:35:43.714  distributed key index 0 to 1 member(s)
    12:35:43.739  host: feeding 35 state membership(s) (ours=3, departures=34)
    12:35:43.760  host: core reports 1 member(s) after feeding          ← alice is not one of them
    12:35:43.751  rotation deferred to +9814ms (1 member(s) who held the key have left):
                  key index 0 is still fresh (186ms of 10000ms)
    12:35:43.752  no distribution needed, rotation deferred
    12:35:47.932  sfu event: RemoteJoined { identity: "@alice:…:V5cP8FErcB" }   ← 4.2 s later
    12:35:48.615  host: feeding 35 state membership(s) (ours=3, departures=33)  ← alice's live membership
    12:35:48.651  no distribution needed                                        ← and we send them nothing
    12:35:48.663  host: core reports 2 member(s) after feeding          ← the core agrees she is here
    ```

    We minted key index 0 and sent it to a membership the core itself noticed had left, 186 ms later — and its own
    member count, read back straight after the feed, was 1. Then the member *actually* arrived four seconds later,
    the host fed the state event carrying it within 0.7 s, the core's member count went to 2 — and the distribution
    pass in between those two lines concluded "no distribution needed", because a member id it had already sent
    index 0 to is, as far as it can tell, holding it. The far end had rejoined with a fresh session and had nothing.

    **The host side is ruled out by those two `core reports N member(s)` lines**, which is why they are in the
    trace. The membership feed is a live subscription, it delivered the arrival promptly, and the core acknowledged
    it — 1 member before, 2 after. The key simply was not sent. (The `No matching RTC membership for key from
    member @alice, buffering` at `12:35:48.518` is a 97 ms transient before that feed landed, and resolves; it is
    not part of this.)

    **The reason the check is wrong is the compat mode.** `MatrixRtcCall.videoFrames`' contract says a member who
    leaves does not come back under the same id, because the core mints a fresh one per join — and under MSC4143
    that is true. Under `ElementCallCompat::StateEvents` it is not: the member id is `userId:deviceId`, so the same
    browser on the same device rejoining is *the same member id*. "Have we already given this member the key" is
    therefore not a question about this join, and answering it from the previous join's bookkeeping is what loses
    the key.

    Sending the current key unconditionally on arrival would close this, and costs one to-device message against a
    call that is otherwise silently one-way.

    **Update, on the AAR of 2026-09-02.** The library ships a fix for this: under `StateEvents`, a member who
    rejoins under the same `userId:deviceId` is no longer credited with the key from their previous join. Not yet
    verified on device — the reproduction above needs a second client that leaves and rejoins within the key's
    freshness window, so the title stays un-struck until that run has been done and the far end's tile for us is
    not grey. Whether the fix also changes anything for item 25 is not known; that one is about a deferred rotation
    not firing, which this scenario no longer depends on but which is still worth its own warn line.

25. **A rotation deferred to `+9814ms` never fired**, and it was the safety net for item 24.

    Same log: the deferral at `12:35:43.751` set a deadline of roughly `12:35:53.565`. The call then ran until
    `12:36:05` — 21 s past it — with no rotation, no new key and no further distribution of any kind. A rotation
    there would have minted index 1, distributed it to the live membership and fixed the call on its own.

    Whether the timer is dropped, or fires and finds nothing to do, or is cancelled by the arrival at
    `12:35:47.932`, is not visible from the host side: the deferral is logged and the outcome never is. **A deferred
    rotation that does not happen should say so at warn** — as it stands the last word on the subject is a line that
    reads like a promise.

**Ruled out while diagnosing this**, recorded so nobody re-runs it: `Received key with unexpected length: 16
(expected 32)` (warn, `matrix_rtc_core::encryption`) fires on every key Element Call web sends — it is AES-128
where the core expects AES-256 — and appears identically in the two calls that worked. It is not this bug. It is
still a warn-level line on a completely normal interop path, which makes it noise in exactly the logs where this
kind of thing gets diagnosed.

## For `matrix-rust-sdk`

1. **~~No way to read a raw room state event~~ — resolved, and the shape asked for is the shape that
   landed.** `Room` gained `stateEvents(eventType)` and `subscribeToStateEvents(eventType,
   RoomStateEventsListener)`, returning `RoomStateEvent { eventType, stateKey, sender, contentJson,
   eventId, timestamp }` — the four fields `LegacyStateMemberEvent` takes, plus two. The listener is
   called with the current snapshot immediately and then on every change, with several changes in one
   sync coalesced, so no host needs the 30 s poll `matrix-rtc-livekit`'s native path uses.
   `MatrixRtcElementCallCompat.STATE_EVENTS` is two-way in `call/impl` as of this change.

   Three notes for the next person, all of which cost us time to establish and none of which are
   obvious from the signature:

   - **One subscription covers both spellings.** The string is resolved through ruma's
     `StateEventType`, which declares `m.call.member` as an alias of
     `org.matrix.msc3401.call.member`, so both collapse to one bucket. Subscribing to each separately
     feeds the same members twice, and since `setCurrentMembership` replaces, each call wipes the
     other's list. Pass the **unstable** spelling: it is ruma's canonical name, so it survives the
     alias being dropped, whereas the stable one would then resolve to a custom type over an empty
     bucket — silently the same failure this item was about.
   - **`required_state` was already right.** `(StateEventType::CallMember, "*")` is in
     `matrix-sdk-ui`'s hardcoded `DEFAULT_REQUIRED_STATE`, so sub-item 3 of the original ask needed
     nothing. It remains true that a genuinely custom type is unreadable without being added there,
     which is worth keeping in the API's own docs — it is the difference between "no such event" and
     "we never asked the server for it".
   - **No manual seeding, unlike sticky events.** `subscribe_to_state_events` reads the store at the
     top of its loop before it first blocks, so the listener already fires with the current state.
     Copying the `stickyEvents()` seeding call from `stickyEventsFlow` would just double the first
     emission.

   **For the library: an empty `setCurrentMembership` is a trap worth documenting or refusing.** Still
   open, and this change makes it more relevant rather than less — it is now the documented reason the
   host filters empty snapshots out of a source it *can* read. Room state is only ever replaced, never
   removed, so a departure in that generation is a present event with `{}` content on the same state
   key: the list keeps its length, and an empty list therefore cannot mean "everyone left". It can
   only mean "nobody ever published here" or "not synced yet", which is exactly the absence of
   knowledge the core reads as a positive assertion. Our first Android↔Web run in this mode fed one —
   the honest thing to build from a room with no sticky traffic at all — and it cost an afternoon. It
   reads as "this call is deserted, ourselves included", so
   the roster drops to zero, the core stops refreshing a session it believes we are not in, and ~20 s
   later the delayed state event fires and empties our membership while the call is still up. From
   the far end we join and vanish; the only give-away on our side is `cancelDelayedEvent` answering
   `404` at hangup, because the switch had already gone off. The fix is to feed *nothing*, which is
   the same distinction `SlotKnowledge::Unsupplied` already draws for slots — "I have nothing to say"
   is not "there is nobody here". Worth either a `MembershipKnowledge::Unsupplied` for symmetry, or a
   line in the docs saying an empty list is a positive assertion.
2. No RTC slot state and no `/rtc/transports` binding — see items 11 and 12 above.
3. Confirm whether `Client.getUrl` attaches the access token. If not, hosts have to build a second authenticated
   HTTP path purely for `/rtc/transports`.
4. `subscribeToToDeviceMessages` is client-wide while `receiveEncryptionKey` is room-scoped. Worth stating whether
   routing by a room id parsed out of the content is the intended pattern.
5. `Room.subscribeToStickyEvents` is per-room, and there is no client-wide equivalent. A host that wants to notice
   an incoming call anywhere has to subscribe across the room list, which is only affordable if the subscription can
   hang off a lightweight room handle rather than one that builds a live timeline. The core is alive for the whole
   Matrix session and `subscribeMembershipSnapshots` now gives us the notification we needed, but the core only
   learns about memberships in rooms we have already fed it.

   **Correction to what this entry used to claim.** It called itself "the single thing blocking incoming calls in
   the app". That was wrong, and worth recording rather than quietly editing: it described the path where the app
   notices a call *while running*, and treated that as the whole problem. The path that actually matters is a push,
   which arrives whether or not the app is running and needs no subscription at all — and that one is blocked
   somewhere else entirely, on the core never sending the notification event that generates the push (item 17).
   Both need solving, and item 17 is the one that decides whether a call can ring at all.
6. The delayed leave event the dead man's switch fires is sent in the clear. In an encrypted room the core discards
   a membership it cannot vouch for, so the departure never lands — `StickyEventMapper` carries a workaround that
   vouches for cleartext *departures* only, which should be removed once delayed events go out encrypted.
7. The sticky duration is `u64` in the RTC FFI but `u32` on `sendStickyRaw`, so the host clamps on the way down.
   Harmless in practice, but the two should agree.
8. `matrix_sdk_crypto: Received an unexpected encrypted to-device event` warns on every RTC media key, even though
   the message is then forwarded correctly.
9. **`Room.sendStickyRaw` returns nothing, so the event id the RTC FFI now asks for cannot be reported.** The RTC
   `sendStickyEvent` callback returns `String` and its docs call the id "never optional" — "matrix-rust-sdk: the
   `eventId` on the send response. Every Matrix send responds with one" — which is true of the response and not of
   this binding: `sendStickyRaw` and `sendRaw` both resolve to unit, and only `sendStateEventRaw` hands the id
   back. So the host has nothing truthful to return and answers with an empty string, and the core cannot relate
   an MSC4075 notification to a sticky membership — which is no longer hypothetical, now that the core sends those
   notifications itself and relates them to the membership event (RTC item 17). The value exists on the wire and `StickyEvent`
   already carries an `eventId` when the same event comes *back* through `stickyEvents()`; it is only the send that
   drops it. Returning the send response's `event_id` from `sendStickyRaw` would close it.

## Widget-driver stopgap

The library builds against the **released** Kotlin bindings (`org.matrix.rustcomponents:sdk-android`, the version
Element X `develop` pins). Those lack the entry points the native call stack needs, so
`call/matrix/…/temporary/widget/` drives the SDK's *widget driver* in-process (no web view) as the Matrix bridge
for exactly them. The widget machine already implements delayed events, a room-state feed and encrypted to-device
messaging for Element Call web; `WidgetMatrixBridge` speaks its JSON API through `WidgetDriver.send` /
`incomingMessages`. Everything else - `sendStateEventRaw`, the OpenID token, members, encryption state - stays on
the released SDK. Same design as Element X iOS (`ElementCallMatrix/Widget/`).

**The shape that makes it disposable.** The Matrix port (`ElementCallMatrixTransport` and `ElementCallMatrixRoom`
in `call/api/…/matrix/`) is the one place listing the gap; `MatrixRtcCommandSender`, `RoomStateFeeder` and
`SessionStateFeeder` depend on it and never learn about widgets. Every declaration in `temporary/widget/` is
annotated `@ElementCallTemporaryApi`, and a Konsist rule keeps the package inside `call/matrix`. Retiring the
stopgap is:

1. When the Kotlin bindings expose them, call the SDK directly from `SdkElementCallMatrixRoom` and
   `ElementCallSdkTransport` (`sendDelayedEvent`, `updateDelayedEvent`, `stateEvents`, `stickyEvents`,
   `sendStickyEvent`, `sendToDeviceMessage`, `toDeviceMessages`; the SDK-backed versions the spike once had are
   in Element X commit `8a2e944437`, the last one before the bridge landed, in `JoinedRustRoom.kt` and
   `RustMatrixClient.kt`).
2. Delete `call/matrix/…/temporary/widget/` (driver, bridge, capability grant, relay, registry), drop
   `onSessionEnded` from `RustMatrixRtcSession`, and lift the compat pin in `DefaultElementCallController` so
   `ElementCallOptions.elementCallCompat` is obeyed. The `RustWidgetDriver` recv-loop hardening the spike made in
   Element X is a genuine bug fix for the WebView call too and can go to Element X on its own.

**Missing from the released FFI** (each retires part of the bridge; all of them retire it):

- `Room.sendDelayedEvent` / `sendDelayedStateEvent` (returning the delay id) and `updateDelayedEvent(cancel|restart)`.
- A room-state feed: `Room.subscribeToStateEvents(eventType)` delivering the full list per change with `event_id`,
  `sender`, `state_key`, `origin_server_ts` and content.
- `Client.sendToDeviceMessage(eventType, messages, encrypt)` returning per-recipient failures.
- `Client.subscribeToToDeviceMessages(eventTypes)` delivering the **encryption info**: attested sender, sender device
  id and cross-signing status. The widget path delivers `{type, content, sender, encrypted}` only.
- `Room.sendStickyRaw` (MSC4354) for the sticky-event compat modes; until then calls are pinned to the state-event
  mode (`DefaultElementCallController`; `ElementCallOptions.elementCallCompat` is read but not obeyed). `Room.sendRaw` returning the event id would
  also let MSC4075 notifications relate to their membership.

**Trust relaxation while the stopgap is in place.** The core drops a media key whose sender is not cross-signed
and the mapper drops one without a sender device. Through the widget driver neither is knowable, so the bridge
takes the device from the key message content (`device_id`, or `member.device_id`) and treats an encrypted
message as cross-signed. The driver already drops cleartext in an encrypted room and attests the sender of an
encrypted message, so this is the trust level embedded Element Call web has today. It lives only in
`WidgetMatrixBridge.deliverToDevice`; `EncryptionKeyMapper` and the core keep their strict checks.

**Sender device of Element Call keys.** Element Call web's `io.element.call.encryption_keys` messages do not
always carry a `device_id`, and the core refuses a key whose sender device it cannot match against the member
event. The bridge infers the device from the sender's live `org.matrix.msc3401.call.member` membership
(`memberships[].device_id`, else the `_{user}_{device}_m.call` state key) when they have exactly one; a
multi-device sender is left unresolved and the key dropped. A rust-rtc option to resolve the device from the
membership itself when the key names none would retire this.

**Wire details worth knowing.** The machine's request enum is adjacently tagged (`action` tag, `data` content):
a message whose `data` precedes `action` is buffered by serde and its raw JSON fields then fail with "invalid
type: newtype struct". The bridge builds its envelopes with `action` before `data`. Live state arrives through
two doors - the sync state block as `update_state`, timeline-borne state as a `toWidget` `send_event` with a
`state_key` - and the same change can come through both, so the bridge dedupes on `event_id`. `send_to_device`
omits `failures` entirely when everyone was served. The machine keeps at most 15 unanswered requests of its own
with a 10 s timeout, so every `toWidget` request is answered inline. `WidgetDriver.run()` returns only once its
handle is dropped and the machine next emits; on Android cancelling the bridge's scope drops the Rust future,
and a `supported_api_versions` poke makes the pending `recv()` return so the handle is released.

**Other limits.** To-device messages are only received while a driver runs for some room, so a key rotated by a
peer between our calls is lost; peers re-send on join. Only state the sync asked for reaches the driver's initial
`update_state`; the call member type is in sliding sync's default `required_state`, so it is there.

## Host-side log lines worth keeping

Each of these was added because a failure was indistinguishable from a different failure without it. Most now have
a counterpart inside the core, so they are kept as the *host's* half of the same story — the point of each pair is
that the two can be read against each other on one device.

| Log line | What it disambiguates |
| :--- | :--- |
| `key index N imported for <member>` (from `KeyImported`) | key never arrived / arrived and was refused / installed at an unused index |
| `key index N for <member> discarded, <reason>` (from `KeyDiscarded`) | which of those it was, and whether the user can act on it |
| `frame encryption <state> for <member> (was <previous>, <diagnostic>)` | a transient rotation delay from a stuck failure, and an empty key ring from a stale index |
| `sendToDeviceMessage(<type> index N to [<recipients>])` | what we told the far end to decrypt with, against what our own cryptor encrypts with |
| `feeding N sticky event(s) for <room>: [<member>=<membership>]` | a snapshot of joins from one carrying a departure the core ignored |
| `N member(s) in <room>/<slot>` | the core's membership projection from the media roster, which can legitimately differ |
| `rx <member> - N pkts, N lost, N% invented, jitter Ns` | a starved stream from a silent one |
| `audio <in\|out> - N frames, level N, realtime Nx` | capture or playback being alive from it being merely negotiated, and a loop keeping up with real time from one starving |
| `audio in <member> - N new AudioTrack under-runs (N total)` | a crackle we caused from one the network caused |
| `<member> publishes no microphone stream ... streams: <kinds>` | a member who muted themselves from one whose audio never reached us |

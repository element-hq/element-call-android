# matrix-rust-rtc — integration guide

How to put `matrix-rust-rtc` into a Matrix client, written from the integration in this repository. The
`element-call-android` library is the worked example throughout, and every file reference points at code you can
read: the wrapper lives in `call/impl/…/rtc` (the only package that imports `org.matrix.rtc`), the call —
capture, playback, controller — in the rest of `call/impl`, and the UI in `call/ui`.

The companion document is [`FEEDBACK.md`](FEEDBACK.md), which records what is still missing or wrong in the library.
This one records what you have to *do*. Where a step exists only to work around a library gap, it says so and points
at the item.

The shape of the thing, in one sentence: **the library owns the transport and the Matrix RTC state machine; you own
the network sends, the room state feed, and every microphone, camera and pixel.** It is a frame-in / frame-out core,
not a call SDK. It will not touch your homeserver, open a capture device, or draw anything.

---

## Contents

1. [What you are linking against](#1-what-you-are-linking-against)
2. [Loading the native library](#2-loading-the-native-library)
3. [The backend: what the core asks of your client](#3-the-backend-what-the-core-asks-of-your-client)
4. [Delivering a room's subjects](#4-delivering-a-rooms-subjects)
5. [Opening a room](#5-opening-a-room)
6. [Joining a call](#6-joining-a-call)
7. [Connecting media](#7-connecting-media)
8. [Publishing audio](#8-publishing-audio)
9. [Publishing video: camera](#9-publishing-video-camera)
10. [Publishing video: screen share](#10-publishing-video-screen-share)
11. [Receiving audio](#11-receiving-audio)
12. [Receiving video](#12-receiving-video)
13. [Constraints, simulcast and dynacast](#13-constraints-simulcast-and-dynacast)
14. [Leaving, and the order of teardown](#14-leaving-and-the-order-of-teardown)
15. [Threading model](#15-threading-model)
16. [Platform obligations (Android)](#16-platform-obligations-android)
17. [Checklist](#17-checklist)
18. [Failure modes worth recognising](#18-failure-modes-worth-recognising)

---

## 1. What you are linking against

The AAR (`matrixrtc-release.aar`) contains four things, and you will end up depending on all four:

| Piece | What it is |
| :--- | :--- |
| `libmatrix_rtc_ffi.so` | the Rust core, LiveKit client and a statically linked libwebrtc |
| `org.matrix.rtc` | the generated Kotlin bindings (`uniffi.matrix_rtc_ffi` before v0.2.0-rc.1) |
| `org.matrix.rtc.MatrixRtc` | the loader that runs `JNI_OnLoad` — see §2 |
| `libs/libwebrtc.jar` | libwebrtc's Java classes: camera and screen capture, I420 conversion, GL renderer |

The Maven coordinate is `io.element.android:matrix-rtc-android`, on GitHub Packages (which needs a token even to
read) and not yet on Maven Central. So the dependency is declared with an `aar` artifact selector and the AAR is
served by an Ivy repository - over the local file here, over the core's GitHub release in a host - with
artifact-only metadata; JNA has to be declared by hand because there is no POM to carry it:

```kotlin
// settings.gradle.kts
ivy {
    url = settingsDir.resolve("rtc/local").toURI()
    patternLayout { artifact("matrixrtc-release.[ext]") }
    metadataSources { artifact() }
    content { includeModule("io.element.android", "matrix-rtc-android") }
}
```

```kotlin
// call/impl/build.gradle.kts
implementation(variantOf(libs.matrix.rtc.android) { artifactType("aar") })
// uniffi bindings runtime
implementation(variantOf(libs.jna) { artifactType("aar") })
```

**Confine the FFI to one package.** Nothing outside `call/impl/…/rtc` imports `org.matrix.rtc`. The
`call/api` module exposes only your own types — `MatrixRtcCall`, `MatrixRtcVideoFrame`, `MatrixRtcStreamKind`. This is
worth the boilerplate: the FFI surface changes between library versions, and the UI should not.

**`libs/libwebrtc.jar` is load-bearing for video** and is not formally part of the library's contract
(`FEEDBACK.md` item 2). Both halves of camera support come from it — `Camera2Enumerator` / `Camera2Capturer` /
`SurfaceTextureHelper` to capture, `JavaI420Buffer` / `SurfaceViewRenderer` to display. What makes it worth
depending on is `VideoFrame.Buffer.toI420()`: it converts whatever the device produced into exactly the three-plane
layout `captureVideo` takes, in native code you would otherwise write by hand against `YUV_420_888` pixel strides.
Pin the assumption with a test so a repackaged AAR fails your build rather than a device:

```kotlin
// MatrixRtcAarClasspathTest.kt — Class.forName on each class the capture and render paths need
```

---

## 2. Loading the native library

**The AAR's own loader must run first.** The uniffi bindings open the library with JNA, which is a plain `dlopen`
and never triggers `JNI_OnLoad` — so going straight to uniffi leaves libwebrtc without a `JavaVM*` and the first
media call dies on a null dereference inside `RtcRuntime()`.

```kotlin
// call/impl/…/rtc/MatrixRtcFfi.kt
MatrixRtc.initialize()       // runs JNI_OnLoad: hands libwebrtc its JavaVM* and class loader
uniffiEnsureInitialized()
```

Both are idempotent; guard them behind one synchronised `ensureInitialized()` and call it before *any* FFI use.
Let a failure propagate rather than swallowing it — an app that carries on without the native library aborts the
process at first media instead of failing a call.

Configure logging in the same place. The core installs a **process-wide** tracing subscriber on first use and will
not take a second one, so a configuration change only lands on the next process start. Prefer the native logcat sink
(`RtcLogging.initLogcat`) over a Kotlin sink: it filters in Rust and never crosses the FFI per record, which matters
because this stack logs from real-time media threads.

A default filter that leaves the useful targets readable without the per-frame flood:

```
matrix_rtc_media=debug,matrix_rtc_livekit=debug,livekit=info,libwebrtc=warn,webrtc_sys=warn
```

`livekit` sits at info deliberately — it is where SFU connection and ICE progress is reported, and a media session
that never connects says nothing at warn.

---

## 3. The backend: what the core asks of your client

The core decides *what* to put on the Matrix wire and *what* it needs to read; your `MatrixBackend` is *how*. You
implement it once, over your own client, and hand it to the core:

```kotlin
val rtcClient = RtcClient(DefaultMatrixBackend(transport, discovery, roomProvider = { openRooms[it] }, sessionScope, io))
```

Reference implementation: `call/impl/…/rtc/DefaultMatrixBackend.kt`. The core depends on no Matrix SDK, so a host
keeps exactly one copy of its own.

| Method | Maps to | Returns |
| :--- | :--- | :--- |
| `sendStickyEvent` | `room.sendStickyEvent(type, json, durationMs)` (MSC4354) | the event id |
| `sendDelayedEvent` | `room.sendDelayedEvent(type, stateKey?, json, delayMs)` (MSC4140); a state key makes it a state event | the **delay id** |
| `restartDelayedEvent` / `cancelDelayedEvent` | `room.updateDelayedEvent(delayId, RESTART / CANCEL)` | — |
| `sendStateEvent` | `room.sendRawStateEvent(type, stateKey, json)` | the event id |
| `sendRoomEvent` | a message-like send, encrypted in an encrypted room | the event id |
| `redactEvent` | `room.redact(eventId, reason)` | — |
| `sendToDeviceMessage` | `client.sendToDeviceMessage(type, messages, encrypt = true)` | one delivery verdict per recipient |
| `subscribeRoom` / `subscribeToDevice` | your client's listeners, delivering into the sink (§4) | a `BackendSubscription` |
| `relations` | `GET /relations/{eventId}/{relType}/{eventType}`, decrypted | the related events |
| `openidToken` | `client.getOpenIdToken()` | the token |
| `rtcTransports` | MSC4143 transport discovery, as the raw `rtc_transports` JSON array | `[]` when there is none |

**On the released SDK, most rows go through the widget-driver stopgap.** The Kotlin bindings do not yet expose
delayed events, sticky events, raw room events or to-device sends, so `call/matrix` drives the SDK's widget machine
in-process behind the `ElementCallMatrixRoom` port; `FEEDBACK.md`, "Widget-driver stopgap", has what retires it.

Things that are not obvious and cost real time:

**Pass everything through verbatim.** The core emits the event types (unstable spellings included) and formats the
JSON, and it parses what comes back. Do not normalise types, content or transports. Watch out for SDK send paths
that disagree: in matrix-rust-sdk, `sendStickyRaw` passes the type through unchanged while `sendDelayedEvent`
normalises it via ruma's typed enum.

**The lifetime is the core's to choose.** `sendStickyEvent` is handed a duration; use it. If your SDK takes a
narrower integer type, **clamp rather than truncate**.

**Report failures, do not classify them.** Throw `FfiBackendException.Failed(errcode, status, reason)` with the
Matrix `errcode` and HTTP status when your client has them. The core decides what a failure means — a delayed-event
refusal (`M_UNRECOGNIZED`, or matrix.org's `M_FORBIDDEN`) retires the dead man's switch for the session, anything
else is retried. Keep `CancellationException` a cancellation.

**Get `cancel` and `restart` the right way round.** Swapping them retires the membership the switch protects. Pin
each to its action with a test.

**`sendToDeviceMessage` must be encrypted, and must report per recipient.** RTC media keys never go out in the
clear. A recipient reported as delivered is never re-sent to; **reporting a failure as a success is how a member
ends up permanently keyless**.

---

## 4. Delivering a room's subjects

The core has no network of its own. `subscribeRoom(roomId, subjects, sink)` asks for one room's subjects; it is
**synchronous**: start your listeners and return. Deliver from your own coroutines until `cancel()`, which must be
idempotent. Each subject on its own, so one that never emits cannot hold up the others, and a failing one must end
only itself — an exception escaping into your session scope kills the session.

| Subject | Sink call | Notes |
| :--- | :--- | :--- |
| sticky events | `onStickyEvents(events)` | the complete live set |
| each `stateEventTypes` entry | `onStateEvents(type, events)` | under its own type: ruma's alias collapse (`m.call.member` / `org.matrix.msc3401.call.member`) means filtering a shared bucket by type |
| joined members | `onJoinedMembers(userIds)` | **must include your own user** |
| encryption | `onEncryption(encrypted)` | only once known; never guess `false` |
| `timelineEventTypes` | `onTimelineEvents(events)` | each batch as it arrives: reactions, raised hands |
| redactions | `onRedaction(eventId)` | a lowered hand |

**`RtcClient.room()` resolves only once every set-shaped subject has delivered once.** So:

- **Deliver the current set first, then the whole set on every change.**
- **An empty set means none, and it must still be delivered.** Holding back an empty set ("it might mean not
  synced") is the one mistake that hangs: `room()` never returns. If your source cannot tell "none" from "not
  loaded", fix that at the source: the widget stopgap waits for the machine's first `update_state`, which carries
  every granted type, an empty one included.
- **A joined-member set without your own user is ignored**, and the room never seeds.

The core logs which delivery it is still waiting on; `RustMatrixRtcClient` also turns a seeding that never finishes
into a failed join after 30 s rather than an endless "Joining".

Events cross as `FfiEventIn`: id, sender, type, state key, `origin_server_ts`, the whole decrypted `content` as JSON,
and `FfiEventEncryption`. Report only what your client said: a cross-signing status it cannot give stays `null`.

**To-device**: `subscribeToDevice(types, sink)` the same way. **Only the encryption info is authenticated** — pass
the attested sender and device from it, never what the content claims.

---

## 5. Opening a room

One `RtcClient` per Matrix session. Creating it does no I/O and the core holds nothing about a room until you open
one, so there is nothing to start at login: the core subscribes to to-device itself, and no key is sent to you
before your membership is visible.

```kotlin
val rtcRoom = rtcClient.room(roomId, FfiRoomOptions(format = FfiMembershipFormat.ROOM_STATE))
```

Make the room your client side needs (here, the widget bridge) routable **before** calling `room()`, because the
core subscribes from inside it. `shutdown()` leaves every call of the room and ends its subscriptions; close your
own room only after it, because that leave goes through it. (`shutdown`, not `close`: uniffi's `close()` frees the
object.)

### Membership format

A **three-way choice made once per room**, not a set of flags — it fixes what the core subscribes to, the wire
format, the member id, the SFU participant identity and the token endpoint together:

- `CURRENT` — MSC4143 + MSC4354 as they stand.
- `STICKY2025` — Element Call as of 2025: sticky events with legacy fields riding alongside.
- `ROOM_STATE` — the pre-MSC4354 generation: `org.matrix.msc3401.call.member` room state, delayed *state* events as
  the dead man's switch. Visible to that generation and to nobody else.

`CURRENT` and `STICKY2025` joins fail in a room with no open `m.rtc.slot`, and this library never opens one, so it
pins `ROOM_STATE` (`DefaultElementCallController`).

---

## 6. Joining a call

```kotlin
val rtcCall = rtcRoom.joinCall(
    FfiJoinSessionParams(
        applicationSlotId = null,                 // the room-wide call, m.call#room
        transport = FfiJoinTransport.Advertised,  // the core picks from rtcTransports()
        keepAliveTimeoutMs = 20_000uL,
        stickyDurationMs = null,                  // defer to the core
        encryptionConfig = null,                  // follow the slot
        notify = ...,                             // null joins quietly
    )
)
```

**No transport to discover or pass.** `Advertised` makes the core pick the first LiveKit transport from your
`rtcTransports()`, and fail the join when there is none. Answer that method with the MSC4143 endpoint and the
`.well-known` fallback (`rtc_foci`): matrix.org answers 404 on the endpoint. See `RtcTransportDiscovery.kt`.

**No slot id to compose.** The join takes the MSC4143 application slot id; `null` is `m.call#room`, the slot
Element Call opens for a room-wide call.

**The core mints the member id** (`rtcCall.memberId()`). It is what every membership, media roster entry and
frame-encryption report is keyed by. A device that rejoined without leaving appears under a new id.

**No keep-alive of your own.** The call refreshes its membership and rotates keys on the core's own runtime until it
leaves or is dropped. A dropped call sends no leave: its membership expires through the delayed leave.

A join refused because the room has no open slot fails with `MatrixRtcFfiException.SlotClosed`.

### Ringing the other side (MSC4075)

Pass a `notify` config **only when the user is starting a call** — joining one someone else started happens
quietly. The core suppresses the notification anyway once anybody else is in the call.

Our rule: a DM rings (`RING`), any other room gets a silent `NOTIFICATION`. Default to the silent one if the room
cannot be read. Carry the MSC4196 `m.call.intent` (`audio` / `video`).

### Membership readback

`rtcCall.subscribeMembershipSnapshots()` → `next()` **suspends** until the roster changes; the first call returns
the current roster, and `null` means the room was shut down — only then stop reading. Cancelling the reading
coroutine cancels the pending `next()`. The roster's size is the member count.

---

## 7. Connecting media

Membership and media are separate steps. You are in the call as soon as you have joined; media is attached after:

```kotlin
val mediaSession = connectMediaSession(rtcCall, MediaSessionConfig(stability = null))
```

The focus comes from the join, and the account and the SFU's OpenID token from your backend (`openidToken`).

Then, in this order:

1. **Start the event pump** (`mediaSession.nextEvent()` in a loop) *before* announcing the call as connected. The
   event flow has no replay, and the event that matters most — `Ended` — arrives exactly when a call is short-lived
   enough for the gap to catch it. In Compose, launch your collectors `UNDISPATCHED` so they are subscribed by the
   time the launcher returns rather than merely queued.
2. **Read `participants()` once**, for your own row before the core publishes your local state. It is the whole
   call every time — the cost the tile roster's detail window exists to avoid — so never re-read it per event. Who
   to draw and who to hear both follow `nextRoster()` (§11).
3. Publish your own microphone (§8).

`participants()` is the transport's roster and is a different thing from the core's membership projection. Both are
legitimate and they can differ; log which layer a surprising row came from.

**What to draw is the tile roster, not `participants()`.** `nextRoster()` / `roster()` give every remote tile — one per
camera, one more per screen share — ranked and damped by the core; `nextLocalState()` / `localState()` give your own
tile, which is never in the ranked list, and the sharing flag. Both long-poll with the latest value winning, so pump
each from one coroutine, seeded from its pull. The semantics are the reviewed contract,
`element-call-feature-hq/plans/002.rust_rtc_media_roster_model/contract.md`; three things bite silently:

- A tile's identity is `(memberId, kind)`. Join `detail` to `order` by it, never by index: `setDetailWindow` makes
  `detail` a subsequence.
- Render `order` as given. It already carries the hysteresis; a re-sort, or a spotlight damper of your own, fights it.
- `localState()` is null until your membership reaches the core's roster, later than `participants()` lists you.
  Build your own tile from your `participants()` row until then, or every join opens on an empty stage.

Two Rust-side deployment notes: Android honours only the AAR's bundled root certificates, so a deployment fronted by
an enterprise CA fails the TLS handshake with no way to install trust (`FEEDBACK.md` item 3); and the AAR ships no
`x86` ABI, so a 32-bit emulator build installs without the native library and fails at first use rather than at
build time (item 1).

---

## 8. Publishing audio

Publish a track, then push PCM into it. The library does not open your microphone.

```kotlin
val track = mediaSession.publish(
    FfiPublishOptions(
        kind = FfiStreamKind.MICROPHONE,
        audio = FfiAudioSourceConfig(sampleRate = 48_000u, numChannels = 1u),
        video = null, simulcast = false,
    )
)
```

**48 kHz mono, 10 ms frames** — that is what the RTC stack works in internally, so nothing has to resample, and 10 ms
is the usual WebRTC tick. One frame is `480 samples × 2 bytes = 960 bytes` of little-endian int16, which is exactly
what `AudioRecord` produces and what `FfiAudioFrame` takes:

```kotlin
val frame = ByteArray(960)
val samples = ByteBuffer.wrap(frame).order(LITTLE_ENDIAN).asShortBuffer()  // same memory, no conversion
// ...
track.captureAudio(FfiAudioFrame(frame, 48_000u, 1u, 480u))
```

Use `MediaRecorder.AudioSource.VOICE_COMMUNICATION` for the platform's echo cancellation and noise suppression, and
give the buffer a few frames of slack so a slow FFI hand-off does not drop samples.

**Muting: stop capturing *and* tell the transport.** Stopping capture is what saves the bandwidth, but on its own it
is indistinguishable to a peer from a client that has wedged and stopped pushing frames. Call
`mediaSession.setLocalMuted(MICROPHONE, muted)` as well, so peers — and your own roster entry — see a deliberate
mute. Keep the `AudioRecord` open while muted so unmuting is instant. Replay the mute state onto a freshly published
track: muting before publishing is ordinary, and the transport only learns about it once there is a track.

**Meter before the mute check.** Knowing the microphone is alive while you are muted is exactly the question a
silent call raises.

**The release race is real and it killed the process.** `Job.cancel()` does not wait, and `AudioRecord.read` in
blocking mode is not a suspension point — so releasing the record while a read is in flight frees native memory
underneath it. Make read and release mutually exclusive under one lock, with a sentinel return value distinct from
every `AudioRecord` error code so "we stopped" is never logged as "the device failed". The same shape applies to
playback (§11), where it was found first, on a three-way call.

A **test tone** is worth building in. A fixed 440 Hz sine at a known level takes the capture device out of the
picture: if the far end still hears nothing, the problem is downstream of capture. Emulators in particular hand out
silence unless the host microphone is explicitly shared.

---

## 9. Publishing video: camera

```kotlin
val track = mediaSession.publish(
    FfiPublishOptions(
        kind = FfiStreamKind.CAMERA,
        audio = null,
        video = FfiVideoSourceConfig(width = 640u, height = 480u),
        simulcast = true,   // NOT optional — see below
    )
)
```

> ### `simulcast = true` is not a quality setting
>
> LiveKit's dynacast pauses any encoding the SFU reports nobody subscribed to, and a peer rendering you in a small
> tile asks for the **low** layer. With one full-resolution encoding, the only layer you produce is the only one
> never asked for — so it is paused and **no video leaves the device**, while capture, your self view and every log
> line on your side stay perfectly healthy. The far end shows grey and never creates an inbound stream at all.
>
> The evidence, from the core's own log:
> `dynacast: SFU quality update: subscribed_codecs="vp8:[Low=true, Medium=false, High=false]"`
>
> There is no way to turn dynacast off from the FFI — `FfiPublishOptions` offers this flag and nothing else — so
> publishing several layers is the only defence. `FEEDBACK.md` item 13.

### Capture

Use libwebrtc's `Camera2Enumerator` / `createCapturer` with a `SurfaceTextureHelper`. The helper needs a GL context
because `toI420()` on a texture frame is a GPU operation — the camera hands you an OpenGL texture and this is what
reads it back as pixels. Prefer the front camera, fall back to whatever exists rather than refusing.

The capturer runs its own camera and GL threads and calls you back on them. `captureVideo` does not suspend, so
**publish straight from the capture thread** — routing 30 frames a second through your single FFI dispatcher queues
them behind membership and stats calls for no benefit.

### Repacking planes: the part that is wrong silently

A plane has a **stride** — the distance from the start of one row to the start of the next — which may exceed the
row width because a capturer aligns rows for its own convenience. Copying `stride × rows` in one go copies padding
as picture, which reads as a frame shearing progressively further sideways down the image. Copy row by row:

```kotlin
// I420Buffers.packPlane
val required = if (rowCount == 0) 0 else stride * (rowCount - 1) + rowWidth  // last row needs only its width
val packed = ByteArray(rowWidth * rowCount)
val reader = source.duplicate()                                             // leave the caller's position alone
for (row in 0 until rowCount) { reader.position(row * stride); reader.get(packed, row * rowWidth, rowWidth) }
```

Chroma planes are half resolution **rounded up**: a 641-pixel-wide frame has 321 chroma samples per row, not 320.
Rounding down loses the last column and shifts every row after the first — shear again, rather than a missing
column. Require the source buffer to be large enough and throw loudly if not; the alternative is a short read
filling the bottom of the picture with whatever the buffer happened to contain.

**Pass rotation through, do not apply it.** The capture device reports how far the frame must be rotated to display
upright; the far end turns it upright, which is a rotation you would otherwise do in software on every frame.

Read dimensions off each frame rather than assuming what you requested — the capturer picks the closest format the
device actually supports.

### Enable and disable

Disabling the camera should **release the device** — the indicator light going out is the point; a camera that stays
open while the UI says it is off costs trust. This is the opposite of the microphone, which stays open and stops
handing frames over.

But keep the **track published** and mute it at the transport (`setLocalMuted(CAMERA, muted)`). Republishing
renegotiates, and a peer would see the track disappear and come back rather than simply mute. Order matters:

- enabling — start capture, then unmute the transport
- disabling — **mute the transport first**, then stop capture. Capture stops within a frame or two either way, but a
  peer told afterwards has already been shown a frozen picture.

`switchCamera` is asynchronous by nature — one camera has to close and another open — so surface the result from the
callback, not from the return. Read `isFrontFacing` back rather than assuming: a device with no front camera starts
on the back one, and a self view must mirror the front camera and must not mirror the back one.

`stopCapture()` blocks until the camera thread has really stopped, which is what makes it safe to dispose the
`SurfaceTextureHelper` afterwards. Do this from your teardown path explicitly, not from a finaliser — a call torn
down from recents otherwise holds the camera, light on, until the process dies.

---

## 10. Publishing video: screen share

`ScreenCapturerAndroid` wraps `MediaProjection` in the same `VideoCapturer` interface, so from `onFrameCaptured`
onwards this is the camera path exactly — share the publisher between them. Three differences, all platform rules:

1. **It needs a token from an Activity.** `MediaProjectionManager.createScreenCaptureIntent()` must be launched for
   a result, and the returned `Intent` is what capture takes. It is **single-use**: stopping and sharing again means
   asking again. Accept it as a parameter rather than trying to raise the dialog from inside your call layer.
2. **The `mediaProjection` foreground service must already be running** when the projection is claimed, which
   happens inside `initialize()`. From Android 14, upgrading the service afterwards or in parallel is a
   `SecurityException` from the platform, not a warning. This ordering is the reason screen sharing belongs in a
   controller that owns both the service and the call.
3. **The user can stop it from outside your app**, from the cast notification, and nothing in the capturer interface
   reports that. `MediaProjection.Callback.onStop` does — wire it, or your button goes on saying "stop sharing" for
   a share that already stopped.

Publish with `simulcast = true` for the same dynacast reason. It is arguably wrong for a screen, where one sharp
layer beats three soft ones and text is the whole point — but a single layer is the one dynacast pauses, and
shipping something that silently sends nothing is worse than shipping something soft.

Cap the resolution (we use a 1280 px long edge) and halve the frame rate (15 fps). A screen is mostly still, and
every pixel is repacked on the way out.

**Stop a share by un-publishing it, not by muting it** — the opposite of the camera. `setLocalMuted(SCREEN_SHARE,
true)` leaves the publication up, and every client that draws a tile for a screen-share track that exists goes on
drawing an empty grey one; Element Call web does exactly that. `unpublish(SCREEN_SHARE)` removes it at the SFU, so
peers drop the stream and the tile with it. The camera wants the other behaviour and for a good reason — a peer
should be able to tell a deliberate camera-off from a wedged sender — but **a screen has no "off"**: it is being
shared or it is not.

Two consequences to build around:

- **The `FfiLocalTrack` from `publish` is dead after `unpublish`** — `captureVideo`/`captureAudio` on it fail with a
  transport error. Drop the handle and publish fresh for the next share; there is no track to reuse, so unlike the
  camera there is no unmute path to take.
- **Dropping or `close()`-ing the `FfiLocalTrack` never retracts a publication.** Only `unpublish` does. Do not
  reach for `close()` expecting it to — we did, and the receiver's tile stayed.

Stopping capture before un-publishing is safe either way (a capture thread mid-`captureVideo` during an unpublish is
explicitly safe), but do it in that order regardless: `stopCapture()` blocks until its thread is done and costs
nothing.

Stop the projection explicitly on teardown too — see below.

Stop the projection explicitly on teardown — an abandoned `MediaProjection` leaves the system's screen-recording
indicator up for a call that has ended.

---

## 11. Receiving audio

The core decodes; you play. One `AudioTrack` per remote member is simpler than mixing yourself and is fine at the
handful of participants a small call has.

Open a stream per member and pump it:

```kotlin
val stream = mediaSession.audioStream(memberId, FfiStreamKind.MICROPHONE)
while (isActive) {
    val frame = stream.next() ?: break
    track.write(frame.data, 0, frame.data.size, AudioTrack.WRITE_BLOCKING)   // already LE PCM16
}
```

Build the track with `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH`: that routes to the earpiece and engages
the platform's echo cancellation against your capture.

**Run that loop on a thread of its own at `THREAD_PRIORITY_URGENT_AUDIO`, and do not build the track at
`getMinBufferSize`.** Both halves of that sentence cost us an audible crackle in a five-party call. `getMinBufferSize`
is the smallest size the platform will *accept*, not one that leaves any room, so the track has to be refilled before
it drains — floor it at a few frames, the way you already floor the capture buffer. And on an ordinary pool thread the
loop loses to video decode and GC and misses that refill anyway; see §15 for the measurements. Nothing in the frame
counter or in `receiveStats` will tell you this is happening, so also read `AudioTrack.getUnderrunCount()`, which is
the one counter that names it directly.

Allocate nothing per frame here. `frame.data` is already a fresh array on every call at 100 frames a second per
member; wrapping it to meter it doubles that, and this is the one path where a GC pause is audible.

**Playback follows the tile roster, not the event stream.** Every remote person tile in `nextRoster().order` is a
candidate: on each roster, open `audioStream(memberId, MICROPHONE)` for candidates you have no player for — it
returns `null` at once for a member with no microphone track, so the retries cost nothing and a microphone that
appears later is picked up on the roster it changes — and stop the player of anyone gone from the order
(`RustMatrixRtcCall.followPlayback`). `StreamStarted` and `StreamStopped` are still emitted, but a consumer that lags
the event stream by more than its buffer loses events silently, and a lost `StreamStarted` used to leave a member
silent for the rest of the call. The roster is a latest-value push and cannot be lagged.

Stop playback on `StreamStopped`, `ParticipantLeft` and `Ended`, and drop that member's cached video flows at the
same time — a member who left will not come back under that id.

Same release race as capture (§8): make write and release mutually exclusive. This is the one that was found by a
crash — `IllegalStateException: Unable to retrieve AudioTrack pointer for write()` on an unsupervised coroutine
takes the whole app down.

### Telling a starved stream from a silent one

Poll about once a second (RTCP reports arrive at roughly that rate, so faster only repeats values), and poll **the
streams you draw, in one call**: `mediaSession.receiveStatsFor(streams)` takes a list of `FfiStreamRef(memberId, kind)`
and answers every entry in one round trip, in request order, `stats == null` where a single `receiveStats` would
return null ("no report yet", which is not zero). This library asks for each composed tile's own stream plus its
member's microphone — the set the layout declares through `setComposedTiles` — so a call of two hundred costs one
call a second for the twenty tiles on screen, not two per member (`RustMatrixRtcCall.pollReceiveStats`).

Read the counters together, because each alone lies:

- A **level meter** on decoded PCM proves audio is flowing — but a jitter buffer with nothing to play emits silence
  rather than nothing, so a frame counter rising at exactly real time only proves the stream is *open*.
- `concealedSamples / totalSamplesReceived` resolves it: concealment rising in step with samples received means the
  silence you are playing is fabricated.
- Once a side goes quiet, its packet counter slows to ~4–6/s while concealment stays at zero. That is DTX, not a
  fault, and it looks alarming next to a `level 0.00` line.

Ask for **audio and video stats separately** — `receiveStats` is keyed by stream kind, and the microphone's report
has no frame counters at all, so reading "0 decoded" off it says nothing. Frames flat while packets climb is a
decoder stuck on what arrived; both climbing with nothing drawn puts the loss between the core and your renderer.

---

## 12. Receiving video

This is where the ownership rules are, and where three shipped bugs came from. Read `FEEDBACK.md` item 14 alongside
this section.

### The stream contract

`videoStream(memberId, kind)` opens a decode; `next()` blocks; `close()` stops it. **Opening the stream is what
makes the core decode at all**, so a stream nobody draws costs nothing — that property is worth preserving all the
way up to your UI.

Wrap it in a cold flow so collecting opens and cancelling closes, but note the consequences:

- **One stream per member *and* kind.** A member sharing their screen while their camera is on has two independent
  streams that happen to share a member id. Keyed by member alone, the second one asked for silently gets the
  first's frames.
- **Two collectors on one member is a crash.** Two `videoStream` handles on the same track, with the second closing
  under the first, aborted the core inside `VideoSinkWrapper::on_frame`. Cache the flow object per key (a cold flow
  holds nothing until collected) and fan out yourself — §"Fanning out" below.
- **Nothing emits for a stream that is not being published**, including yourself with the camera off. A member who
  starts publishing later needs a *fresh* collection, so drive subscription from `participants()` and
  `StreamStarted`.
- `next()` blocks the calling thread → `flowOn(io)`.

### Frames are resources, not values

A decoded frame's planes are **the core's own memory**. Wrap them where they lie rather than copying: `planePtr` is
valid for as long as any reference to the `VideoFrameRef` is alive, stable across calls, readable from any thread
unsynchronised, and releasable from any thread. The single rule is ordering — every read happens before the release,
and the release happens exactly once.

```kotlin
// VideoFrameMapper.mapZeroCopy — JNA's Pointer.getByteBuffer is the only way to wrap an address as a direct buffer
dataY = Pointer(planePtr(Y).toLong()).getByteBuffer(0, planeLen(Y).toLong()),
strideY = stride(Y).toInt(),          // the source's own stride, NOT normalised to width
onRelease = { close() },              // takes ownership of the ref — the caller must not close it
```

This was measured before and after: copying twice per plane (uniffi marshals to a `ByteArray`, then a direct
`ByteBuffer` for GL) put the frame path at **53% of process CPU with 29% more in GC**, against 2% for the hardware
codec. Zero-copy dropped GC from 20% of the process to 6% *while decoding three times the pixels*.

The cost is ownership, and "release when you are done" reads as single ownership when a real UI has several holders
at once. **Reference-count the frame**, starting at one:

```kotlin
fun retain(): Boolean          // CAS loop; returns false if it already hit zero — a use-after-free averted
fun release()                  // frees on the last one; extra calls past zero ignored, never double-free
```

Do not make it a `data class`: `ByteBuffer.equals` compares remaining content, so generated `equals`/`hashCode`
would walk a megabyte per comparison on frames arriving thirty times a second.

### Conflating without leaking

A renderer that has fallen behind must not become backpressure on the decoder, so drop old frames — but a dropped
frame is never collected, so nothing downstream can release it, and with owned memory every drop leaks most of a
megabyte. Dropping is the *normal* case under load, so the naive version leaks hardest under exactly the conditions
that cause it.

`buffer(1, DROP_OLDEST)` has no undelivered-element hook. `Channel` does, and that is the only reason to write it
out by hand:

```kotlin
Channel<MatrixRtcVideoFrame>(capacity = 1, onBufferOverflow = DROP_OLDEST, onUndeliveredElement = { it.release() })
```

**Operator order is load-bearing.** Conflation releases each frame as soon as `emit` returns, so nothing downstream
of it may buffer. With `flowOn` *after* the conflation, a 64-deep channel sat between the release and the renderer:
every frame was freed while still queued, the renderer refused a dead frame, and about one frame in a hundred
reached the screen while the core decoded happily at 30 fps. Put `flowOn` first, conflate last.

### Fanning out to several tiles

`shareIn` cannot carry these frames, and the reason is worth stating precisely because it cost two attempts:
**`SharedFlow.emit` is an asynchronous hand-off.** It resumes once a subscriber has been *woken*, not once that
subscriber's collector has run — so releasing after `emit` frees the frame while the renderer is still being
dispatched to. On device that was about a third of frames arriving already released and the rest of the picture
black.

So fan out explicitly (`call/impl/…/SharedFrameStream.kt`): **retain once per subscriber before
offering**, each subscriber releases its own reference when its collector returns, and collectors run inline from
their own channel loop with no dispatcher change in between. Every path accounted for — no subscribers means release
immediately and never queue; a slow subscriber's own one-deep channel drops and releases; a departing subscriber's
cancelled channel releases what it held. The fan-out **must not** release the upstream's reference: the upstream
hands the frame over for the duration of the call and releases it itself afterwards. Having it release too was a
double free that blacked out every renderer.

Add a **linger** (we use 3 s) before closing the upstream after the last collector leaves. Compose disposes the old
tile before composing the new one, so moving a member between spotlight and strip takes the subscriber count to zero
in between; a bare `WhileSubscribed()` reads that as "nobody is watching" and tears the stream down — which meant
`stream.close()` racing a frame in flight, releasing the decoder underneath libwebrtc's `AndroidVideoDecoder` output
thread, and aborting the process, reliably, whenever a third participant joined.

The same rule covers a change of *layout*, not only of position. Our one-to-one arrangement — the other person
full-bleed, ourselves as a thumbnail in a corner — is not a different screen but the same keyed tile composables
handed different rectangles (`CallTileLayout.kt`, `ElementCallScreenState.layout`), so a third member joining or leaving a
DM call moves everyone to where the group layout puts them without a single renderer being rebuilt. A layout switch
that swaps composables is a subscriber count going to zero for every tile at once, which is the linger race above
multiplied by the call.

### Drawing

Use libwebrtc's renderer: it uploads the three planes as textures and converts on the GPU, so nothing in Kotlin
touches a pixel. `JavaI420Buffer.wrap` takes direct buffers as they are — which is why frames carry direct buffers
rather than byte arrays — and takes a release callback:

```kotlin
if (!frame.retain()) { Timber.w("dropped a frame that was already released — something is buffering"); return }
val buffer = JavaI420Buffer.wrap(w, h, dataY, strideY, dataU, strideU, dataV, strideV, frame::release)
val videoFrame = VideoFrame(buffer, frame.rotationDegrees, frame.timestampUs * 1_000)
try { renderer.onFrame(videoFrame) } finally { videoFrame.release() }
```

Passing `null` as the release callback — correct while the planes were copies you owned — frees the core's memory
while the GL thread is still reading it.

**Make releasing the renderer mutually exclusive with drawing to it.** A Compose `onRelease` runs the moment a tile
leaves the composition and tears down the GL thread, while the frame collector may be inside `onFrame`; cancelling a
collector is not synchronous, so "the effect was cancelled" is not "the effect has stopped".

Two smaller things that save a debugging session: honour `rotationDegrees` (ignoring it shows a sideways picture on
a phone held upright, and it is *not* applied to the pixels), and render a placeholder under
`LocalInspectionMode` — previews and screenshot tests have no GL context.

---

## 13. Constraints, simulcast and dynacast

Two halves of one mechanism, and each is useless without the other.

**Sender: publish simulcast** (§9). Without it dynacast pauses your only layer and nothing leaves the device.

**Receiver: say how big you are actually drawing.** With nothing said, the SFU sends its best layer — so a tile
drawn 100 dp wide receives, decodes and composites a full 720p frame to show it at a tenth of that.

```kotlin
mediaSession.setConstraints(
    memberId, kind,
    FfiMediaConstraints(
        enabled = true,                 // still subscribed; `visible` is what lets the SFU stop sending
        visible = isVisible,
        detail = if (isVisible) FfiVideoDetail.Dimensions(widthPx.toUInt(), heightPx.toUInt()) else FfiVideoDetail.Auto,
        lowBandwidth = false,
    )
)
```

**Measured effect on a two-party call with three tiles: remote video went from 5–9 fps to 20–30, and the phone from
38 °C to 34 °C.** It was the largest single performance lever on the receive side and the reason the phone was hot.
That was our omission rather than a library fault, and it is recorded because the symptom — bad remote frame rate —
is indistinguishable from one (`FEEDBACK.md` item 20).

How to drive it:

- **From the layout**, because the layout is the only thing that knows the size. Ours reports from the tile
  composable via an event to the presenter.
- **Express the real drawn size, not a quality band.** A band is your guess about a number you already have.
- **Key the effect on the *target* rectangle, not the animated one.** A tile being promoted should send one message
  when its destination is decided, not sixty as it travels there. Round to whole pixels — sub-pixel layout changes
  are not news.
- **Report nothing for a tile with no video.** There is no stream to constrain.
- **Say `isVisible = false` when subscribed but not drawing** — scrolled away, call backgrounded — rather than
  quietly receiving frames you throw away. Three things about how, each of which shipped after the simple version
  did not hold up in a large call:
  - **Detach with a delay.** Keep drawing for a short time after a tile leaves the screen (we use 2 s,
    `RENDER_DETACH_MS`) and report `NotVisible` only then. A strip flung back and forth otherwise sends the SFU a
    burst of visible/not-visible flips and builds and tears down a GL renderer on every pass. Once the delay has
    elapsed the tile drops its frames, the renderer goes with them, and the stream closes after its own linger
    (§12) — so a tile scrolled away for good stops costing anything within a few seconds.
  - **`NotVisible` keeps the subscription.** The SFU stops sending — "no data, instant resume" — and scrolling the
    tile back costs a key frame rather than a renegotiation. That is the whole saving of a strip that scrolls.
  - **Whoever evicts a tile must speak for it.** Past a certain size, tiles more than a page away from the current
    one are not composed at all — no shell, no avatar, no layout node — because at fifty members on a Pixel 5 that
    is 4 tiles on screen, 8 in reserve and 37 that cost nothing, and placing every one of them on every frame of a
    swipe is what made the swipe stutter. But a tile parked before its detach delay elapsed has no effect left to
    send `NotVisible`: its effects were cancelled with it. So the layout sends it on the tile's behalf
    (`CallTileLayout.kt`, the `parkedTiles` effect). Usually that repeats what the tile already sent, and the
    de-duplication below drops the repeat — which is what makes reporting from two places safe.
- **De-duplicate before the FFI call.** A tile's size is recomputed on every layout pass and most passes land on the
  same numbers; the call is cheap but it reaches the SFU. Keep the last constraints per `(memberId, kind)` and skip
  unchanged ones.
- Failure should be reported, not thrown: it costs only the bandwidth it was trying to save.

---

## 14. Leaving, and the order of teardown

Leaving the transport and leaving the session are separate, and the order matters:

```
1. disconnect media      (mediaSession.disconnect())
2. rtcCall.leave(params) (publishes the leave membership)
3. rtcRoom.shutdown()    (leaves anything still joined, ends the room's subscriptions)
4. close your own room   (after the shutdown: its leave goes through it)
```

**Make `leave` idempotent.** Hanging up and shutting the room down both leave, and the second attempt fails as
`CallOver`.

On the media side, release everything explicitly rather than leaving it to the GC: stop capture (camera light),
stop the projection (recording indicator), stop every playback track, then cancel the scope. And release in the
reverse order you took things — media, then session, then audio focus, then the foreground service.

A call can also end from the far end (`MatrixRtcCallEvent.Ended`). Tear down from a *different* coroutine than the
event collector — teardown cancels the job the collector runs in, and a coroutine cannot wait for its own death. In
that case there is nothing to leave; the session is already over.

---

## 15. Threading model

| Thread | What runs on it |
| :--- | :--- |
| single-threaded FFI dispatcher | every FFI call *start*: room, join, leave, publish, mute, constraints, stats, `participants()` |
| general IO | your backend's sends and subscriptions |
| the core's own runtime | its feeds and upkeep: keep-alive, key rotation — nothing for you to schedule |
| one thread per audio loop, at `THREAD_PRIORITY_URGENT_AUDIO` | `stream.next()` and the `AudioTrack` write; the `AudioRecord` read and `track.captureAudio` |
| capture threads (camera, GL) | `track.captureVideo` — straight from the capture callback, not via the FFI dispatcher |
| your app scope | call ownership, so a call outlives whatever screen is showing it |

**Why the audio loops get threads of their own, and why general IO is not good enough.** These loops have a 10 ms
deadline: the device drains a buffer whether or not you refilled it, and what it plays in the gap is an audible
crackle. On Android, `Dispatchers.IO` is a view over the same pool as `Dispatchers.Default`, so putting them there
puts them among ordinary `nice = 0` workers, queued behind video decode, encode and GC — while the `AudioTrack` and
`AudioRecord` threads they feed run at `nice = -16` and `MediaCodec` at `nice = -10`.

We shipped it that way and it crackled. Measured on a Pixel 5 in a five-party call, playback moved 5.00 s of audio in
5.45–5.97 s of wall clock as soon as the local camera was enabled — roughly 15% of the output was under-run — with RTP
delivery clean throughout (`0 lost, 0% invented`). The 1 Hz stats poll was landing at 1.4–1.5 s in the same window,
which is the same starvation seen from another angle. One thread per loop, at `THREAD_PRIORITY_URGENT_AUDIO`, is what
that costs to avoid; one thread *per loop* rather than one shared, because a blocking write for one member must not
delay another's.

Worth knowing that this is not a host invention: `WebRtcAudioTrack` and `WebRtcAudioRecord` inside the AAR's bundled
`libwebrtc.jar` both do exactly this. They are unused here — the FFI hands over PCM, so the host opens the devices —
which is precisely why the obligation moves to the host and has to be written down.

Why single-thread the FFI *starts*: the core is internally synchronised and its exported methods suspend, so uniffi
resumes them wherever it likes. What you buy is that the order you hand things over in is the order they are picked
up, and that no FFI call is initiated from the main dispatcher.

Why *not* for media frames: 30 frames a second queued behind membership and stats calls, for no benefit. Neither
`captureAudio` nor `captureVideo` suspends.

Why nothing long-lived goes on the FFI dispatcher: parking that single thread leaves nothing to start `leave()` or
the media connection on — the call simply hangs. `next()` suspends rather than blocks, so it is safe anywhere.

**Own the call above the UI.** Driving it from a screen's presenter makes the composition the call's lifetime, so
navigating away ends the call and the only shape the UI can take is full-screen in its own task. Holding it in an
app-scoped controller makes the call a fact about the app, and the full-screen UI and a minimized bar become two
renderings of one thing. Funnel mutations through a mutex so callers can fire and forget from any thread and the UI
only ever sees whole snapshots.

**Rate-limit high-frequency diagnostics before they become state.** The PCM meter reports ten times a second *per
member*. Funnelled straight into the snapshot, an eleven-person call produced over a hundred snapshots a second from
that one source, each a new state and a recomposition of every tile. Sample it to ten a second in total
(`AUDIO_LEVEL_SAMPLE_MS = 100`, in `DefaultElementCallController.startObservers`); a meter needs no more. It helps that
nothing but the diagnostics overlay reads the PCM levels: the speaker ring on a tile comes from the SFU's
`ActiveSpeakers` event, which is both cheaper and more useful — it still lights up for a member whose media we cannot
decrypt, which is exactly the case worth being able to see.

---

## 16. Platform obligations (Android)

**Permissions**: `RECORD_AUDIO`, `CAMERA`, `FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_MICROPHONE`,
`_CAMERA`, `_MEDIA_PROJECTION`.

**Foreground service**, `android:foregroundServiceType="microphone|camera|mediaProjection"`. Android will not let a
backgrounded app hold the microphone without one, so it must be up *before* capture starts. Compute the type from
what has actually been granted — `startForeground` throws if a type's permission is missing — and restart the
service to upgrade it when the camera or projection is granted later. `onStartCommand` runs again on the same
instance, so this is cheap and idempotent.

**Permissions must be requested by an Activity**, which your call controller is not. Model it explicitly: enter a
`RequestingPermission` state, let the host answer, and make the answer idempotent — a host re-reports a permission
it already has whenever it is recreated, and a configuration change would otherwise join the call twice.

**Audio focus and communication mode**: request focus so other apps duck; start the device controller *after*
entering communication mode, because routing choices do not stick outside it.

**Choose the built-in output by call kind.** A voice call is held to the head, so the earpiece; a video call is
looked at, so the loudspeaker. Anything the user plugged in or paired outranks both either way — reaching for a
headset is itself the instruction. Decide it when the device controller starts
(`start(preferLoudspeaker = !isAudioCall)`, ranked in `DefaultCallAudioDeviceController.priorityOf`), because that
fixes the order the device list comes out in and the head of that list is what gets selected. Do not copy a
meeting-style order that ranks the loudspeaker first unconditionally — `WebViewAudioManager` does, which is right
for a meeting laid on a desk and startling for a phone call.

**Proximity blanking**, if you use it, needs three conditions: maximized, no video, app in foreground. Held
indiscriminately it blanks the screen whenever a hand reaches near the top — which for a minimized call means the
gesture that reaches the notification shade is the gesture that blanks the screen.

---

## 17. Checklist

- [ ] AAR + JNA on the classpath, FFI imports confined to one module
- [ ] `MatrixRtc.initialize()` before `uniffiEnsureInitialized()`, both before any FFI use
- [ ] Logging configured before the first FFI call (native logcat sink)
- [ ] `MatrixBackend` implemented — sends verbatim, failures carry errcode and status, to-device encrypted and reported per recipient
- [ ] `subscribeRoom` returns at once; every set delivered current-first, **an empty set included**; members include you
- [ ] Each state type delivered under its own spelling; to-device encryption info from the client, never the content
- [ ] Your room routable before `RtcClient.room()`, closed only after `shutdown()`
- [ ] `rtcTransports()` with the well-known fallback; joins `Advertised`
- [ ] Member id taken from the core, never derived
- [ ] `notify` only when *starting* a call
- [ ] Event pump and roster sweep before announcing "connected"
- [ ] Microphone: 48 kHz mono 10 ms; mute stops capture **and** `setLocalMuted`
- [ ] Audio loops on their own threads at `THREAD_PRIORITY_URGENT_AUDIO`, never on a shared pool
- [ ] Device buffers floored at a few frames, not left at `getMinBufferSize`; nothing allocated per frame
- [ ] Per-member audio levels sampled before reaching UI state; tiles light up from `ActiveSpeakers`, not PCM
- [ ] Built-in output chosen by call kind (earpiece for voice, loudspeaker for video); a headset outranks both
- [ ] Camera and screen published with `simulcast = true`
- [ ] Planes repacked row by row; chroma dimensions rounded up; rotation passed through
- [ ] Camera off releases the device but keeps the track published
- [ ] Screen share *stopped* with `unpublish(SCREEN_SHARE)`, not muted, and the track handle dropped
- [ ] `mediaProjection` foreground service running **before** claiming the projection
- [ ] Playback claimed before opening the stream; own member id skipped
- [ ] Frames reference-counted; conflation releases dropped ones; `flowOn` before conflate
- [ ] One `videoStream` per `(member, kind)`, fanned out explicitly, with a linger
- [ ] Renderer release exclusive with drawing; `JavaI420Buffer.wrap` given a release callback
- [ ] `setConstraints` driven from real tile sizes, de-duplicated
- [ ] Off-screen tiles report `NotVisible` after a detach delay; tiles evicted from composition are reported by the layout
- [ ] Teardown: leave, then shut the room down; capture and projection released explicitly
- [ ] Audio/video receive stats logged (packets, lost, concealment, frames decoded)

---

## 18. Failure modes worth recognising

Each of these looked like something else first.

| Symptom | Cause |
| :--- | :--- |
| Call connects, far end shows grey, no inbound stream created | `simulcast = false` — dynacast paused your only layer |
| Roster empty for the whole call, but media works | memberships fed before `subscribeMembershipSnapshots`, or `members` not woken in this compat mode — read `memberCount` |
| Every membership silently rejected | room members not fed first (`SenderNotInRoom`) |
| You join, then vanish from the far end ~20 s later | an empty legacy-state snapshot was fed; the dead man's switch fired |
| Member stuck at `MISSING_KEY`, nothing in the log | to-device subscribed per call instead of per session, or the key was marked not cross-signed |
| Conformant peer refuses your membership while your logs look healthy | slot id missing the `{application}#` prefix |
| Picture shears further sideways down each frame | plane copied `stride × rows` instead of row by row, or chroma rounded down |
| ~1 frame in 100 reaches the screen at a healthy 30 fps decode | a buffer sits downstream of the conflation that releases frames |
| A third of frames arrive already released, picture black | `shareIn` used to fan out, or the fan-out released the upstream's reference |
| Process aborts inside `VideoSinkWrapper::on_frame` | two `videoStream` handles on one track, or a stream closed under a frame in flight (no linger) |
| Process aborts inside `AttachCurrentThreadIfNeeded` | renderers created and destroyed at speed — add dwell to whatever moves tiles around |
| `IllegalStateException: Unable to retrieve AudioTrack pointer for write()` | release racing a blocking write; make them mutually exclusive |
| Remote video at 5–9 fps and the phone is hot | `setConstraints` never called |
| Receiver draws a grey tile for a screen share that ended | share stopped by muting; use `unpublish(SCREEN_SHARE)` |
| First media call dereferences null in `RtcRuntime()` | `MatrixRtc.initialize()` not called; JNA's `dlopen` never ran `JNI_OnLoad` |
| Far end never decrypts us for a whole call after they left and rejoined, every local signal (`FrameEncryption` included) healthy — seen in `ROOM_STATE` | our key went to the departed membership and the rejoined member (same `userId:deviceId` id in that mode) was credited with it; the deferred rotation never fired. Library-side, `FEEDBACK.md` items 24 and 25 — a fix shipped in the AAR of 2026-09-02, unverified on device |
| Swiping a long strip stutters, and video keeps arriving for tiles nobody has scrolled to | every tile composed however far away; or evicted tiles never reported `NotVisible` because their effect was cancelled with them |
| A fling over the strip sends a burst of visible/not-visible flips and rebuilds renderers | no detach delay before reporting `NotVisible` |
| Every tile recomposes many times a second in a large call | per-member audio levels reaching state unsampled |
| A voice call starts on the loudspeaker, or a video call on the earpiece | built-in output priority not keyed on the call kind |

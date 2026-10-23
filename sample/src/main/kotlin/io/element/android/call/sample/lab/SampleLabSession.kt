/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import android.content.Context
import io.element.android.call.api.ElementCallOptions
import io.element.android.call.api.rtc.MatrixRtcLogLevel
import io.element.android.call.api.rtc.MatrixRtcLoggingConfiguration
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.sample.matrix.SdkElementCallRoomContext
import io.element.android.call.sample.matrix.SdkMatrixBackend
import io.element.android.call.sample.matrix.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.AuthDataPasswordDetails
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CrossSigningResetAuthType
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.initPlatform
import timber.log.Timber
import java.io.File

/**
 * A logged-in Rust SDK session for the lab (feature-hq `harness/lab.md`): the sync running, the one room the call is in,
 * and the [ElementCallStack] over [SdkMatrixBackend], kept for the session's life as the library asks. The session is
 * kept, store and all, until logged out: rejoining after a call or a relaunch is the same device, with no new device
 * for the others to discover. Scanning the same account's code again reuses it; another account's logs this one out.
 */
class SampleLabSession private constructor(
    val client: Client,
    val room: Room,
    val roomId: String,
    val stack: ElementCallStack,
    private val syncService: SyncService,
    private val scope: CoroutineScope,
) {
    /** The host's connectivity report (034 R11) from the sync's state: offline on `OFFLINE` or `ERROR`, online on the `RUNNING` after one. */
    fun observeConnectivity(report: (Boolean) -> Unit): TaskHandle {
        var isOffline = false
        return syncService.state(
            object : SyncServiceStateObserver {
                override fun onUpdate(state: SyncServiceState) {
                    when (state) {
                        SyncServiceState.OFFLINE, SyncServiceState.ERROR -> if (!isOffline) {
                            isOffline = true
                            report(false)
                        }
                        SyncServiceState.RUNNING -> if (isOffline) {
                            isOffline = false
                            report(true)
                        }
                        SyncServiceState.IDLE, SyncServiceState.TERMINATED -> Unit
                    }
                }
            },
        )
    }

    /** Stops the sync and the stack, so another client can take the store; the session stays kept. */
    suspend fun stop() {
        runCatchingExceptions { syncService.stop() }
        stack.close()
        scope.cancel()
    }

    /** Logs this device out on the server and forgets it, store and all. */
    suspend fun logOut(context: Context) {
        runCatchingExceptions { syncService.stop() }
        runCatchingExceptions { client.logout() }.onFailure { Timber.w(it, "Lab: logging out on the server failed; forgetting the session anyway") }
        stack.close()
        scope.cancel()
        Saved.clear(context)
        Timber.i("Lab: logged out")
    }

    /** What is kept between launches to restore the session: where it logged in, and the SDK's session. */
    data class Saved(
        val homeserver: String,
        val user: String,
        val roomId: String,
        val accessToken: String,
        val refreshToken: String?,
        val userId: String,
        val deviceId: String,
        val homeserverUrl: String,
        val oauthData: String?,
    ) {
        fun isAccount(homeserver: String, user: String) = this.homeserver == homeserver && this.user == user

        val session: Session
            get() = Session(accessToken, refreshToken, userId, deviceId, homeserverUrl, oauthData, SlidingSyncVersion.NATIVE)

        fun store(context: Context) {
            val json = JSONObject()
                .put("homeserver", homeserver)
                .put("user", user)
                .put("roomId", roomId)
                .put("accessToken", accessToken)
                .put("refreshToken", refreshToken)
                .put("userId", userId)
                .put("deviceId", deviceId)
                .put("homeserverUrl", homeserverUrl)
                .put("oauthData", oauthData)
            prefs(context).edit().putString(KEY, json.toString()).apply()
        }

        companion object {
            private const val KEY = "session"

            private fun prefs(context: Context) = context.getSharedPreferences("lab", Context.MODE_PRIVATE)

            fun load(context: Context): Saved? {
                val json = prefs(context).getString(KEY, null)?.let(::JSONObject) ?: return null
                return Saved(
                    homeserver = json.getString("homeserver"),
                    user = json.getString("user"),
                    roomId = json.getString("roomId"),
                    accessToken = json.getString("accessToken"),
                    refreshToken = json.optString("refreshToken").takeIf { it.isNotEmpty() && it != "null" },
                    userId = json.getString("userId"),
                    deviceId = json.getString("deviceId"),
                    homeserverUrl = json.getString("homeserverUrl"),
                    oauthData = json.optString("oauthData").takeIf { it.isNotEmpty() && it != "null" },
                )
            }

            fun clear(context: Context) {
                prefs(context).edit().remove(KEY).apply()
                storeDirectory(context).deleteRecursively()
            }
        }
    }

    class Failure(message: String) : Exception(message)

    companion object {
        private fun storeDirectory(context: Context) = File(context.filesDir, "lab")

        private var platformReady = false

        /** The SDK's own platform support, once per process: on Android its TLS verifier needs the JVM and the app's context. */
        @Synchronized
        private fun initSdkPlatform(context: Context) {
            if (platformReady) return
            System.loadLibrary("matrix_sdk_ffi")
            org.matrix.rustsdk.Android.init(context.applicationContext)
            initPlatform(TracingConfiguration(LogLevel.WARN, emptyList(), emptyList(), true, null, null), false)
            platformReady = true
        }

        /** For a scanned code: the kept session when it is the same account and its token still works, else a new login. */
        suspend fun login(context: Context, join: SampleLabJoin, parentScope: CoroutineScope): SampleLabSession = withContext(Dispatchers.IO) {
            val trust = install(context, join.homeserver)
            Saved.load(context)?.let { saved ->
                if (saved.isAccount(join.homeserver, join.user) && isValid(trust, saved)) {
                    Timber.i("Lab: reusing the kept session ${saved.userId} / ${saved.deviceId}")
                    return@withContext restore(context, saved, join.roomId, parentScope)
                }
                Timber.i("Lab: the kept session ${saved.userId} is for another account or no longer valid; logging it out")
                logOut(context, trust, saved)
            }
            Saved.clear(context)

            Timber.i("Lab: logging in as ${join.user} on ${join.homeserver}")
            val client = makeClient(context, join.homeserver)
            client.login(join.user, join.password, "Element Call lab", null)
            val session = client.session()
            Saved(
                homeserver = join.homeserver,
                user = join.user,
                roomId = join.roomId,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                userId = session.userId,
                deviceId = session.deviceId,
                homeserverUrl = session.homeserverUrl,
                oauthData = session.oauthData,
            ).store(context)
            val (syncService, room) = startSync(client, join.roomId)
            adoptIdentity(client, join.password, join.recoveryKey)
            sayHi(room)
            make(context, client, room, join.roomId, syncService, parentScope)
        }

        /** The kept session, for rejoining without a code. */
        suspend fun restoreSaved(context: Context, parentScope: CoroutineScope): SampleLabSession = withContext(Dispatchers.IO) {
            val saved = Saved.load(context) ?: throw Failure("no lab session kept on this phone")
            install(context, saved.homeserver)
            restore(context, saved, saved.roomId, parentScope)
        }

        /** The kept session logged out without a live client. A token the server no longer knows (a lab reset) is only forgotten. */
        suspend fun logOutSaved(context: Context) = withContext(Dispatchers.IO) {
            val saved = Saved.load(context) ?: return@withContext
            runCatchingExceptions { logOut(context, install(context, saved.homeserver), saved) }
            Saved.clear(context)
        }

        private fun install(context: Context, homeserver: String): SampleLabTrust {
            val url = SampleLabJoin.certificateUrl(homeserver) ?: throw Failure("no lab CA for $homeserver")
            return SampleLabTrust.install(context, url)
        }

        private suspend fun logOut(context: Context, trust: SampleLabTrust, saved: Saved) {
            if (isValid(trust, saved)) {
                runCatchingExceptions {
                    val client = makeClient(context, saved.homeserver)
                    client.restoreSession(saved.session)
                    client.logout()
                }.onFailure { Timber.w(it, "Lab: logging ${saved.userId} out on the server failed") }
            }
        }

        private suspend fun restore(context: Context, saved: Saved, roomId: String, parentScope: CoroutineScope): SampleLabSession {
            val client = makeClient(context, saved.homeserver)
            client.restoreSession(saved.session)
            if (roomId != saved.roomId) saved.copy(roomId = roomId).store(context)
            val (syncService, room) = startSync(client, roomId)
            return make(context, client, room, roomId, syncService, parentScope)
        }

        /**
         * Whether the server still knows the kept token: `whoami` answers for the same device. A lab reset wipes the
         * homeserver, and a restored client with a dead token would only find out in its sync. Any other failure (the
         * laptop unreachable) is not an answer, so the session is kept.
         */
        private fun isValid(trust: SampleLabTrust, saved: Saved): Boolean {
            val connection = runCatchingExceptions {
                trust.open("${saved.homeserverUrl.trimEnd('/')}/_matrix/client/v3/account/whoami").apply {
                    setRequestProperty("Authorization", "Bearer ${saved.accessToken}")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
            }.getOrNull() ?: return true
            return runCatchingExceptions {
                when (connection.responseCode) {
                    200 -> JSONObject(connection.inputStream.bufferedReader().readText()).optString("device_id") == saved.deviceId
                    else -> false
                }
            }.getOrDefault(true)
        }

        private suspend fun makeClient(context: Context, homeserver: String): Client {
            initSdkPlatform(context)
            val base = storeDirectory(context).apply { mkdirs() }
            return ClientBuilder()
                .serverNameOrHomeserverUrl(homeserver)
                .sessionPaths(File(base, "data").absolutePath, File(base, "cache").absolutePath)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                // A member's key is only accepted from a device its owner's identity signs, so the first device sets the identity up.
                .autoEnableCrossSigning(true)
                // Not `addRootCertificates`: on Android the SDK verifies with the platform verifier, which refuses extra
                // roots ("builder error"). It trusts the lab's CA from the phone's own store (network_security_config.xml).
                .build()
        }

        /** Starts the sync, offline mode so it outlives a homeserver outage, and waits for the room, which the seed has every account in. */
        private suspend fun startSync(client: Client, roomId: String): Pair<SyncService, Room> {
            val syncService = client.syncService().withOfflineMode().finish()
            syncService.start()
            repeat(60) {
                runCatchingExceptions { client.getRoom(roomId) }.getOrNull()?.let { return syncService to it }
                delay(500)
            }
            syncService.stop()
            throw Failure("the account is not in $roomId, or the sync has not delivered it")
        }

        /**
         * Every login is a new device, which must join the account's cross-signing identity or the others refuse its
         * media key. With the recovery key it does. Without one, the identity is reset to this device only if the
         * account never had recovery: resetting one that has deletes its backup and leaves the lab's key opening nothing.
         */
        private suspend fun adoptIdentity(client: Client, password: String, recoveryKey: String?) {
            val encryption = client.encryption()
            waitUntil { encryption.verificationState() != VerificationState.UNKNOWN }
            val state = encryption.verificationState()
            if (state != VerificationState.UNVERIFIED) {
                Timber.i("Lab: this device is $state by its identity")
                return
            }
            if (recoveryKey != null) {
                Timber.i("Lab: joining the account's identity with its recovery key")
                runCatchingExceptions { encryption.recover(recoveryKey) }.onFailure {
                    throw Failure("the code's recovery key does not open the account's secret storage (${it.message}); rerun `lab identities` on the laptop")
                }
                waitUntil { encryption.verificationState() == VerificationState.VERIFIED }
                Timber.i("Lab: after recovery this device is ${encryption.verificationState()} by its identity")
                return
            }
            waitUntil { encryption.recoveryState() != RecoveryState.UNKNOWN }
            if (encryption.recoveryState() != RecoveryState.DISABLED) {
                throw Failure("${client.userId()} has recovery set up but the code carries no recovery key; rescan once the lab page shows one")
            }
            val handle = encryption.resetIdentity() ?: run {
                Timber.w("Lab: the identity could not be reset; the others may refuse our key")
                return
            }
            Timber.i("Lab: resetting the cross-signing identity to this device")
            when (val auth = handle.authType()) {
                is CrossSigningResetAuthType.Uiaa -> handle.reset(AuthData.Password(AuthDataPasswordDetails(client.userId(), password, auth.info.session)))
                is CrossSigningResetAuthType.OAuth -> {
                    Timber.w("Lab: the homeserver wants OAuth for the reset, which the lab does not drive")
                    handle.cancel()
                }
            }
        }

        /** Up to 10 s, for state the SDK settles shortly after a login. */
        private suspend fun waitUntil(condition: () -> Boolean) {
            var left = 20
            while (!condition() && left-- > 0) delay(500)
        }

        /**
         * A fresh device joining a room-state call at once races its own key downloads: a member's media key can arrive
         * from a device it has not fetched yet, and is refused. An encrypted "hi" first makes the SDK fetch every member's
         * devices and share a room key with them. Best effort: the join goes on without it.
         */
        private suspend fun sayHi(room: Room) {
            runCatchingExceptions { room.sendRaw("m.room.message", """{"msgtype":"m.text","body":"hi"}""") }
                .onSuccess { Timber.i("Lab: said hi in the room, so the members' devices are known before the call") }
                .onFailure { Timber.w(it, "Lab: could not say hi; joining anyway") }
        }

        private fun make(
            context: Context,
            client: Client,
            room: Room,
            roomId: String,
            syncService: SyncService,
            parentScope: CoroutineScope,
        ): SampleLabSession {
            val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]))
            val stack = ElementCallStack.Builder(context, SdkMatrixBackend(client, scope))
                .roomContext(SdkElementCallRoomContext(client))
                .options(ElementCallOptions(logging = MatrixRtcLoggingConfiguration(logLevel = MatrixRtcLogLevel.INFO, writesToLogcat = true)))
                .build(scope)
            Timber.i("Lab: logged in as ${client.userId()}, room $roomId synced")
            return SampleLabSession(client, room, roomId, stack, syncService, scope)
        }
    }
}

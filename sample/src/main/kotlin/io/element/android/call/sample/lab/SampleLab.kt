/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.element.android.call.sample.SampleCallController
import io.element.android.call.sample.matrix.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The lab on the home screen: the account kept on this phone, back into its call or logged out, and a code opened from
 * the camera. The session in hand is reused for the same account and room; otherwise [SampleLabSession.login] decides
 * between the kept session and a new login.
 */
class SampleLab(private val context: Context, private val scope: CoroutineScope, private val controller: SampleCallController) {
    private val accountFlow = MutableStateFlow(SampleLabSession.Saved.load(context)?.userId)

    /** The kept account's Matrix ID, null when logged out. */
    val account: StateFlow<String?> = accountFlow

    private val noticeFlow = MutableStateFlow<String?>(null)

    /** What the last lab action is doing, or why it failed. */
    val notice: StateFlow<String?> = noticeFlow

    private var session: SampleLabSession? = null
    private var job: Job? = null

    fun open(join: SampleLabJoin) = start("Logging in as ${join.user}…") { current ->
        val sameAccount = SampleLabSession.Saved.load(context)?.isAccount(join.homeserver, join.user) == true
        if (current != null && sameAccount && current.roomId == join.roomId) {
            current
        } else {
            if (current != null) {
                session = null
                if (sameAccount) current.stop() else current.logOut(context)
            }
            SampleLabSession.login(context, join, scope)
        }
    }

    /**
     * The lab page's code, scanned inside the app: the camera app hands a scanned `elementcall-lab://` link to the
     * browser, which does not open custom schemes. Play services draws the scanner.
     */
    fun scan(activity: Activity) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { barcode ->
                val join = barcode.rawValue?.let(Uri::parse)?.let(SampleLabJoin::parse)
                if (join != null) open(join) else noticeFlow.value = "Lab: that code is not a lab code"
            }
            .addOnFailureListener {
                Timber.w(it, "Lab: the scanner failed")
                noticeFlow.value = "Lab: the scanner failed: ${it.message}"
            }
    }

    fun rejoin() = start("Rejoining as ${accountFlow.value}…") { current -> current ?: SampleLabSession.restoreSaved(context, scope) }

    fun logOut() {
        controller.endLiveCall()
        job?.cancel()
        val current = session
        session = null
        accountFlow.value = null
        noticeFlow.value = null
        job = scope.launch {
            if (current != null) current.logOut(context) else SampleLabSession.logOutSaved(context)
        }
    }

    private fun start(notice: String, makeSession: suspend (SampleLabSession?) -> SampleLabSession) {
        controller.endLiveCall()
        job?.cancel()
        noticeFlow.value = notice
        job = scope.launch {
            runCatchingExceptions { makeSession(session) }
                .onSuccess {
                    session = it
                    accountFlow.value = SampleLabSession.Saved.load(context)?.userId
                    noticeFlow.value = null
                    controller.startLab(SampleLabCall(it))
                }
                .onFailure {
                    Timber.w(it, "Lab: could not join")
                    accountFlow.value = SampleLabSession.Saved.load(context)?.userId
                    noticeFlow.value = "Lab: ${it.message}"
                }
        }
    }
}

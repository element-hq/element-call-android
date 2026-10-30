/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import android.content.Context
import android.system.Os
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * The lab's CA, for the TLS clients a lab call uses that do not read the phone's store: the core's token exchange and SFU
 * socket (rustls, which reads `SSL_CERT_FILE`), and this sample's own session check. The SDK reads the phone's store, where
 * the tester installs the CA once (network_security_config.xml). Fetched over plain HTTP from the lab page, which is
 * acceptable for a debug sample on a LAN lab only.
 */
class SampleLabTrust private constructor(val certificate: ByteArray) {
    private val sslContext: SSLContext by lazy {
        val ca = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream())
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("lab", ca)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    /** An HTTPS connection that trusts the lab's CA. */
    fun open(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        sslSocketFactory = sslContext.socketFactory
    }

    companion object {
        /** Blocking: call on an IO dispatcher. */
        fun install(context: Context, certificateUrl: String): SampleLabTrust {
            val connection = URL(certificateUrl).openConnection() as HttpURLConnection
            val certificate = connection.inputStream.use { it.readBytes() }
            val file = File(context.filesDir, "lab-ca.pem").apply { writeBytes(certificate) }
            Os.setenv("SSL_CERT_FILE", file.absolutePath, true)
            return SampleLabTrust(certificate)
        }
    }
}

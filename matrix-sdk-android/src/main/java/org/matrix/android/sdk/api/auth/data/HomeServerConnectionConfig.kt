/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.auth.data

import com.squareup.moshi.JsonClass
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig.Builder
import org.matrix.android.sdk.api.network.ssl.Fingerprint
import org.matrix.android.sdk.internal.util.ensureTrailingSlash

/**
 * This data class holds how to connect to a specific Homeserver.
 * It's used with [org.matrix.android.sdk.api.auth.AuthenticationService] class.
 * You should use the [Builder] to create one.
 * URIs are held as strings so the class stays platform-neutral; the JSON shape is unchanged from
 * when they were android Uri (which always serialized as the plain string).
 */
@JsonClass(generateAdapter = true)
data class HomeServerConnectionConfig(
        // This is the homeserver URL entered by the user
        val homeServerUri: String,
        // This is the homeserver base URL for the client-server API. Default to homeServerUri,
        // but can be updated with data from .Well-Known before login, and/or with the data
        // included in the login response
        val homeServerUriBase: String = homeServerUri,
        val identityServerUri: String? = null,
        val antiVirusServerUri: String? = null,
        val allowedFingerprints: List<Fingerprint> = emptyList(),
        val shouldPin: Boolean = false,
        val tlsVersions: List<TlsVersion>? = null,
        val tlsCipherSuites: List<CipherSuite>? = null,
        val shouldAcceptTlsExtensions: Boolean = true,
        val allowHttpExtension: Boolean = false,
        val forceUsageTlsVersions: Boolean = false
) {

    /**
     * This builder should be use to create a [HomeServerConnectionConfig] instance.
     */
    class Builder {
        private lateinit var homeServerUri: String
        private var identityServerUri: String? = null
        private var antiVirusServerUri: String? = null
        private val allowedFingerprints: MutableList<Fingerprint> = ArrayList()
        private var shouldPin: Boolean = false
        private val tlsVersions: MutableList<TlsVersion> = ArrayList()
        private val tlsCipherSuites: MutableList<CipherSuite> = ArrayList()
        private var shouldAcceptTlsExtensions: Boolean = true
        private var allowHttpExtension: Boolean = false
        private var forceUsageTlsVersions: Boolean = false

        /**
         * @param hsUriString The URI to use to connect to the homeserver.
         * @return this builder
         */
        fun withHomeServerUri(hsUriString: String): Builder {
            if (uriScheme(hsUriString) != "http" && uriScheme(hsUriString) != "https") {
                throw RuntimeException("Invalid homeserver URI: $hsUriString")
            }
            homeServerUri = hsUriString.ensureTrailingSlash()
            return this
        }

        /**
         * @param identityServerUriString The URI to use to manage identity.
         * @return this builder
         */
        fun withIdentityServerUri(identityServerUriString: String): Builder {
            if (uriScheme(identityServerUriString) != "http" && uriScheme(identityServerUriString) != "https") {
                throw RuntimeException("Invalid identity server URI: $identityServerUriString")
            }
            this.identityServerUri = identityServerUriString.ensureTrailingSlash()
            return this
        }

        /**
         * @param allowedFingerprints If using SSL, allow server certs that match these fingerprints.
         * @return this builder
         */
        fun withAllowedFingerPrints(allowedFingerprints: List<Fingerprint>?): Builder {
            if (allowedFingerprints != null) {
                this.allowedFingerprints.addAll(allowedFingerprints)
            }
            return this
        }

        /**
         * @param pin If true only allow certs matching given fingerprints, otherwise fallback to
         * standard X509 checks.
         * @return this builder
         */
        fun withPin(pin: Boolean): Builder {
            this.shouldPin = pin
            return this
        }

        /**
         * @param shouldAcceptTlsExtension
         * @return this builder
         */
        fun withShouldAcceptTlsExtensions(shouldAcceptTlsExtension: Boolean): Builder {
            this.shouldAcceptTlsExtensions = shouldAcceptTlsExtension
            return this
        }

        /**
         * Add an accepted TLS version for TLS connections with the homeserver.
         *
         * @param tlsVersion the tls version to add to the set of TLS versions accepted.
         * @return this builder
         */
        fun addAcceptedTlsVersion(tlsVersion: TlsVersion): Builder {
            this.tlsVersions.add(tlsVersion)
            return this
        }

        /**
         * Force the usage of TlsVersion. This can be usefull for device on Android version < 20
         *
         * @param forceUsageOfTlsVersions set to true to force the usage of specified TlsVersions (with [.addAcceptedTlsVersion]
         * @return this builder
         */
        fun forceUsageOfTlsVersions(forceUsageOfTlsVersions: Boolean): Builder {
            this.forceUsageTlsVersions = forceUsageOfTlsVersions
            return this
        }

        /**
         * Add a TLS cipher suite to the list of accepted TLS connections with the homeserver.
         *
         * @param tlsCipherSuite the tls cipher suite to add.
         * @return this builder
         */
        fun addAcceptedTlsCipherSuite(tlsCipherSuite: CipherSuite): Builder {
            this.tlsCipherSuites.add(tlsCipherSuite)
            return this
        }

        /**
         * Update the anti-virus server URI.
         *
         * @param antivirusServerUriString the new anti-virus uri. Can be null
         * @return this builder
         */
        fun withAntiVirusServerUri(antivirusServerUriString: String?): Builder {
            if (null != antivirusServerUriString && uriScheme(antivirusServerUriString) !in listOf("http", "https")) {
                throw RuntimeException("Invalid antivirus server URI: $antivirusServerUriString")
            }
            this.antiVirusServerUri = antivirusServerUriString
            return this
        }

        /**
         * Convenient method to limit the TLS versions and cipher suites for this Builder
         * Ref:
         * - https://www.ssi.gouv.fr/uploads/2017/07/anssi-guide-recommandations_de_securite_relatives_a_tls-v1.2.pdf
         * - https://developer.android.com/reference/javax/net/ssl/SSLEngine
         *
         * @param tlsLimitations true to use Tls limitations
         * @param enableCompatibilityMode set to true for Android < 20
         * @return this builder
         */
        @Deprecated("TLS versions and cipher suites are limited by default")
        fun withTlsLimitations(tlsLimitations: Boolean, enableCompatibilityMode: Boolean): Builder {
            if (tlsLimitations) {
                withShouldAcceptTlsExtensions(false)

                // TlS versions
                ConnectionSpec.RESTRICTED_TLS.tlsVersions()?.let { this.tlsVersions.addAll(it) }

                forceUsageOfTlsVersions(enableCompatibilityMode)

                // Cipher suites
                ConnectionSpec.RESTRICTED_TLS.cipherSuites()?.let { this.tlsCipherSuites.addAll(it) }

                if (enableCompatibilityMode) {
                    // Adopt some preceding cipher suites for Android < 20 to be able to negotiate
                    // a TLS session.
                    addAcceptedTlsCipherSuite(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA)
                    addAcceptedTlsCipherSuite(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA)
                }
            }
            return this
        }

        fun withAllowHttpConnection(allowHttpExtension: Boolean): Builder {
            this.allowHttpExtension = allowHttpExtension
            return this
        }

        /**
         * @return the [HomeServerConnectionConfig]
         */
        fun build(): HomeServerConnectionConfig {
            return HomeServerConnectionConfig(
                    homeServerUri = homeServerUri,
                    identityServerUri = identityServerUri,
                    antiVirusServerUri = antiVirusServerUri,
                    allowedFingerprints = allowedFingerprints,
                    shouldPin = shouldPin,
                    tlsVersions = tlsVersions,
                    tlsCipherSuites = tlsCipherSuites,
                    shouldAcceptTlsExtensions = shouldAcceptTlsExtensions,
                    allowHttpExtension = allowHttpExtension,
                    forceUsageTlsVersions = forceUsageTlsVersions
            )
        }

        companion object {
            /** Same semantics as android.net.Uri.scheme: the text before the first ':', or null. */
            private fun uriScheme(uriString: String): String? =
                    uriString.substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        }
    }
}

package com.example.engine

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Manages true per-profile and Incognito isolation in Chromium WebView.
 * Uses androidx.webkit MULTI_PROFILE (ProfileStore) when supported by the system WebView,
 * and strict cookie/storage isolation for Incognito and Guest sessions.
 */
object ProfileIsolationManager {

    fun isMultiProfileSupported(): Boolean {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    fun resolveTargetWebProfileName(webProfileName: String, isIncognito: Boolean, isGuest: Boolean): String {
        return when {
            isIncognito -> "weblio_incognito_ephemeral"
            isGuest -> "weblio_guest_ephemeral"
            else -> webProfileName
        }
    }

    fun applyProfileToWebView(webView: WebView, webProfileName: String, isIncognito: Boolean, isGuest: Boolean) {
        val targetProfile = resolveTargetWebProfileName(webProfileName, isIncognito, isGuest)
        if (isIncognito || isGuest) {
            if (isMultiProfileSupported()) {
                runCatching {
                    WebViewCompat.setProfile(webView, targetProfile)
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
            return
        }

        if (isMultiProfileSupported()) {
            runCatching {
                WebViewCompat.setProfile(webView, targetProfile)
            }
        }
    }

    fun clearEphemeralData() {
        runCatching {
            CookieManager.getInstance().removeSessionCookies(null)
            CookieManager.getInstance().flush()
        }
        if (isMultiProfileSupported()) {
            runCatching {
                val store = ProfileStore.getInstance()
                store.deleteProfile("weblio_incognito_ephemeral")
                store.deleteProfile("weblio_guest_ephemeral")
            }
        }
    }

    fun clearProfileWebData(webProfileName: String) {
        if (isMultiProfileSupported()) {
            runCatching {
                ProfileStore.getInstance().deleteProfile(webProfileName)
            }
        } else {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    fun clearAllCookiesAndStorage() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}

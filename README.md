# WEBLIO Browser (Android)

**Tagline:** *EXPLORE. DISCOVER. EXPERIENCE.*

WEBLIO Browser is a privacy-first, multi-profile Android web browser built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, WorkManager, Android Keystore, and Chromium-backed WebView + `androidx.webkit`.

---

## 1. Browser Engine Evaluation & Extension Architecture

### Engine Comparison
| Engine Option | Base Technology | License | Maintenance | Extension Capability | Verdict |
|---|---|---|---|---|---|
| **Android System WebView + `androidx.webkit`** | Chromium Blink + V8 (Play-updated) | BSD / Apache 2.0 | Active (Google) | `DocumentStart` JS, `shouldInterceptRequest`, `ProfileStore` isolation, `WebViewAssetLoader` | **Selected** |
| Kiwi / Custom Chromium Fork | Forked Chromium C++ | BSD-3-Clause | Unmaintained / Archived as SDK | Full desktop MV2/MV3 in forked APK only | Rejected (security patch lag, no Gradle AAR) |
| Bromite / Cromite | Hardened Chromium APK | GPLv3 / BSD | Bromite archived; Cromite APK-only | UserScripts only | Rejected (no embeddable library) |
| Mozilla GeckoView | Gecko / SpiderMonkey | MPL 2.0 | Active (Mozilla) | Native `WebExtensionController` | Alternative (non-Chromium, ~95MB+ AAR per ABI) |

### Verified Extension & Filter Compatibility Matrix
- **uBlock Origin (Filter & Cosmetic Engine):** Verified via `AdBlockEngine` (`shouldInterceptRequest` + EasyList rules) and cosmetic CSS/scriptlet injection.
- **Dark Reader (Dynamic Dark CSS Engine):** Verified via luminance-preserving CSS stylesheet injection + `WebSettingsCompat.setAlgorithmicDarkeningAllowed`.
- **Tampermonkey (`.user.js` UserScripts):** Verified via real `==UserScript==` header parsing (`@match`, `@include`, `@run-at`, `@grant`) and `GM_getValue`/`GM_setValue`/`GM_addStyle` JS-to-Kotlin bridge.
- **Privacy Badger (Tracker & Beacon Guard):** Verified via tracking query stripping (`utm_*`, `fbclid`, `gclid`), third-party cookie controls, and `navigator.sendBeacon` filtering.
- **Bitwarden / Vault Autofill Bridge:** Hardware Keystore-encrypted local password vault + form autofill bridge.
- **Unpacked `.zip` / `.crx` WebExtensions:** CRX2/CRX3 header stripping, Zip-Slip-safe extraction, `manifest.json` content scripts, CSS injection, `chrome.storage.local` shim, and sandboxed popup HTML rendering. *Limitation:* Extensions relying on desktop Chromium C++ daemons (`nativeMessaging`, `chrome.debugger`, `declarativeNetRequest` compiled rulesets, or MV3 `offscreen` service workers) are flagged honestly as unsupported in `Extensions -> Compatibility`.

---

## 2. Security & Privacy Safeguards
- **Strict SSL Validation:** `WebViewClient.onReceivedSslError` strictly calls `handler.cancel()` and presents a security prompt; certificate errors are never bypassed.
- **Android Keystore Vault:** Passwords are encrypted using hardware-backed AES-256-GCM (`KeystoreCryptoManager`).
- **Profile PIN Hashing:** PBKDF2WithHmacSHA256 (100,000 iterations, per-profile random 16-byte salt, constant-time verification).
- **Encrypted Local Backups:** `.weblio` backups use passphrase-derived PBKDF2 + AES-256-GCM authentication tags. Unencrypted passwords are never exported.
- **Local-Only DNS VPN Filter:** `WeblioDnsVpnService` intercepts only DNS UDP port-53 queries on-device (`10.111.222.2`) after explicit user consent and detects Android Private DNS conflicts.

---

## 3. Build & Test Instructions

### Build Debug / Release APK
```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
```
Release signing reads `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD` from environment variables in `app/build.gradle.kts`.

### Run Automated Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

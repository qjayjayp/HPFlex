# HP Flex — HandyParken flex-parking manual stepper

"AI (qwen-3.8-flash-next) generated copy of the original app (only supports flex parking and M-Login), due to frustation about the update policy, runs on Android 5 and will see how many updates for just parking are really needed. Only supports M-Login and FlexParking at the moment."

Tiny Android app (plain Java, framework-only, no AndroidX/Gradle deps) that exposes
every step of the flex-parking ("offener Parkschein") protocol as a button, mirroring
`flexparken.sh`. M-Login runs **inside the app** in an embedded WebView — the
`de.swm.login.handyparken:/oauth2redirect?code=...` redirect never leaves the app,
it is intercepted by the WebViewClient, so no system browser is involved.

## Minimum requirements (on the phone)
- **Android 5.0 (API 21) or newer**, any CPU architecture — the APK is pure Java
  bytecode, ~29 KB, no native code, no Google Play Services, no root.
  (Built against targetSdk 34 / Android 14.)
- **No third-party libraries**: framework only (`org.json`, `HttpURLConnection`,
  `WebView`, `AlertDialog`) — hence no runtime dependencies at all.
- **Permissions**: `INTERNET`; `ACCESS_FINE/COARSE_LOCATION` only for the GPS
  button (runtime prompt on Android 6+, everything else works without granting).
- **WebView**: only used for the M-Login page. The app's WebView APIs are old
  (all ≤ API 24), so the app itself puts no requirement on engine age; the floor
  is M-Login's own JavaScript SPA — any engine from ~2016 onward renders it, and
  any Android 13 device already ships a far newer one. TLS is not a constraint
  (measured: M-Login negotiates plain TLS 1.2 / ECDHE-ECDSA; WebView has had
  that since ~2014). Certificate *root* trust comes from the Android OS trust
  store, not the WebView — an `ERR_CERT_AUTHORITY_INVALID` means the device
  needs an OS/trust-store update, updating the WebView would not help.
- **HTTPS only** (`usesCleartextTraffic=false`); user-installed CA certs are
  ignored (targetSdk ≥ 24), so HTTPS-debugging proxies won't be trusted.
- Build-time only (not on the phone): Android SDK (build-tools r34,
  platforms/android-34) + JDK 17+, see Build below.

## Steps
| button | call |
|---|---|
| 1 info | GET v2/info?os=ANDROID&version=<APPVER> (anonymous) |
| 2 login | WebView → login.muenchen.de OAuth2+PKCE → auto-catch redirect → POST v3/user/login → session stored in app prefs |
| 3 status | GET v2/parkschein/active |
| 4 kfz | GET v3/kfz — auto-fills the Kfz-ID + Kennzeichen fields if the response has exactly one vehicle (or one with standard=true); persists across restarts |
| 5 tarif | GET v5/tarif/resolve (what 'start' resolves internally, shown on its own) |
| 6 start | GET v5/tarif/resolve → **aborts with a plain-language message unless `resolveResult=RESOLVED`** (KEIN_TARIFGEBIET / KEINE_VERKAUFSZEIT incl. next selling time) → POST v5/purchase/preauthorize (vehicle is **mandatory**: `kfz:{id,kennzeichen,standard,elektroAuto}`; the `parkingTimeInterval` epoch-ms pair from the resolve response is forwarded — omitting it yields MISSING_END_TIME / PURCHASE_SECURITY_EXCEPTION) → **confirm dialog** → POST v5/purchase/confirm (paymentType MLOGIN, no wallet token) |
| 7 stop | POST v2/parkschein/active/stop |
| GPS | runtime location permission, then LocationManager: fresh fix if last one >60s old (requestSingleUpdate, 20s timeout, falls back to stale last fix) |
| no kfz | clears the vehicle fields/prefs so start omits the kfz object |
| logout | POST v3/user/logout + remove stored session |

Inputs: lat/lng (manual or via GPS button — the last position used, set either way,
is persisted and reloaded at startup; without any saved value the fields seed with
48.137/11.575), Kennzeichen + Kfz-ID (both required —
the server answers VALIDATION_ERROR "kfz: Pflichtfeld" without a vehicle). All
requests/responses are logged in the monospace output view; epoch-ms values
(numbers and `start-end` window strings) are annotated with local date/time.

### App version code field
Every step that talks version (the `version=` query of `v2/info` and
`versionInfoDTO.appVersion` in preauthorize) uses the version-code edit field:
free text, or tap **known ▾** for the codes dumped from the XAPKs
(plus the far-future code from the patched `+versionspoof` build):

| app | versionCode |
|---|---|
| v47 | 2024810047 |
| v86.0 | 2025000003 |
| v92.1 | 2025000006 |
| v99.1 | 2025000014 |
| v105.3 | 2026000017 |
| v107.0 | 2026000020 |
| v108.1 | 2026000021 |
| v110.0 | 2026000023 (default) |
| spoof (far future) | 2099000001 |

The field is persisted in prefs (`appver`), so the choice survives restarts.

## Build
    ./build.sh          # uses ../sdk (build-tools r34, platforms/android-34), JDK 17+
    → HPFlex.apk        # signed with a locally generated debug key (debug.keystore)

Notes on the toolchain: javac must run with
`-bootclasspath android.jar:core-lambda-stubs.jar`, and inner classes must be **named**
(not anonymous) — d8 8.2.2 in build-tools r34 NPEs on anonymous classes compiled by
JDK 21 with `-source 8`.

## Install
    adb install HPFlex.apk
(or copy to the phone; allow "install unknown apps". Debug-signed, minSdk 21.)

## Caveats
- **Flex ticket lifetime is decided by the server from the resolve window.** Starting
  flex *inside* the paid segment (Mo–Sa 09:00–23:00 for L201) yields a ticket that
  runs until stopped (or until the segment ends at 23:00). Starting **outside** the
  paid hours (Sundays/holidays, before 09:00, after 23:00) resolves a degenerate
  ~1-minute window (`absoluteParkDauerInMinuten: 1`), so the purchased ticket ends
  after ~1 minute with `status: FINISHED_AUTOMATICALLY` — the real app behaves
  identically (its code sends the very same fields: `parkDauerInMin=0`,
  `isOpenTicket=true`, resolve's `parkingTimeInterval` verbatim). Both tools now
  print a WARNING before purchasing when the sellable window is ≤ 2 minutes.
- GPS button adds ACCESS_FINE/COARSE_LOCATION (runtime prompt, Android 6+).
- The WebView is the system WebView engine (Blink), but with its **own cookie store** —
  nothing shared with your normal browser, no bookmarks/history entry points.
- The app uses the HandyParken OAuth client (`client_id=handyparken`,
  `login_action=widen_scope`); M-Login sessions are independent per exchange, so the
  original app staying logged in does not conflict (verified in practice).
- `start` only charges after the explicit "Buy" dialog; preauthorize alone is not a purchase.
- APPVER (line 45 of MainActivity.java) is the versionCode the backend grades
  (forceUpdateInDays); current apps send values like 2026000023.
- Only the GPS button itself (positioning on device) has not been exercised yet;
  tarif button, kfz auto-fill and the version picker all work on device.

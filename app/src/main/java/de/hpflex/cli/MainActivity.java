package de.hpflex.cli;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual, step-by-step client for the HandyParken flex-parking (open ticket) flow.
 * Mirrors flexparken.sh: every backend call is a button, results go to the log.
 * M-Login happens in an embedded WebView; the app-scheme redirect is intercepted.
 */
public class MainActivity extends Activity {

    private static final String BASE     = "https://handyparken-backend-prod.web.azrapp.swm.de/api";
    private static final String APIKEY   = "P89pb1e-4c-a7xx-ab29f65efa";
    private static final String MLOGIN   = "https://login.muenchen.de";
    private static final String CLIENT   = "handyparken";
    private static final String REDIRECT = "de.swm.login.handyparken:/oauth2redirect";
    private static final String APPVER_DEF = "2026000023"; // default versionCode presented to the backend

    // Known HandyParken(München) version codes, dumped from the XAPKs in /download/handy,
    // plus the 2099000001 "far future" code used by the verify_spoof patched build.
    private static final String[][] VERSIONS = {
        {"v47",                    "2024810047"},
        {"v86.0",                  "2025000003"},
        {"v92.1",                  "2025000006"},
        {"v99.1",                  "2025000014"},
        {"v105.3",                 "2026000017"},
        {"v107.0",                 "2026000020"},
        {"v108.1",                 "2026000021"},
        {"v110.0",                 "2026000023"},
        {"spoof (far future)",     "2099000001"},
    };

    private SharedPreferences sp;
    private ExecutorService net;
    private TextView out;
    private ScrollView outScroll;
    private WebView web;
    private View loginBar;
    private EditText inLat, inLng, inKennz, inKfzId, inAppVer;
    private Button btnVerList;

    private String pkceVerifier;
    private String oauthState;
    private GpsListener activeGps;
    private boolean gpsPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp  = getSharedPreferences("hpflex", MODE_PRIVATE);
        net = Executors.newSingleThreadExecutor();

        out       = findViewById(R.id.out);
        outScroll = findViewById(R.id.outScroll);
        web       = findViewById(R.id.web);
        loginBar  = findViewById(R.id.loginBar);
        inLat     = findViewById(R.id.inLat);
        inLng     = findViewById(R.id.inLng);
        inKennz   = findViewById(R.id.inKennz);
        inKfzId   = findViewById(R.id.inKfzId);

        inLat.setText("48.137");
        inLng.setText("11.575");
        String posLat = sp.getString("pos_lat", null), posLng = sp.getString("pos_lng", null);
        if (posLat != null && posLng != null) { inLat.setText(posLat); inLng.setText(posLng); }
        inKennz.setText(sp.getString("kfz_plate", ""));
        inKfzId.setText(sp.getString("kfz_id", ""));
        inAppVer  = findViewById(R.id.inAppVer);
        btnVerList = findViewById(R.id.btnVerList);
        inAppVer.setText(sp.getString("appver", APPVER_DEF));

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(WebSettings.getDefaultUserAgent(this).replace("; wv", ""));
        web.setWebViewClient(new LoginClient(this));

        click(R.id.btnInfo,    this::stepInfo);
        click(R.id.btnLogin,   this::stepLogin);
        click(R.id.btnStatus,  this::stepStatus);
        click(R.id.btnVehicles,this::stepVehicles);
        click(R.id.btnTarif,   this::stepTarif);
        click(R.id.btnStart,   this::stepStart);
        click(R.id.btnStop,    this::stepStop);
        click(R.id.btnLogout,  this::stepLogout);
        click(R.id.btnGps,     this::stepGps);
        click(R.id.btnVerList, this::showVersions);
        click(R.id.btnClearKfz, () -> {
            inKennz.setText(""); inKfzId.setText("");
            sp.edit().remove("kfz_plate").remove("kfz_id").apply();
            log("(kfz fields cleared — start will omit the vehicle)");
        });
        click(R.id.btnClear,   () -> runOnUiThread(() -> out.setText("")));
        click(R.id.btnLoginDone, () -> runOnUiThread(this::hideLogin));
    }

    // remember the last used position (manual edit or GPS button) across restarts
    @Override
    protected void onPause() {
        super.onPause();
        SharedPreferences.Editor e = sp.edit();
        String la = inLat.getText().toString().trim(), ln = inLng.getText().toString().trim();
        if (!la.isEmpty() && !ln.isEmpty()) e.putString("pos_lat", la).putString("pos_lng", ln);
        else e.remove("pos_lat").remove("pos_lng");
        e.apply();
    }

    // ---------- tiny UI / plumbing helpers ----------

    private void click(int id, Runnable r) {
        ((Button) findViewById(id)).setOnClickListener(v -> {
            try { r.run(); } catch (Exception e) { log("!! " + e); }
        });
    }

    private void log(String s) {
        runOnUiThread(() -> {
            out.append(s + "\n");
            outScroll.post(() -> outScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    interface Try { void run() throws Exception; }

    private void step(String title, Try r) {
        log("\n=== " + title + " ===");
        net.execute(() -> {
            try { r.run(); } catch (Exception e) { log("!! " + e); }
        });
    }

    private void show(int code, String body) {
        log("HTTP " + code + "\n" + pretty(body));
    }

    private String pretty(String s) {
        try { JSONObject o = new JSONObject(s); markDates(o); return o.toString(2); } catch (JSONException e) {
            try { JSONArray a = new JSONArray(s); markDates(a); return a.toString(2); } catch (JSONException e2) { return s; }
        }
    }

    // Timestamps (ParkscheinDTO.start/end, ...) arrive as 13-digit epoch-ms numbers.
    // Render them as "<ms> = <local datetime>" in the log. Duration/cent values
    // (beginAbrechnungInMs, priceInCents, ids) are far smaller and stay untouched.
    private static final long EPOCH_MS_MIN = 1_000_000_000_000L; // 2001-09-09
    private static final long EPOCH_MS_MAX = 4_102_444_800_000L; // 2100-01-01
    // parkingTimeInterval arrives as an epoch-ms pair string, e.g. "1794734230000-1794734290000"
    private static final java.util.regex.Pattern MS_RANGE = java.util.regex.Pattern.compile("^\\d{13}-\\d{13}$");

    private static String msRange(String s) {
        if (!MS_RANGE.matcher(s).matches()) return null;
        String[] pr = s.split("-");
        try {
            long a = Long.parseLong(pr[0]), b = Long.parseLong(pr[1]);
            if (a < EPOCH_MS_MIN || a > EPOCH_MS_MAX || b < EPOCH_MS_MIN || b > EPOCH_MS_MAX) return null;
            return s + "  =  " + fmtMs(a) + " .. " + fmtMs(b);
        } catch (NumberFormatException e) { return null; }
    }

    private static void markDates(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            List<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = o.opt(k);
                if (isEpochMs(v)) { try { o.put(k, v + "  =  " + fmtMs(((Number) v).longValue())); } catch (JSONException ignore) {} }
                else if (v instanceof String) {
                    String r = msRange((String) v);
                    if (r != null) { try { o.put(k, r); } catch (JSONException ignore) {} }
                    else markDates(v);
                }
                else markDates(v);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (isEpochMs(v)) { try { a.put(i, v + "  =  " + fmtMs(((Number) v).longValue())); } catch (JSONException ignore) {} }
                else if (v instanceof String) {
                    String r = msRange((String) v);
                    if (r != null) { try { a.put(i, r); } catch (JSONException ignore) {} }
                    else markDates(v);
                } else markDates(v);
            }
        }
    }

    private static boolean isEpochMs(Object v) {
        if (!(v instanceof Number) || v instanceof Double || v instanceof Float) return false;
        long l = ((Number) v).longValue();
        return l >= EPOCH_MS_MIN && l <= EPOCH_MS_MAX;
    }

    private static String fmtMs(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new java.util.Date(ms));
    }

    private double lat() { return parse(inLat.getText().toString(), 48.137); }
    private double lng() { return parse(inLng.getText().toString(), 11.575); }
    private static double parse(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private boolean haveSession() {
        String s = sp.getString("session", "");
        if (s == null || s.isEmpty()) { log("(no session yet — press 'login' first; sending anyway)"); }
        return s != null && !s.isEmpty();
    }

    // ---------- app version code reported to the backend ----------

    private String appVer() {
        String v = inAppVer.getText().toString().trim();
        if (v.isEmpty()) v = APPVER_DEF;
        sp.edit().putString("appver", v).apply();
        return v;
    }

    private void showVersions() {
        if (inAppVer.getText().length() == 0) inAppVer.setText(APPVER_DEF);
        PopupMenu pm = new PopupMenu(this, btnVerList);
        int id = 1;
        for (String[] v : VERSIONS) pm.getMenu().add(0, id++, 0, v[1] + "  (" + v[0] + ")");
        pm.setOnMenuItemClickListener(item -> {
            String code = item.getTitle().toString().trim().split("\\s")[0];
            inAppVer.setText(code);
            sp.edit().putString("appver", code).apply();
            log("(version code set to " + code + " — used by v2/info and preauthorize)");
            return true;
        });
        pm.show();
    }

    // ---------- HTTP ----------

    private String[] api(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("swm_hp_apikey", APIKEY);
        c.setRequestProperty("Content-Type", "application/json");
        String session = sp.getString("session", null);
        if (session != null && !session.isEmpty()) c.setRequestProperty("swm_hp_authorization", session);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        return new String[]{String.valueOf(code), readAll(in)};
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------- 1: info (anonymous) ----------

    private void stepInfo() {
        final String v = appVer();
        step("GET v2/info (reporting version " + v + ")", () -> {
            String[] r = api("GET", "/v2/info?os=ANDROID&version=" + v, null);
            show(Integer.parseInt(r[0]), r[1]);
        });
    }

    // ---------- 2: M-Login in embedded WebView ----------

    private void stepLogin() {
        try {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            pkceVerifier = b64url(rnd);
            byte[] h = MessageDigest.getInstance("SHA-256").digest(pkceVerifier.getBytes(StandardCharsets.UTF_8));
            String challenge = b64url(h);
            byte[] s = new byte[16];
            new SecureRandom().nextBytes(s);
            oauthState = b64url(s);
            String url = MLOGIN + "/oauth2/authorize"
                    + "?client_id=" + enc(CLIENT)
                    + "&redirect_uri=" + enc(REDIRECT)
                    + "&response_type=code"
                    + "&scope=openid"
                    + "&code_challenge=" + challenge
                    + "&code_challenge_method=S256"
                    + "&state=" + oauthState
                    + "&locale=de"
                    + "&login_action=widen_scope";
            log("\n=== login (in-app WebView) ===\nauthorize:\n" + url);
            showLogin();
            web.loadUrl(url);
        } catch (Exception e) { log("!! " + e); }
    }

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private void showLogin() {
        loginBar.setVisibility(View.VISIBLE);
        web.setVisibility(View.VISIBLE);
        ((LinearLayout.LayoutParams) web.getLayoutParams()).weight = 1f;
        web.setLayoutParams(web.getLayoutParams());
    }

    private void hideLogin() {
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        ((LinearLayout.LayoutParams) web.getLayoutParams()).weight = 0f;
        web.setLayoutParams(web.getLayoutParams());
        loginBar.setVisibility(View.GONE);
    }

    private boolean isRedirect(Uri u) {
        return u != null && u.toString().startsWith(REDIRECT);
    }

    private void catchRedirect(Uri u) {
        runOnUiThread(() -> {
            log("redirect intercepted: " + u);
            String st = u.getQueryParameter("state");
            if (oauthState != null && !oauthState.equals(st)) log("!! state mismatch, expected " + oauthState);
            String code = u.getQueryParameter("code");
            hideLogin();
            finishLogin(code);
        });
    }

    private void finishLogin(String code) {
        if (code == null || code.isEmpty()) { log("!! no code= in redirect"); return; }
        log("authcode received (length " + code.length() + "), exchanging at backend...");
        step("POST v3/user/login", () -> {
            JSONObject body = new JSONObject();
            body.put("authcode", code);
            body.put("verificationCode", pkceVerifier);
            String[] r = api("POST", "/v3/user/login", body.toString());
            if (r[0].equals("200")) {
                JSONObject o = new JSONObject(r[1]);
                sp.edit().putString("session", o.getString("session")).apply();
                String sess = o.optString("session", "");
                o.put("session", sess.length() > 12
                        ? sess.substring(0, 12) + "... (" + sess.length() + " chars, stored in app)"
                        : "(stored in app)");
                log("login OK, session stored.\nHTTP 200\n" + o.toString(2));
            } else {
                show(Integer.parseInt(r[0]), r[1]);
            }
        });
    }

    // ---------- 3/4: status, vehicles ----------

    private void stepStatus() {
        haveSession();
        step("GET v2/parkschein/active", () -> {
            String[] r = api("GET", "/v2/parkschein/active", null);
            show(Integer.parseInt(r[0]), r[1]);
        });
    }

    private void stepVehicles() {
        haveSession();
        step("GET v3/kfz", () -> {
            String[] r = api("GET", "/v3/kfz", null);
            show(Integer.parseInt(r[0]), r[1]);
            JSONArray arr = parseVehicles(r[1]);
            if (arr == null) { log("(no vehicle array found in the response — see JSON above)"); return; }
            if (arr.length() == 1) {
                fillVehicle(arr.getJSONObject(0), "only vehicle in response");
            } else if (arr.length() > 1) {
                JSONObject std = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    log("  vehicle: id=" + o.opt("id") + "  kennzeichen=" + o.optString("kennzeichen")
                            + (o.optBoolean("standard") ? "  (standard)" : ""));
                    if (o.optBoolean("standard")) std = o;
                }
                if (std != null) fillVehicle(std, "vehicle marked standard=true");
                else log("(" + arr.length() + " vehicles, none marked standard — copy one id+kennzeichen into the fields)");
            } else {
                log("(response contains 0 vehicles)");
            }
        });
    }

    // The backend wraps the list: {"all":[ {id,kennzeichen,...}, ... ]}.
    // Accept a bare array or another array-valued wrapper key too, just in case.
    private static JSONArray parseVehicles(String body) {
        try { return new JSONArray(body); } catch (JSONException ignore) { }
        try {
            JSONObject o = new JSONObject(body);
            JSONArray a = o.optJSONArray("all");
            if (a != null) return a;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                JSONArray a2 = o.optJSONArray(it.next());
                if (a2 != null) return a2;
            }
        } catch (JSONException ignore) { }
        return null;
    }

    private void fillVehicle(JSONObject o, String why) {
        String kennz = o.optString("kennzeichen", "");
        long id = o.optLong("id", -1);
        if (kennz.isEmpty() || id < 0) { log("(vehicle object lacks kennzeichen or numeric id — not auto-filling)"); return; }
        runOnUiThread(() -> { inKennz.setText(kennz); inKfzId.setText(String.valueOf(id)); });
        sp.edit().putString("kfz_plate", kennz).putString("kfz_id", String.valueOf(id)).apply();
        log("(" + why + ": auto-filled fields — Kfz-ID=" + id + ", kennzeichen=" + kennz + "; persisted)");
    }

    // ---------- tarif (same resolve 'start' uses, just shown on its own) ----------

    private void stepTarif() {
        haveSession();
        step("GET v5/tarif/resolve", () -> {
            String[] r = tarifCall();
            show(Integer.parseInt(r[0]), r[1]);
        });
    }

    private String[] tarifCall() throws IOException {
        return api("GET", "/v5/tarif/resolve?isOffenerParkschein=true&parkDauerInMin=&userLat="
                + lat() + "&userLng=" + lng() + "&eAuto=false&withShortTarifInfo=false&withLongTarifInfo=true", null);
    }

    // ---------- 5: start = tarif resolve + preauthorize + (confirm after prompt) ----------

    private void stepStart() {
        haveSession();
        step("start: GET v5/tarif/resolve + POST v5/purchase/preauthorize", () -> {
            String[] t = tarifCall();
            log("tarif resolve: HTTP " + t[0] + "\n" + pretty(t[1]));
            // bail out early when there is nothing to buy here (and only forward a REAL parkingTimeInterval)
            String tic = "";
            try {
                JSONObject tj = new JSONObject(t[1]);
                String res = tj.optString("resolveResult", "");
                Object to = tj.opt("parkingTimeInterval");
                tic = (to instanceof String) ? (String) to : "";
                if (!"RESOLVED".equals(res) || tic.isEmpty()) {
                    String why = "KEIN_TARIFGEBIET".equals(res) ? "this position is not inside a tariff area — nothing to buy here"
                             : "KEINE_VERKAUFSZEIT".equals(res) ? "outside selling hours at this position"
                             : "no bookable window at this position";
                    log("START ABORTED (resolveResult=" + res + "): " + why + " — preauthorize NOT sent");
                    long nvt = tj.optLong("naechsteVerkaufszeit", 0);
                    if (nvt > 0) log("next selling time: " + fmtMs(nvt));
                    return;
                }
                // A Flex resolve always answers with a degenerate ~1-minute interval: an
                // open ticket has no fixed end, so that interval is only a placeholder for
                // the minimum duration. It must NOT be echoed back into the purchase —
                // that is what produced the fixed 1-minute ticket.
                if (MS_RANGE.matcher(tic).matches()) {
                    String[] se = tic.split("-");
                    long spanMin = (Long.parseLong(se[1]) - Long.parseLong(se[0])) / 60000L;
                    if (spanMin <= 2) {
                        log("note: resolve offers only a " + spanMin + "-min window here — normal Flex placeholder.");
                        long ab = tj.optLong("abrechnungsStart", 0);
                        if (ab > 0) log("      first billing time of this tariff: " + fmtMs(ab));
                        log("      purchase sends openTicket=true without parkingTimeInterval,");
                        log("      so the ticket stays open (end = null) until it is stopped.");
                    }
                }
            } catch (JSONException e) { log("START ABORTED: tarif resolve is not a JSON object"); return; }

            String installation = sp.getString("installation_id", null);
            if (installation == null) {
                installation = UUID.randomUUID().toString();
                sp.edit().putString("installation_id", installation).apply();
            }

            JSONObject body = new JSONObject();
            // wire name is "openTicket": the backend DTO field is isOpenTicket, but Jackson maps the
            // boolean is-getter to "openTicket" (also the key the response answers with). "isOpenTicket"
            // is silently ignored -> the server sells a FIXED ticket that needs an end time.
            body.put("openTicket", true);
            body.put("userLat", lat());
            body.put("userLng", lng());
            body.put("paymentType", "MLOGIN");
            JSONObject vi = new JSONObject();
            vi.put("appVersion", appVer());
            vi.put("operatingSystem", "ANDROID");
            body.put("versionInfoDTO", vi);
            JSONObject ai = new JSONObject();
            ai.put("appInstallationId", installation);
            ai.put("operatingSystem", "ANDROID");
            ai.put("sprache", "de");
            body.put("appInstallation", ai);
            // NO parkingTimeInterval for an open ticket: the server then answers with
            // ticketEndInMs = null (open end, billed per minute, price only on stop).
            // Sending the resolve interval — even together with a correct openTicket flag —
            // used to pin the ticket to that 1-minute window; leaving openTicket out at all
            // is what made the server fail with 401 PURCHASE_SECURITY_EXCEPTION.
            String kennz = inKennz.getText().toString().trim();
            String kfzId = inKfzId.getText().toString().trim();
            if (!kennz.isEmpty() && !kfzId.isEmpty()) {
                // same shape as the real app's KfzDTO / flexparken.sh: {id, kennzeichen, standard, elektroAuto}
                JSONObject kfz = new JSONObject();
                kfz.put("id", Long.parseLong(kfzId));
                kfz.put("kennzeichen", kennz);
                kfz.put("standard", true);
                kfz.put("elektroAuto", false);
                body.put("kfz", kfz);
            } else {
                // server-side rule: kfz is a Pflichtfeld (VALIDATION_ERROR errors.kfz)
                log("(no vehicle set — the server requires one: run step 4 kfz or fill both fields)");
                body.put("kfz", JSONObject.NULL);
            }

            log("preauthorize body:\n" + body.toString(2));
            String[] r = api("POST", "/v5/purchase/preauthorize", body.toString());
            if (!r[0].equals("200")) { show(Integer.parseInt(r[0]), r[1]); return; }
            final JSONObject pre = new JSONObject(r[1]);
            log("preauthorize: HTTP 200\n" + pre.toString(2));
            final String payload = pre.optString("payload", "");
            final String price   = pre.optString("formattedPrice", pre.optString("priceEUR", "?"));
            final String uuid    = pre.optString("ticketUUID", "?");
            runOnUiThread(() -> confirmPurchase(payload, price, uuid));
        });
    }

    private void confirmPurchase(final String payload, String price, String uuid) {
        if (payload.isEmpty()) { log("!! preauthorize returned no payload — cannot confirm"); return; }
        new AlertDialog.Builder(this)
                .setTitle("FlexParken starten?")
                .setMessage("Price: " + price + "\nTicket: " + uuid
                        + "\nPayment: M-Login (MLOGIN)\n\nPOST v5/purchase/confirm?")
                .setPositiveButton("Buy", (d, w) -> step("POST v5/purchase/confirm", () -> {
                    JSONObject c = new JSONObject();
                    c.put("encryptedPayload", payload);
                    String[] r = api("POST", "/v5/purchase/confirm", c.toString());
                    show(Integer.parseInt(r[0]), r[1]);
                }))
                .setNegativeButton("cancel", null)
                .show();
    }

    // ---------- 6: stop ----------

    private void stepStop() {
        haveSession();
        step("POST v2/parkschein/active/stop", () -> {
            String[] r = api("POST", "/v2/parkschein/active/stop", "{}");
            show(Integer.parseInt(r[0]), r[1]);
        });
    }

    // ---------- GPS position ----------

    private void stepGps() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 42);
            log("(location permission requested — press GPS again after granting)");
            return;
        }
        doGps();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == 42 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) doGps();
    }

    private void doGps() {
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { log("!! no LocationManager on this device"); return; }
        List<String> providers = lm.getProviders(true);
        if (providers.isEmpty()) { log("!! all location providers are off — enable location in system settings"); return; }

        Location best = null;
        for (String p : providers) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        final Location lastFix = best;
        long age = lastFix == null ? Long.MAX_VALUE : System.currentTimeMillis() - lastFix.getTime();
        if (lastFix != null && age < 60000) { fillLocation(lastFix, "last fix, " + (age / 1000) + "s old"); return; }

        Criteria crit = new Criteria();
        crit.setAccuracy(Criteria.ACCURACY_FINE);
        crit.setPowerRequirement(Criteria.POWER_LOW);
        try {
            activeGps = new GpsListener(this);
            gpsPending = true;
            lm.requestSingleUpdate(crit, activeGps, null);
            log("(GPS: single fix requested on " + providers + (lastFix != null ? ", last fix " + (age / 1000) + "s old" : "") + ", waiting up to 20s...)");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!gpsPending) return;
                gpsPending = false;
                try { if (activeGps != null) lm.removeUpdates(activeGps); } catch (SecurityException ignore) {}
                if (lastFix != null) fillLocation(lastFix, "stale last fix after GPS timeout");
                else log("!! GPS timeout, no fix — edit lat/lng manually");
            }, 20000);
        } catch (SecurityException e) { log("!! location permission missing: " + e); }
    }

    private void fillLocation(Location l, String why) {
        gpsPending = false;
        final String latS = String.format(Locale.US, "%.6f", l.getLatitude());
        final String lngS = String.format(Locale.US, "%.6f", l.getLongitude());
        runOnUiThread(() -> { inLat.setText(latS); inLng.setText(lngS); });
        log("(position via GPS [" + why + ", provider " + l.getProvider() + ", acc ~" + (int) l.getAccuracy()
                + "m]: " + latS + ", " + lngS + ")");
    }

    // ---------- logout ----------

    private void stepLogout() {
        // The logout request has to carry the session header, so the stored token must still
        // be there when api() builds the request. step() runs on the net executor and returns
        // at once — removing the session here (as before) raced ahead of the request and the
        // call went out without swm_hp_authorization => 403 SECURITY_EXCEPTION.
        step("POST v3/user/logout", () -> {
            String session = sp.getString("session", "");
            if (session == null || session.isEmpty()) {
                log("(no session stored — nothing to log out. The endpoint answers 403"
                    + " SECURITY_EXCEPTION / NOT_APPLICABLE without a session, exactly like step 3.)");
                return;
            }
            String[] r = api("POST", "/v3/user/logout", "{}");
            show(Integer.parseInt(r[0]), r[1]);
            if (r[0].equals("200")) {
                sp.edit().remove("session").apply();
                log("(session ended on the server and removed from app storage)");
            } else if (r[0].equals("403")) {
                sp.edit().remove("session").apply();
                log("(the backend does not know this session — already logged out or expired;"
                    + " local copy removed. Use 'login' for a fresh one.)");
            } else {
                log("(session KEPT in app storage — logout failed with HTTP " + r[0] + ")");
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE) { hideLogin(); return; }
        super.onBackPressed();
    }

    // Named class instead of anonymous: JDK21 javac + this d8 combo NPEs on anonymous classes.
    static class LoginClient extends WebViewClient {
        private final MainActivity owner;
        LoginClient(MainActivity o) { owner = o; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (owner.isRedirect(u)) { owner.catchRedirect(u); return true; }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (owner.isRedirect(u) && req.isForMainFrame()) {
                owner.catchRedirect(u);
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream("redirect caught".getBytes(StandardCharsets.UTF_8)));
            }
            return null;
        }

        @Override
        public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
            owner.log("(web error " + code + " on " + failingUrl + ")");
        }
    }

    // Named class again (d8 + anonymous classes = NPE, see README).
    static class GpsListener implements LocationListener {
        private final MainActivity owner;
        GpsListener(MainActivity o) { owner = o; }

        @Override
        public void onLocationChanged(Location loc) {
            LocationManager lm = (LocationManager) owner.getSystemService(LOCATION_SERVICE);
            try { if (lm != null) lm.removeUpdates(this); } catch (SecurityException ignore) {}
            owner.activeGps = null;
            owner.fillLocation(loc, "fresh fix");
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    }
}

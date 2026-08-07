package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.annotation.TargetApi;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Fullscreen WebView kiosk — loads room display via Cloudflare Worker.
 * tabletkey URL param tells index.html to use Worker proxy (no MSAL).
 * webview=1 param tells index.html to skip the tap overlay.
 *
 * FortiGate SSL inspection (rk3288/Android 10 on office LAN) is handled
 * at the network layer — Cloudflare IP ranges are whitelisted in FortiGate
 * policy per tablet IP. No app-level bypass needed.
 *
 * interceptNavigation() is kept as a safety net: if FortiGate ever redirects
 * (rule expired, IP changed), it catches the redirect, persists tabletKey in
 * localStorage, and reloads the Worker page instead of showing a blank screen.
 *
 * Android version compatibility:
 *   API 19  (Android 4.4 / LG tablets) : legacy SystemUI flags
 *   API 29+ (Android 10 / Lenovo)      : WindowInsetsController for immersive
 */
public class KioskWebViewActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String TABLET_KEY = "RIS-TABLET-KEY2026";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    // Set true while this activity is in the foreground — read by ScheduleReceiver on the same process
    // to skip health-check startActivity() when the app is already visible.
    static volatile boolean sIsVisible = false;

    private WebView webView;
    private volatile boolean enforceOneApp = false;

    // ── Watchdog — two layers ──────────────────────────────────────────────────
    // Layer 1 (load): armed on loadDisplay(), disarmed on onPageFinished.
    //   If page never finishes loading within 5 min, force reload.
    // Layer 2 (runtime): JS calls Android.ping() every 3 min.
    //   If APK gets no ping for 10 min (WebView OOM-crashed), force reload.
    private final Handler _wdHandler = new Handler();
    private Runnable _loadWatchdog = null;
    private Runnable _pingWatchdog = null;
    private volatile long _lastPingMs = 0;
    private static final long LOAD_WATCHDOG_MS    =  5 * 60 * 1000; // 5 min
    private static final long PING_TIMEOUT_MS    = 10 * 60 * 1000; // 10 min
    private static final long PING_CHECK_MS      =  5 * 60 * 1000; // 5 min
    private static final long WATCHDOG_COOLDOWN_MS = 20 * 60 * 1000; // min gap between watchdog reloads
    private volatile long _lastWatchdogReloadMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyRotation();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        enforceOneApp = prefs.getBoolean("enforce_one_app", false);

        // Re-register standby/wake alarms on every launch — ensures they survive APK updates
        // (BootReceiver only fires on full device reboot, not on APK update relaunch)
        ScheduleReceiver.schedule(this);

        webView = new WebView(this);
        setContentView(webView);
        hideSystemUI();
        setupWebView();
        loadDisplay();
    }

    private void applyRotation() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean rotated = prefs.getBoolean("screen_rotated", false);
        setRequestedOrientation(rotated
            ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void loadDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName  = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());
        url.append("&tabletkey=").append(Uri.encode(TABLET_KEY));
        url.append("&webview=1");
        if (roomEmail.length() > 0)
            url.append("&room=").append(Uri.encode(roomEmail));
        if (roomName.length() > 0)
            url.append("&roomname=").append(Uri.encode(roomName));
        try {
            String apkVersion = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
            url.append("&apkversion=").append(Uri.encode(apkVersion));
        } catch (Exception e) { /* skip if unavailable */ }

        armLoadWatchdog();
        webView.loadUrl(url.toString());
    }

    private void armLoadWatchdog() {
        _lastPingMs = System.currentTimeMillis(); // also resets runtime watchdog
        if (_loadWatchdog != null) _wdHandler.removeCallbacks(_loadWatchdog);
        _loadWatchdog = new Runnable() {
            @Override public void run() {
                if (webView != null) loadDisplay();
            }
        };
        _wdHandler.postDelayed(_loadWatchdog, LOAD_WATCHDOG_MS);
    }

    private void disarmLoadWatchdog() {
        if (_loadWatchdog != null) { _wdHandler.removeCallbacks(_loadWatchdog); _loadWatchdog = null; }
    }

    private void startPingWatchdog() {
        _lastPingMs = System.currentTimeMillis();
        if (_pingWatchdog != null) _wdHandler.removeCallbacks(_pingWatchdog);
        _pingWatchdog = new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                if (System.currentTimeMillis() - _lastPingMs > PING_TIMEOUT_MS) {
                    long now = System.currentTimeMillis();
                    if (now - _lastWatchdogReloadMs < WATCHDOG_COOLDOWN_MS) {
                        // Reloaded recently — pings still absent; wait before trying again
                        _wdHandler.postDelayed(this, PING_CHECK_MS);
                    } else {
                        _lastWatchdogReloadMs = now;
                        loadDisplay(); // JS stopped pinging — WebView likely frozen
                    }
                } else {
                    _wdHandler.postDelayed(this, PING_CHECK_MS);
                }
            }
        };
        _wdHandler.postDelayed(_pingWatchdog, PING_CHECK_MS);
    }

    // Safety net: catches any non-Worker navigation (FortiGate redirect, stray MSAL, etc.).
    // Persists tabletKey into localStorage while the Worker page context is still active,
    // then reloads. On reload tabletKey is found and launch() runs instead of initMsal().
    private boolean interceptNavigation(final WebView view, String url) {
        if (url.startsWith("about:") || url.contains("ris-display.workers.dev")) {
            return false;
        }
        view.evaluateJavascript(
            "(function(){try{" +
            "var k='roomdisplay_v5';" +
            "var c=JSON.parse(localStorage.getItem(k)||'{}');" +
            "c.tabletKey='" + TABLET_KEY + "';" +
            "localStorage.setItem(k,JSON.stringify(c));" +
            "}catch(e){}})();",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    loadDisplay();
                }
            }
        );
        return true;
    }

    private void setupWebView() {
        webView.addJavascriptInterface(new KioskInterface(), "Android");
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.setWebViewClient(new WebViewClient() {

            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return interceptNavigation(view, req.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return interceptNavigation(view, url);
            }

            // rk3288 tablets have an outdated system CA store that may not trust
            // Cloudflare's cert. Safe to proceed — kiosk on internal LAN only.
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                disarmLoadWatchdog();
                startPingWatchdog();
            }

            // Network/load error recovery — retry after 5 s so the kiosk self-heals
            // from FortiGate redirects, transient DNS failures, or SSL interception
            // that slipped past onReceivedSslError. The delay prevents tight retry loops.
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                new Handler().postDelayed(new Runnable() {
                    @Override public void run() {
                        if (webView != null) loadDisplay();
                    }
                }, 5000);
            }
        });
    }

    @TargetApi(30)
    private void hideSystemUIModern() {
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            ctrl.hide(WindowInsets.Type.systemBars());
            ctrl.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= 30) {
            hideSystemUIModern();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                            hideSystemUI();
                        }
                    }
                }
            );
        }
    }

    @Override
    public void onBackPressed() {
        // Block back button in kiosk mode
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!enforceOneApp || isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: launch synchronously while still foreground — background start
            // restriction silently drops startActivity() from onStop().
            Intent relaunch = new Intent(this, KioskWebViewActivity.class);
            relaunch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(relaunch);
        } else {
            // Android 4.4: delay 400ms so SystemUI finishes its transition first —
            // launching immediately races with SystemUI and can crash it.
            new Handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (!enforceOneApp || isFinishing()) return;
                    Intent relaunch = new Intent(KioskWebViewActivity.this, KioskWebViewActivity.class);
                    relaunch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(relaunch);
                }
            }, 400);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // keep getIntent() current for health-check detection in onResume()
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    // JavaScript interface — called from web config screen
    public class KioskInterface {
        @JavascriptInterface
        public boolean getEnforceOneApp() {
            return enforceOneApp;
        }

        @JavascriptInterface
        public void setEnforceOneApp(boolean enabled) {
            enforceOneApp = enabled;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean("enforce_one_app", enabled).apply();
        }

        // Called after web-side PIN verification — just finishes the activity
        @JavascriptInterface
        public void ping() {
            _lastPingMs = System.currentTimeMillis();
        }

        @JavascriptInterface
        public void exitKiosk() {
            enforceOneApp = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean("enforce_one_app", false).apply();
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        _lastPingMs = System.currentTimeMillis();
        _lastWatchdogReloadMs = 0; // reset cooldown — fresh start after standby
        startPingWatchdog(); // restart watchdog suspended in onPause
        hideSystemUI();
        // Health-check recovery: ScheduleReceiver fires every 10 min during business hours
        // and relaunches this activity with health_check=true if it was not foreground.
        // onNewIntent() keeps getIntent() current so this extra is always from the latest launch.
        sIsVisible = true;
        android.content.SharedPreferences _rp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (getIntent() != null && getIntent().getBooleanExtra("health_check", false)) {
            getIntent().removeExtra("health_check");
            if (!isInStandbyWindow()) {
                long _bgAt = _rp.getLong("bg_at", 0L);
                long _hiddenMs = _bgAt > 0L ? System.currentTimeMillis() - _bgAt : 0L;
                _rp.edit().remove("bg_at").apply();
                logHealthCheckRecovered(_hiddenMs);
            }
        } else {
            _rp.edit().remove("bg_at").apply();
        }
        // If we're inside the standby window (20:30–06:00 BKK), StandbyActivity should be
        // covering us. Reaching onResume() here means it crashed or never launched.
        // Retry up to 3 times with a 5-min cooldown; file a standby_failure incident when exhausted.
        if (isInStandbyWindow()) {
            int count      = getStandbyRetryCount();
            long lastMs    = getStandbyRetryLastMs();
            long nowMs     = System.currentTimeMillis();
            long COOLDOWN  = 5L * 60L * 1000L;
            if (count < 3 && (nowMs - lastMs) > COOLDOWN) {
                incrementStandbyRetryCount();
                logStandbyRetry(getStandbyRetryCount());
                ScheduleReceiver.launchStandby(this);
            } else if (count >= 3 && !standbyIncidentFiled()) {
                markStandbyIncidentFiled();
                fileStandbyFailureIncident(count);
            }
        }
    }

    // ── Standby retry helpers ─────────────────────────────────────────────────

    private String todayBkk() {
        java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        return sdf.format(new java.util.Date());
    }

    private boolean isInStandbyWindow() {
        java.util.Calendar bkk = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        int h = bkk.get(java.util.Calendar.HOUR_OF_DAY);
        int m = bkk.get(java.util.Calendar.MINUTE);
        return (h > 20 || (h == 20 && m >= 30) || h < 6);
    }

    private android.content.SharedPreferences standbyRetryPrefs() {
        return getSharedPreferences("standby_retry", MODE_PRIVATE);
    }

    private int getStandbyRetryCount() {
        return standbyRetryPrefs().getInt("count_" + todayBkk(), 0);
    }

    private long getStandbyRetryLastMs() {
        return standbyRetryPrefs().getLong("last_ms", 0L);
    }

    private boolean standbyIncidentFiled() {
        return standbyRetryPrefs().getBoolean("filed_" + todayBkk(), false);
    }

    private void incrementStandbyRetryCount() {
        standbyRetryPrefs().edit()
            .putInt("count_" + todayBkk(), getStandbyRetryCount() + 1)
            .putLong("last_ms", System.currentTimeMillis())
            .apply();
    }

    private void markStandbyIncidentFiled() {
        standbyRetryPrefs().edit()
            .putBoolean("filed_" + todayBkk(), true)
            .apply();
    }

    /** Fire-and-forget HTTP POST on a background thread. Never throws. */
    private void postJsonFire(final String url, final String json) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.URL u = new java.net.URL(url);
                    java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setDoOutput(true);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    byte[] bytes = json.getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(bytes.length);
                    c.getOutputStream().write(bytes);
                    c.getInputStream().close();
                    c.disconnect();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void logHealthCheckRecovered(long hiddenMs) {
        android.content.SharedPreferences p =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String room     = p.getString("room_email", "");
        String roomname = p.getString("room_name", "");
        String apkVer;
        try {
            apkVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { apkVer = ""; }
        String payload = "{\"room\":\"" + room + "\","
            + "\"roomname\":\"" + roomname + "\","
            + "\"event\":\"app_health_check_recovered\","
            + "\"hiddenMinutes\":" + (hiddenMs / 60000) + ","
            + "\"apkVersion\":\"" + apkVer + "\"}";
        postJsonFire(BASE_URL + "api/alarm", payload);
    }

    private void logStandbyRetry(int attemptNumber) {
        android.content.SharedPreferences p =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String room     = p.getString("room_email", "");
        String roomname = p.getString("room_name", "");
        String apkVer;
        try {
            apkVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { apkVer = ""; }
        String payload = "{\"room\":\"" + room + "\","
            + "\"roomname\":\"" + roomname + "\","
            + "\"event\":\"standby_retry\","
            + "\"apkVersion\":\"" + apkVer + "\"}";
        postJsonFire(BASE_URL + "api/alarm", payload);
    }

    private void fileStandbyFailureIncident(int retryCount) {
        android.content.SharedPreferences p =
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String room     = p.getString("room_email", "");
        String roomname = p.getString("room_name", "");
        String apkVer;
        try {
            apkVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { apkVer = ""; }
        java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        String nowBkk = fmt.format(new java.util.Date());
        String detail = "Standby retried " + retryCount + "/3, gave up at "
            + nowBkk + " BKK. Tablet staying online until 06:00 restart.";
        String payload = "{\"room\":\"" + room + "\","
            + "\"roomname\":\"" + roomname + "\","
            + "\"type\":\"standby_failure\","
            + "\"detail\":\"" + detail + "\","
            + "\"apkVersion\":\"" + apkVer + "\","
            + "\"reportedBy\":\"apk\"}";
        postJsonFire(BASE_URL + "api/incident", payload);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        // Suspend watchdogs during standby — JS timers pause, so pings stop; prevent overnight reload loop
        if (_loadWatchdog != null) _wdHandler.removeCallbacks(_loadWatchdog);
        if (_pingWatchdog != null) _wdHandler.removeCallbacks(_pingWatchdog);
        // Record pause time when NOT in standby window so onResume() can detect exit-to-home.
        // Don't set the flag during standby (20:30–06:00) — that's the normal overnight pause.
        sIsVisible = false;
        if (!isInStandbyWindow()) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putLong("bg_at", System.currentTimeMillis())
                .apply();
        }
    }

    @Override
    protected void onDestroy() {
        if (_loadWatchdog != null) _wdHandler.removeCallbacks(_loadWatchdog);
        if (_pingWatchdog != null) _wdHandler.removeCallbacks(_pingWatchdog);
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}

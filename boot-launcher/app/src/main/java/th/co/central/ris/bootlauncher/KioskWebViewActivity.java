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

    private WebView webView;
    private boolean enforceOneApp = false;

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
        // Android 10+: background activity start restrictions block relaunch from onStop().
        // onUserLeaveHint fires before onPause while still foreground — startActivity() allowed here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && enforceOneApp && !isFinishing()) {
            Intent relaunch = new Intent(this, KioskWebViewActivity.class);
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(relaunch);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Android 4.4 (API 19): enforce kiosk via onStop with a short delay so SystemUI
        // can finish its home/recents transition before we relaunch (avoids SystemUI crash).
        // Android 10+ uses onUserLeaveHint instead — background start restriction blocks this path.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && enforceOneApp && !isFinishing()) {
            new Handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (enforceOneApp && !isFinishing()) {
                        Intent relaunch = new Intent(KioskWebViewActivity.this, KioskWebViewActivity.class);
                        relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(relaunch);
                    }
                }
            }, 400);
        }
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        // Suspend watchdogs during standby — JS timers pause, so pings stop; prevent overnight reload loop
        if (_loadWatchdog != null) _wdHandler.removeCallbacks(_loadWatchdog);
        if (_pingWatchdog != null) _wdHandler.removeCallbacks(_pingWatchdog);
    }

    @Override
    protected void onDestroy() {
        if (_loadWatchdog != null) _wdHandler.removeCallbacks(_loadWatchdog);
        if (_pingWatchdog != null) _wdHandler.removeCallbacks(_pingWatchdog);
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}

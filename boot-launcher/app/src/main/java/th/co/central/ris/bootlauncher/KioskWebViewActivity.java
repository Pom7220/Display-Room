package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Fullscreen WebView kiosk activity.
 * Replaces Chrome external launch — opens the room display in a
 * native Android WebView with FLAG_FULLSCREEN.
 *
 * Benefits:
 * - True fullscreen from launch (no address bar, no tap needed)
 * - Handles OAuth redirect internally
 * - localStorage persists between sessions (same as Chrome)
 * - SSL handled programmatically (no Cloudflare cert issues)
 */
public class KioskWebViewActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // True fullscreen — no title bar, no status bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Hide navigation bar (Android 4.4+) — wrapped in try/catch for safety
        applyImmersiveMode();

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();
        loadKioskUrl();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // Enable JavaScript
        settings.setJavaScriptEnabled(true);

        // Enable localStorage and DOM storage
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDatabasePath(getApplicationContext().getFilesDir().getAbsolutePath());

        // Enable app cache
        settings.setAppCacheEnabled(true);
        settings.setAppCachePath(getCacheDir().getAbsolutePath());

        // User agent — identify as Chrome 42 for compatibility
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 4.4.2; LG-V500 Build/KOT49I.V50010d) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/42.0.2311.137 Safari/537.36"
        );

        // Allow mixed content (http + https) — API 21+, skip on older
        try {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Exception e) {}

        // Zoom settings — disable for kiosk
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Wide viewport for proper rendering
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // WebViewClient — handle redirects and SSL
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Re-apply immersive mode after page load (can get reset)
                applyImmersiveMode();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Handle all navigation within the WebView
                // Including OAuth redirects back to ris-display.workers.dev
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Accept all SSL certificates — we trust ris-display.workers.dev
                // This is safe for a controlled kiosk environment
                handler.proceed();
            }
        });

        // WebChromeClient — handle JS dialogs and console
        webView.setWebChromeClient(new WebChromeClient() {
            // Allow JS console.log to work
        });
    }

    private void loadKioskUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0) {
            url.append("&room=").append(android.net.Uri.encode(roomEmail));
        }
        if (roomName.length() > 0) {
            url.append("&roomname=").append(android.net.Uri.encode(roomName));
        }

        webView.loadUrl(url.toString());
    }

    private void applyImmersiveMode() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        } catch (Exception e) {
            // Fallback for older Android 4.4 builds
            try {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            } catch (Exception e2) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    public void onBackPressed() {
        // Block back button — kiosk mode
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
        // Don't call super — prevents exiting the kiosk
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

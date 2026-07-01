package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Fullscreen WebView kiosk with URL hash token injection.
 *
 * Token passing flow (Chrome 30 compatible):
 * 1. Fetch ROPC tokens from Worker /api/token
 * 2. Encode as base64 JSON in URL hash: #t={base64}
 * 3. index.html reads hash BEFORE MSAL runs
 * 4. Tokens stored in localStorage → MSAL finds account → no login
 * 5. Room config in URL params → no config screen
 * 6. Zero touch required
 */
public class KioskWebViewActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    private WebView webView;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        webView = new WebView(this);
        setContentView(webView);
        setupWebView();

        // Show loading screen
        webView.loadData(
            "<html><body style='background:#0a0a12;margin:0;display:table;width:100%;height:100%'>" +
            "<div style='display:table-cell;vertical-align:middle;text-align:center;color:#3b9eff;font-family:sans-serif'>" +
            "<div style='font-size:60px;font-weight:900'>RIS</div>" +
            "<div style='font-size:16px;margin-top:12px;color:#6b82a8'>Starting room display...</div>" +
            "</div></body></html>",
            "text/html", "utf-8");

        // Fetch tokens then load real page
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String adminKey = prefs.getString("admin_key", "");
        final String roomEmail = prefs.getString("room_email", "");
        final String roomName = prefs.getString("room_name", "");

        if (roomEmail.isEmpty()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final TokenFetcher.TokenResult tokens =
                    TokenFetcher.fetchTokens(adminKey);

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView == null) return;
                        loadWithTokens(roomEmail, roomName, tokens);
                    }
                });
            }
        }).start();
    }

    private void loadWithTokens(String roomEmail, String roomName,
                                 TokenFetcher.TokenResult tokens) {
        StringBuilder url = new StringBuilder(BASE_URL);

        // Room config as URL params
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0)
            url.append("&room=").append(Uri.encode(roomEmail));
        if (roomName.length() > 0)
            url.append("&roomname=").append(Uri.encode(roomName));

        // Encode tokens as base64 JSON in URL hash
        // index.html reads #t= BEFORE MSAL initializes
        if (tokens != null && tokens.isValid()) {
            try {
                StringBuilder json = new StringBuilder("{");
                json.append("\"a\":\"").append(tokens.accessToken).append("\"");
                json.append(",\"r\":\"").append(
                    tokens.refreshToken != null ? tokens.refreshToken : "").append("\"");
                json.append(",\"i\":\"").append(
                    tokens.idToken != null ? tokens.idToken : "").append("\"");
                json.append(",\"c\":\"").append(
                    tokens.clientId != null ? tokens.clientId : "").append("\"");
                json.append(",\"e\":").append(tokens.expiresIn);
                json.append("}");

                String b64 = Base64.encodeToString(
                    json.toString().getBytes("UTF-8"),
                    Base64.NO_WRAP | Base64.URL_SAFE);

                url.append("#t=").append(b64);
            } catch (Exception e) {
                // If encoding fails, load without tokens
                // User will see login screen as fallback
            }
        }

        webView.loadUrl(url.toString());
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep all navigation in WebView
                // Azure OAuth redirect will come back here
                // That's OK — after redirect, URL has #code= not #t=
                // MSAL will process the code, but we've already injected
                // tokens so it should find account and skip login
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view,
                    SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}

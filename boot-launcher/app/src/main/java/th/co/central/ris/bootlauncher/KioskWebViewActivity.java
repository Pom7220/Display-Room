package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Fullscreen WebView kiosk with ROPC token injection.
 *
 * On launch:
 * 1. Fetch tokens from Worker /api/token (ROPC) in background
 * 2. Open fullscreen WebView
 * 3. Inject tokens into localStorage before page finishes loading
 * 4. index.html finds tokens → skips sign-in → shows room display
 *
 * Zero touch required.
 */
public class KioskWebViewActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    private WebView webView;
    private TokenFetcher.TokenResult pendingTokens = null;
    private boolean tokensInjected = false;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();

        // Fetch tokens in background BEFORE loading the page
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String adminKey = prefs.getString("admin_key", "");

        new Thread(new Runnable() {
            @Override
            public void run() {
                TokenFetcher.TokenResult result = TokenFetcher.fetchTokens(adminKey);
                pendingTokens = result;

                // Load the page on UI thread after token fetch
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadKioskUrl();
                    }
                });
            }
        }).start();
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
            public void onPageFinished(WebView view, String url) {
                // Inject tokens into localStorage after page loads
                if (!tokensInjected && pendingTokens != null && pendingTokens.isValid()) {
                    injectTokens(view, pendingTokens);
                    tokensInjected = true;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Handle all navigation in WebView
                // On token injection, index.html will NOT redirect to Azure
                // because tokens are already present
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    private void injectTokens(WebView view, TokenFetcher.TokenResult tokens) {
        if (tokens.clientId == null || tokens.clientId.length() == 0) return;

        long expiresAt = System.currentTimeMillis() / 1000L + tokens.expiresIn;
        String prefix = "msal." + tokens.clientId + ".";

        // Build JavaScript to inject tokens into localStorage
        // This is exactly what inject_tokens command does in index.html
        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("try{");
        js.append("var p='").append(escapeJs(prefix)).append("';");
        js.append("localStorage.setItem(p+'access_token','").append(escapeJs(tokens.accessToken)).append("');");
        js.append("localStorage.setItem(p+'token_expires','").append(expiresAt).append("');");
        if (tokens.refreshToken != null && tokens.refreshToken.length() > 0) {
            js.append("localStorage.setItem(p+'refresh_token','").append(escapeJs(tokens.refreshToken)).append("');");
        }
        if (tokens.idToken != null && tokens.idToken.length() > 0) {
            js.append("localStorage.setItem(p+'id_token','").append(escapeJs(tokens.idToken)).append("');");
        }
        // Also update the roomdisplay_token cache used by getToken()
        js.append("localStorage.setItem('roomdisplay_token',JSON.stringify({");
        js.append("token:'").append(escapeJs(tokens.accessToken)).append("',");
        js.append("expiry:").append(expiresAt * 1000L);
        js.append("}));");
        js.append("}catch(e){}");
        // Trigger token refresh in the running app
        js.append("if(typeof _cachedToken!=='undefined'){");
        js.append("_cachedToken='").append(escapeJs(tokens.accessToken)).append("';");
        js.append("_tokenExpiry=").append(expiresAt * 1000L).append(";");
        js.append("}");
        // Re-fetch calendar with new token
        js.append("if(typeof fetchCal==='function')setTimeout(fetchCal,500);");
        js.append("})();");

        view.evaluateJavascript(js.toString(), null);
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void loadKioskUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0)
            url.append("&room=").append(android.net.Uri.encode(roomEmail));
        if (roomName.length() > 0)
            url.append("&roomname=").append(android.net.Uri.encode(roomName));

        webView.loadUrl(url.toString());
    }

    @Override
    public void onBackPressed() {
        // Block back — kiosk mode
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

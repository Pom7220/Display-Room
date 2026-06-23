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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // Inject Promise polyfill + tokens as early as possible
                // onPageStarted fires before page scripts execute
                injectPromisePolyfill(view);
                if (pendingTokens != null && pendingTokens.isValid()) {
                    injectTokens(view, pendingTokens);
                    tokensInjected = true;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Retry injection if not done yet (fallback)
                if (!tokensInjected && pendingTokens != null && pendingTokens.isValid()) {
                    injectPromisePolyfill(view);
                    injectTokens(view, pendingTokens);
                    tokensInjected = true;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
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

    private void injectPromisePolyfill(WebView view) {
        // Minimal Promise polyfill for Android 4.4 WebView (Chrome 30)
        String polyfill = "if(typeof Promise==='undefined'){" +
            "Promise=function(fn){" +
            "var callbacks=[];" +
            "var errbacks=[];" +
            "var state=0;" +
            "var val;" +
            "this.then=function(cb,eb){" +
            "if(state===1)setTimeout(function(){cb(val);},0);" +
            "else if(state===2&&eb)setTimeout(function(){eb(val);},0);" +
            "else{callbacks.push(cb);if(eb)errbacks.push(eb);}" +
            "return this;};" +
            "function resolve(v){state=1;val=v;callbacks.forEach(function(c){setTimeout(function(){c(v);},0);});}" +
            "function reject(v){state=2;val=v;errbacks.forEach(function(c){setTimeout(function(){c(v);},0);});}" +
            "try{fn(resolve,reject);}catch(e){reject(e);}" +
            "};" +
            "Promise.resolve=function(v){return new Promise(function(r){r(v);});};" +
            "Promise.reject=function(v){return new Promise(function(r,j){j(v);});};" +
            "Promise.all=function(arr){return new Promise(function(r,j){" +
            "var res=[];var count=0;" +
            "if(!arr.length){r(res);return;}" +
            "arr.forEach(function(p,i){Promise.resolve(p).then(function(v){res[i]=v;if(++count===arr.length)r(res);},j);});" +
            "});};" +
            "}";
        view.evaluateJavascript(polyfill, null);
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

    /**
     * JavaScript interface — allows index.html to call native methods.
     * Available as window.RISKiosk in JavaScript.
     * This works on ALL Android versions including 4.4 WebView (Chrome 30).
     */
    public class RISKioskBridge {
        private TokenFetcher.TokenResult tokens;

        public RISKioskBridge(TokenFetcher.TokenResult t) {
            tokens = t;
        }

        @android.webkit.JavascriptInterface
        public String getAccessToken() {
            return tokens != null && tokens.accessToken != null ? tokens.accessToken : "";
        }

        @android.webkit.JavascriptInterface
        public String getRefreshToken() {
            return tokens != null && tokens.refreshToken != null ? tokens.refreshToken : "";
        }

        @android.webkit.JavascriptInterface
        public String getIdToken() {
            return tokens != null && tokens.idToken != null ? tokens.idToken : "";
        }

        @android.webkit.JavascriptInterface
        public String getClientId() {
            return tokens != null && tokens.clientId != null ? tokens.clientId : "";
        }

        @android.webkit.JavascriptInterface
        public int getExpiresIn() {
            return tokens != null ? tokens.expiresIn : 3600;
        }

        @android.webkit.JavascriptInterface
        public boolean hasTokens() {
            return tokens != null && tokens.isValid();
        }
    }
}

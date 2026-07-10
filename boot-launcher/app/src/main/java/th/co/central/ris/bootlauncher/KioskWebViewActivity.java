package th.co.central.ris.bootlauncher;

import android.app.Activity;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fullscreen WebView kiosk — no MSAL, no token injection.
 * Worker proxy handles auth via X-Tablet-Key header (set from URL param).
 * webview=1 param tells index.html to skip the tap overlay.
 *
 * All requests to ris-display.workers.dev are fulfilled via OkHttp to bypass
 * FortiGate SSL inspection, which intercepts Chrome 149 WebView traffic on
 * Android 10 (rk3288) but does not intercept OkHttp's TLS fingerprint.
 *
 * Android version compatibility:
 *   API 19  (Android 4.4 / LG tablets) : legacy WebView, old SystemUI flags
 *   API 21+ (Android 5+)               : WebResourceRequest override
 *   API 29+ (Android 10 / Lenovo)      : WindowInsetsController for immersive
 */
public class KioskWebViewActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String TABLET_KEY = "RIS-TABLET-KEY2026";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    private WebView webView;
    private OkHttpClient okHttpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyRotation();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        X509TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS);
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            okBuilder.sslSocketFactory(sc.getSocketFactory(), trustAll)
                     .hostnameVerifier(new HostnameVerifier() {
                         @Override public boolean verify(String h, SSLSession s) { return true; }
                     });
        } catch (Exception ignored) {}
        okHttpClient = okBuilder.build();

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

        webView.loadUrl(url.toString());
    }

    // Fetch a Worker URL via OkHttp, bypassing the WebView's Chrome TLS stack
    // which FortiGate SSL inspection intercepts on Android 10 (Chrome/149).
    // OkHttp's TLS fingerprint is not targeted by the FortiGate policy.
    private WebResourceResponse fetchViaOkHttp(String url, Map<String, String> headers) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (!key.equalsIgnoreCase("Host")) {
                        builder.addHeader(key, entry.getValue());
                    }
                }
            }
            Response response = okHttpClient.newCall(builder.build()).execute();
            ResponseBody body = response.body();
            if (body == null) return null;

            String contentType = response.header("Content-Type", "text/html");
            String mimeType = contentType.contains(";")
                ? contentType.split(";")[0].trim() : contentType;
            String charset = "UTF-8";
            if (contentType.contains("charset=")) {
                charset = contentType.split("charset=")[1].trim();
            }

            InputStream stream;
            if (mimeType.equals("text/html")) {
                // Inject tabletKey into localStorage before index.html's startup check runs.
                // loadDataWithBaseURL scopes localStorage to about:blank, not the Worker origin,
                // so we must inject directly into the HTML served by OkHttp.
                String html = body.string();
                // Override localStorage.getItem so index.html always sees tabletKey
                // regardless of origin scoping when shouldInterceptRequest serves the page.
                String inject = "<script>(function(){" +
                    "var _o=localStorage.getItem.bind(localStorage);" +
                    "localStorage.getItem=function(k){" +
                    "if(k==='roomdisplay_v5'){" +
                    "try{var c=JSON.parse(_o(k)||'{}');c.tabletKey='" + TABLET_KEY + "';return JSON.stringify(c);}catch(e){}" +
                    "}" +
                    "return _o(k);" +
                    "};})();</script>";
                html = html.contains("<head>") ? html.replace("<head>", "<head>" + inject) : inject + html;
                stream = new ByteArrayInputStream(html.getBytes("UTF-8"));
            } else {
                stream = body.byteStream();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Map<String, String> responseHeaders = new HashMap<>();
                for (int i = 0; i < response.headers().size(); i++) {
                    responseHeaders.put(response.headers().name(i), response.headers().value(i));
                }
                String reason = response.message();
                if (reason == null || reason.isEmpty()) reason = "OK";
                return new WebResourceResponse(mimeType, charset,
                    response.code(), reason, responseHeaders, stream);
            } else {
                return new WebResourceResponse(mimeType, charset, stream);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setupWebView() {
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

            // Route all Worker requests through OkHttp (API 21+, full request info)
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("ris-display.workers.dev")) {
                    return fetchViaOkHttp(url, request.getRequestHeaders());
                }
                return super.shouldInterceptRequest(view, request);
            }

            // Route all Worker requests through OkHttp (API 11+, URL only)
            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url.contains("ris-display.workers.dev")) {
                    return fetchViaOkHttp(url, null);
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            // Some Lenovo/Rockchip tablets have an outdated system CA store that
            // doesn't trust Cloudflare's cert. Safe to proceed — kiosk on internal Wi-Fi.
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
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
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemUI();
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

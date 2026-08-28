package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.conscrypt.Conscrypt;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Self-update: checks apk-version.json via Worker, downloads and installs
 * a newer APK if one is found. Works on Android 4.4+ (API 19).
 *
 * Uses OkHttp 3.12.x which bundles its own SSL stack — bypasses Android 4.4
 * system OpenSSL that cannot negotiate TLS 1.2 with modern servers.
 *
 * Flow: check() → version compare → AlertDialog → download → install intent
 *
 * Android version handling:
 *   API < 24  : Uri.fromFile() for install intent
 *   API 24-25 : FileProvider URI
 *   API 26+   : FileProvider URI + canRequestPackageInstalls() guard
 *   API 29+   : scoped storage (getExternalFilesDir)
 */
public class UpdateChecker {

    // Proxied through Cloudflare Worker — avoids direct HTTPS to GitHub Pages
    private static final String VERSION_URL =
        "https://ris-display.ris-display.workers.dev/api/version";
    private static final String APK_FILENAME = "ris-kiosk-update.apk";

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static final OkHttpClient CLIENT;
    private static final OkHttpClient DOWNLOAD_CLIENT;
    static {
        // Install Conscrypt as the first SSL provider — replaces Android 4.4 system
        // OpenSSL with a modern TLS 1.2/1.3 implementation so HTTPS works on all tablets.
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1); } catch (Throwable ignored) {}
        OkHttpClient c = new OkHttpClient();
        OkHttpClient dl = new OkHttpClient();
        try {
            // Accept all certificates — same policy as WebView's handler.proceed().
            // Required on Android 10 (rk3288/Latte) where FortiGate SSL inspection
            // presents its own certificate for our Worker URL.
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            SSLSocketFactory sf = sc.getSocketFactory();
            HostnameVerifier hv = new HostnameVerifier() {
                @Override public boolean verify(String h, SSLSession s) { return true; }
            };
            c = new OkHttpClient.Builder()
                .sslSocketFactory(sf, TRUST_ALL)
                .hostnameVerifier(hv)
                .build();
            // Separate client for large APK downloads — Worker cold-start + GitHub Pages
            // fetch can exceed the default 10s read timeout before first byte arrives.
            dl = new OkHttpClient.Builder()
                .sslSocketFactory(sf, TRUST_ALL)
                .hostnameVerifier(hv)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        } catch (Throwable ignored) {}
        CLIENT = c;
        DOWNLOAD_CLIENT = dl;
    }

    private static volatile boolean sInstallInProgress = false;

    public static void check(final Activity activity) {
        final Handler ui = new Handler(activity.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Request req = new Request.Builder().url(VERSION_URL).build();
                    Response resp = CLIENT.newCall(req).execute();
                    if (!resp.isSuccessful()) return;

                    String body = resp.body().string();
                    JSONObject json = new JSONObject(body);
                    final int remoteCode = json.getInt("versionCode");
                    final String remoteName = json.optString("versionName", "");
                    final String apkUrl = json.getString("apkUrl");

                    int localCode = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionCode;

                    if (remoteCode <= localCode) return;

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(activity, remoteName, apkUrl);
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(activity,
                                "Update check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static void showUpdateDialog(final Activity activity,
                                         final String version,
                                         final String apkUrl) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("RIS Kiosk v" + version + " is available.\nDownload and install now?")
            .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    downloadAndInstall(activity, apkUrl);
                }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private static void downloadAndInstall(final Activity activity, final String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity,
                    "Allow 'Install unknown apps' for RIS Kiosk in Settings, then try again.",
                    Toast.LENGTH_LONG).show();
                Intent settingsIntent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { activity.startActivity(settingsIntent); } catch (Exception ignored) {}
                return;
            }
        }

        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_LONG).show();
        final Handler ui = new Handler(activity.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apkFile = getApkFile(activity);

                    Request req = new Request.Builder().url(apkUrl).build();
                    Response resp = DOWNLOAD_CLIENT.newCall(req).execute();
                    if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());

                    InputStream is = resp.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(apkFile);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close();
                    is.close();

                    final File downloaded = apkFile;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            triggerInstall(activity, downloaded);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity,
                                "Download failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static File getApkFile(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new File(context.getExternalFilesDir(null), APK_FILENAME);
        } else {
            return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILENAME);
        }
    }

    private static void triggerInstall(Activity activity, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent.setDataAndType(
                    Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity,
                "Install failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Downloads and silently installs a newer APK via root (su pm install -r).
     * Runs on a background thread. Calls onNoUpdate if already up-to-date,
     * onFailure if root is denied or install fails, neither on success
     * (the package manager kills this process after install).
     * Safe to call from a BroadcastReceiver — does not require Activity context.
     */
    public static void silentInstall(final Context context,
                                     final Runnable onNoUpdate,
                                     final Runnable onFailure) {
        if (sInstallInProgress) { runCb(onNoUpdate); return; }
        sInstallInProgress = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    debugStep(context, "1_start", "");
                    Request req = new Request.Builder().url(VERSION_URL).build();
                    Response resp = CLIENT.newCall(req).execute();
                    if (!resp.isSuccessful()) {
                        debugStep(context, "ERR_version_http", String.valueOf(resp.code()));
                        runCb(onFailure); return;
                    }
                    if (resp.body() == null) {
                        debugStep(context, "ERR_version_nobody", "");
                        runCb(onFailure); return;
                    }
                    JSONObject json = new JSONObject(resp.body().string());
                    int remoteCode = json.getInt("versionCode");
                    String apkUrl  = json.getString("apkUrl");
                    int localCode = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionCode;
                    if (remoteCode <= localCode) {
                        debugStep(context, "2_noupdate", remoteCode + "<=" + localCode);
                        runCb(onNoUpdate); return;
                    }
                    debugStep(context, "3_download_start", "remote=" + remoteCode + " local=" + localCode + " url=" + apkUrl);

                    File apkFile = getApkFile(context);
                    Response dlResp = DOWNLOAD_CLIENT.newCall(
                        new Request.Builder().url(apkUrl).build()).execute();
                    if (!dlResp.isSuccessful()) {
                        debugStep(context, "ERR_download_http", String.valueOf(dlResp.code()));
                        runCb(onFailure); return;
                    }
                    if (dlResp.body() == null) {
                        debugStep(context, "ERR_download_nobody", "");
                        runCb(onFailure); return;
                    }
                    debugStep(context, "4_download_ok", "writing to " + apkFile.getAbsolutePath());
                    try (InputStream is = dlResp.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(apkFile)) {
                        byte[] buf = new byte[4096]; int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }

                    String suPath = new File("/system/xbin/su").exists()
                        ? "/system/xbin/su" : "/system/bin/su";
                    debugStep(context, "5_su_start", suPath);
                    // Step 5b: cp APK to /data/local/tmp/ — pm install via su cannot access
                    // scoped storage paths (API 29+) even as root due to SELinux on Android 10.
                    final Process cpProc = Runtime.getRuntime().exec(new String[]{
                        suPath, "-c", "cp " + apkFile.getAbsolutePath() + " /data/local/tmp/" + APK_FILENAME
                    });
                    Thread cpWaiter = new Thread(new Runnable() {
                        @Override public void run() {
                            try { cpProc.waitFor(); } catch (InterruptedException ignored) {}
                        }
                    });
                    cpWaiter.start();
                    cpWaiter.join(30000);
                    if (cpWaiter.isAlive()) {
                        cpProc.destroy();
                        debugStep(context, "ERR_cp_timeout", "30s");
                        runCb(onFailure); return;
                    }
                    int cpExit = cpProc.exitValue();
                    if (cpExit != 0) {
                        debugStep(context, "ERR_cp_exit", String.valueOf(cpExit));
                        runCb(onFailure); return;
                    }
                    debugStep(context, "5c_cp_ok", "/data/local/tmp/" + APK_FILENAME);

                    // Step 6: pm install from /data/local/tmp/
                    final Process proc = Runtime.getRuntime().exec(new String[]{
                        suPath, "-c", "pm install -r /data/local/tmp/" + APK_FILENAME
                    });
                    Thread waiter = new Thread(new Runnable() {
                        @Override public void run() {
                            try { proc.waitFor(); } catch (InterruptedException ignored) {}
                        }
                    });
                    waiter.start();
                    waiter.join(60000);
                    if (waiter.isAlive()) {
                        proc.destroy();
                        debugStep(context, "ERR_su_timeout", "60s");
                        runCb(onFailure); return;
                    }
                    int exitCode = proc.exitValue();
                    if (exitCode != 0) {
                        debugStep(context, "ERR_su_exit", String.valueOf(exitCode));
                        runCb(onFailure); return;
                    }
                    debugStep(context, "6_su_ok", "exit=0 installing");
                    // Exit 0: package manager will kill and restart this process.
                } catch (Exception e) {
                    debugStep(context, "ERR_exception", e.getClass().getSimpleName() + ":" + e.getMessage());
                    runCb(onFailure);
                } finally { sInstallInProgress = false; }
            }
        }).start();
    }

    // Fire-and-forget: POST the current step to /api/ota-debug for remote diagnostics.
    private static void debugStep(final Context context, final String step, final String detail) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    android.content.SharedPreferences p =
                        context.getSharedPreferences("ris_kiosk_prefs",
                            android.content.Context.MODE_PRIVATE);
                    String room     = p.getString("room_email", "");
                    String roomname = p.getString("room_name",  "");
                    if (room.isEmpty()) return;
                    String ver = "";
                    try { ver = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
                    } catch (Exception ignored) {}
                    String body = "{\"room\":\"" + room
                        + "\",\"roomname\":\"" + roomname
                        + "\",\"step\":\"" + step
                        + "\",\"detail\":\"" + detail.replace("\"", "'")
                        + "\",\"apkVersion\":\"" + ver + "\"}";
                    okhttp3.OkHttpClient dbgClient = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .sslSocketFactory(
                            ((javax.net.ssl.SSLSocketFactory) CLIENT.sslSocketFactory()),
                            TRUST_ALL)
                        .hostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                            @Override public boolean verify(String h, javax.net.ssl.SSLSession s) { return true; }
                        })
                        .build();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://ris-display.ris-display.workers.dev/api/ota-debug")
                        .post(okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("application/json"), body))
                        .build();
                    dbgClient.newCall(req).execute().close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private static void runCb(Runnable r) { if (r != null) r.run(); }
}

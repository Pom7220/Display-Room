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
    static {
        // Install Conscrypt as the first SSL provider — replaces Android 4.4 system
        // OpenSSL with a modern TLS 1.2/1.3 implementation so HTTPS works on all tablets.
        try { Security.insertProviderAt(Conscrypt.newProvider(), 1); } catch (Throwable ignored) {}
        OkHttpClient c = new OkHttpClient();
        try {
            // Accept all certificates — same policy as WebView's handler.proceed().
            // Required on Android 10 (rk3288/Latte) where FortiGate SSL inspection
            // presents its own certificate for our Worker URL.
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            SSLSocketFactory sf = sc.getSocketFactory();
            c = new OkHttpClient.Builder()
                .sslSocketFactory(sf, TRUST_ALL)
                .hostnameVerifier(new HostnameVerifier() {
                    @Override public boolean verify(String h, SSLSession s) { return true; }
                })
                .build();
        } catch (Throwable ignored) {}
        CLIENT = c;
    }

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
                    Response resp = CLIENT.newCall(req).execute();
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
}

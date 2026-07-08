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
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update: checks apk-version.json on GitHub Pages, downloads and
 * installs a newer APK if one is found. Works on Android 4.4+ (API 19).
 *
 * Flow: check() → version compare → AlertDialog → download → install intent
 *
 * Android version handling:
 *   API < 24  : Uri.fromFile() for install intent
 *   API 24-25 : FileProvider URI (no canRequestPackageInstalls check)
 *   API 26+   : FileProvider URI + canRequestPackageInstalls() guard
 *   API 29+   : scoped storage (getExternalFilesDir instead of Downloads)
 */
public class UpdateChecker {

    private static final String VERSION_URL =
        "https://pom7220.github.io/Display-Room/apk-version.json";
    private static final String APK_FILENAME = "ris-kiosk-update.apk";

    public static void check(final Activity activity) {
        final Handler ui = new Handler(activity.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Fetch version JSON
                    HttpURLConnection conn = (HttpURLConnection)
                        new URL(VERSION_URL).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.connect();
                    if (conn.getResponseCode() != 200) return;

                    StringBuilder sb = new StringBuilder();
                    InputStream is = conn.getInputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
                    is.close();

                    JSONObject json = new JSONObject(sb.toString());
                    final int remoteCode = json.getInt("versionCode");
                    final String remoteName = json.optString("versionName", "");
                    final String apkUrl = json.getString("apkUrl");

                    int localCode = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionCode;

                    if (remoteCode <= localCode) return; // already up to date

                    // Show update dialog on UI thread
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(activity, remoteName, apkUrl);
                        }
                    });
                } catch (Exception e) {
                    // Network unavailable or JSON malformed — silently skip
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
        // On API 26+, check that we have install-unknown-apps permission
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

                    HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.connect();

                    InputStream is = conn.getInputStream();
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
            // Android 10+: use scoped storage (no WRITE_EXTERNAL_STORAGE needed)
            return new File(context.getExternalFilesDir(null), APK_FILENAME);
        } else {
            // Android 9 and below: public Downloads folder
            return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILENAME);
        }
    }

    private static void triggerInstall(Activity activity, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+: must use FileProvider URI
                Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // Android 6 and below: direct file URI
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

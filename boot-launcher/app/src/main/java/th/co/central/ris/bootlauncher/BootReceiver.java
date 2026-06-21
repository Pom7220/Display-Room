package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * v1.8 — Full kiosk boot launcher:
 * 1. Three-tier Chrome launch: PWA mode → shortcut mode → regular
 * 2. Double launch at 90s + 3min (beats Meet in Touch)
 * 3. CLEAR_TASK flag (single tab, no accumulation)
 * 4. Starts ForegroundWatchService (keeps Chrome on top 24/7)
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";
    private static final long FIRST_LAUNCH_MS = 90000;
    private static final long SECOND_LAUNCH_MS = 180000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // Start the foreground watchdog service
            try {
                context.startService(new Intent(context, ForegroundWatchService.class));
            } catch (Exception e) {}

            // Launch Chrome with delay
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchKiosk(context);

                    try { Thread.sleep(SECOND_LAUNCH_MS - FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    bringChromeToFront(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
        String url = buildUrl(context);

        // Try 1: PWA mode (standalone, auto-fullscreen)
        if (tryPwaLaunch(context, url)) return;

        // Try 2: Shortcut mode (older Chrome class name)
        if (tryShortcutLaunch(context, url)) return;

        // Try 3: Regular Chrome (browser mode)
        launchRegularChrome(context, url);
    }

    private boolean tryPwaLaunch(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.setClassName(CHROME_PACKAGE,
                "com.google.android.apps.chrome.webapps.WebappLauncherActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryShortcutLaunch(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.setClassName(CHROME_PACKAGE,
                "org.chromium.chrome.browser.webapps.WebappLauncherActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchRegularChrome(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW);
                fallback.setData(Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {}
        }
    }

    private String buildUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0) {
            url.append("&room=").append(Uri.encode(roomEmail));
        }
        if (roomName.length() > 0) {
            url.append("&roomname=").append(Uri.encode(roomName));
        }
        return url.toString();
    }

    static void bringChromeToFront(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {}
    }
}

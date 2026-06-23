package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * v2.0 — WebView kiosk launcher.
 * Launches KioskWebViewActivity (fullscreen WebView) instead of Chrome.
 * True fullscreen from boot — no address bar, no tap needed.
 * Falls back to Chrome if WebView activity fails.
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

            // Start foreground watchdog
            try {
                context.startService(new Intent(context, ForegroundWatchService.class));
            } catch (Exception e) {}

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchKiosk(context);

                    try { Thread.sleep(SECOND_LAUNCH_MS - FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    bringToFront(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
        // Primary: launch fullscreen WebView activity
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
            return;
        } catch (Exception e) {}

        // Fallback: regular Chrome
        launchChrome(context);
    }

    private void launchChrome(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());
        if (roomEmail.length() > 0) url.append("&room=").append(Uri.encode(roomEmail));
        if (roomName.length() > 0) url.append("&roomname=").append(Uri.encode(roomName));

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url.toString()));
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {}
        }
    }

    static void bringToFront(Context context) {
        // Bring our KioskWebViewActivity to front
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
            return;
        } catch (Exception e) {}

        // Fallback: bring Chrome to front
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(CHROME_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {}
    }
}

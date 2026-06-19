package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Launches Chrome on BOOT_COMPLETED with room config in URL params.
 * Double-launch: opens URL at 90s, then brings Chrome to front at 3min.
 * Second launch uses ACTION_MAIN (no URL) to avoid opening a new tab —
 * just brings the existing tab back on top of Meet in Touch.
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

            new Thread(new Runnable() {
                @Override
                public void run() {
                    // First launch — open URL with room config (new tab)
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchWithUrl(context);

                    // Second launch — just bring Chrome to foreground (no new tab)
                    try { Thread.sleep(SECOND_LAUNCH_MS - FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    bringChromeToFront(context);
                }
            }).start();
        }
    }

    private void launchWithUrl(Context context) {
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

        try {
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW);
            chromeIntent.setData(Uri.parse(url.toString()));
            chromeIntent.setPackage(CHROME_PACKAGE);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(chromeIntent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW);
                fallback.setData(Uri.parse(url.toString()));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {}
        }
    }

    private void bringChromeToFront(Context context) {
        try {
            // ACTION_MAIN + CATEGORY_LAUNCHER brings Chrome to front
            // with the last viewed tab — no new tab created
            Intent frontIntent = new Intent(Intent.ACTION_MAIN);
            frontIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            frontIntent.setPackage(CHROME_PACKAGE);
            frontIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(frontIntent);
        } catch (Exception e) {}
    }
}

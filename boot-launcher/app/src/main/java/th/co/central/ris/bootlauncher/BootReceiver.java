package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Launches Chrome on BOOT_COMPLETED with room config in URL params.
 * Double-launch strategy: fires at 90s AND 180s after boot.
 * Meet in Touch also fires on boot and may steal the foreground.
 * The second launch at 180s brings Chrome back on top.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";
    private static final long FIRST_LAUNCH_MS = 90000;   // 90 seconds
    private static final long SECOND_LAUNCH_MS = 180000;  // 3 minutes

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // First launch — after WiFi and services settle
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchKiosk(context);

                    // Second launch — brings Chrome back if Meet in Touch stole foreground
                    try { Thread.sleep(SECOND_LAUNCH_MS - FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchKiosk(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
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

        String finalUrl = url.toString();

        try {
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW);
            chromeIntent.setData(Uri.parse(finalUrl));
            chromeIntent.setPackage(CHROME_PACKAGE);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(chromeIntent);
        } catch (Exception e) {
            try {
                Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
                fallbackIntent.setData(Uri.parse(finalUrl));
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(fallbackIntent);
            } catch (Exception e2) {}
        }
    }
}

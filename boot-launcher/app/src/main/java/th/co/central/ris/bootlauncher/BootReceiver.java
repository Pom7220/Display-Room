package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Launches Chrome on BOOT_COMPLETED with the room config baked into the URL.
 * This ensures localStorage config is always set regardless of whether Chrome
 * opens in PWA context or regular browser context.
 *
 * Room config is stored in SharedPreferences by MainActivity (room picker).
 * On boot: reads config → appends as URL params → Chrome opens → index.html
 * detects params → writes to localStorage → app launches with correct room.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";
    private static final long BOOT_DELAY_MS = 10000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(BOOT_DELAY_MS);
                    } catch (InterruptedException e) {
                        // Continue
                    }
                    launchKiosk(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
        // Read room config from SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");
        boolean roomApproval = prefs.getBoolean("room_approval", false);

        // Build URL with room config as params
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("?nocache=").append(System.currentTimeMillis());

        if (roomEmail.length() > 0) {
            url.append("&room=").append(Uri.encode(roomEmail));
        }
        if (roomName.length() > 0) {
            url.append("&roomname=").append(Uri.encode(roomName));
        }
        if (roomApproval) {
            url.append("&approval=1");
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
            } catch (Exception e2) {
                // Both failed
            }
        }
    }
}

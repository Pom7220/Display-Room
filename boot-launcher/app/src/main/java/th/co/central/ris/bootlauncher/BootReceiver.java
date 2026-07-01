package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Stable Chrome-based boot launcher with auto-tap accessibility service.
 * Chrome opens URL → accessibility service detects Chrome → taps screen
 * → fullscreen activates → zero touch needed from user.
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

            try {
                context.startService(new Intent(context, ForegroundWatchService.class));
            } catch (Exception e) {}

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    launchChrome(context);

                    try { Thread.sleep(SECOND_LAUNCH_MS - FIRST_LAUNCH_MS); } catch (InterruptedException e) {}
                    bringChromeToFront(context);
                }
            }).start();
        }
    }

    static void launchChrome(Context context) {
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

package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Boot launcher — starts KioskWebViewActivity after device boot.
 * KioskWebViewActivity provides Android-level fullscreen (FLAG_FULLSCREEN)
 * that is reload-proof — page reloads for auto-updates never exit fullscreen.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";
    private static final long FIRST_LAUNCH_MS = 90000;

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
                    launchKioskActivity(context);
                }
            }).start();
        }
    }

    static void launchKioskActivity(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to Chrome if WebView activity fails to start
            launchChrome(context);
        }
    }

    static void launchChrome(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String roomEmail = prefs.getString("room_email", "");
        String roomName = prefs.getString("room_name", "");

        StringBuilder url = new StringBuilder("https://ris-display.ris-display.workers.dev/");
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

    static void bringKioskToFront(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {}
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

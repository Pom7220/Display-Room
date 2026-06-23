package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot receiver — starts watchdog and launches KioskWebViewActivity.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final long FIRST_LAUNCH_MS = 90000;
    private static final long SECOND_LAUNCH_MS = 180000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // Start watchdog
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

    static void launchKiosk(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback: Chrome
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://ris-display.ris-display.workers.dev/"));
                fallback.setPackage("com.android.chrome");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {}
        }
    }

    static void bringToFront(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Exception e) {}
    }
}

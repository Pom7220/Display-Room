package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class BootReceiver extends BroadcastReceiver {

    // Kiosk URL — update this if the URL changes
    private static final String KIOSK_URL =
        "https://pom7220.github.io/Display-Room/index.html?v=360608";

    // Chrome package name on Android
    private static final String CHROME_PACKAGE = "com.android.chrome";

    // Delay in milliseconds before launching Chrome after boot
    // 8 seconds gives Android time to fully initialise WiFi/network
    private static final long BOOT_DELAY_MS = 8000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        // Handle both standard boot and LG quick-boot
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // Use a thread to delay launch — network needs time to connect
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(BOOT_DELAY_MS);
                    } catch (InterruptedException e) {
                        // Continue even if interrupted
                    }
                    launchChrome(context);
                }
            }).start();
        }
    }

    private void launchChrome(Context context) {
        try {
            // Try to launch Chrome specifically first
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW);
            chromeIntent.setData(Uri.parse(KIOSK_URL));
            chromeIntent.setPackage(CHROME_PACKAGE);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(chromeIntent);
        } catch (Exception e) {
            // Chrome not found or failed — fall back to default browser
            try {
                Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
                fallbackIntent.setData(Uri.parse(KIOSK_URL));
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(fallbackIntent);
            } catch (Exception e2) {
                // Both failed — nothing we can do
            }
        }
    }
}

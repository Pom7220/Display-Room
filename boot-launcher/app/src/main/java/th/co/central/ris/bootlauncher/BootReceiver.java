package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class BootReceiver extends BroadcastReceiver {

    // Base URL — no version string, always loads latest
    // Timestamp cache-bust added at runtime so Chrome 42 always fetches fresh
    private static final String BASE_URL =
        "https://pom7220.github.io/Display-Room/";

    // Chrome package name on Android
    private static final String CHROME_PACKAGE = "com.android.chrome";

    // Delay before launching — gives Android time to connect WiFi after boot
    // 10 seconds is safer than 8 for slower LG tablets
    private static final long BOOT_DELAY_MS = 10000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();

        // Handle both standard boot and LG quick-boot
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(BOOT_DELAY_MS);
                    } catch (InterruptedException e) {
                        // Continue even if interrupted
                    }
                    launchKiosk(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
        // Add timestamp as cache-bust — ensures Chrome 42 always fetches
        // the latest version, never serves cached old version
        String url = BASE_URL + "?nocache=" + System.currentTimeMillis();

        try {
            // Launch Chrome specifically
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW);
            chromeIntent.setData(Uri.parse(url));
            chromeIntent.setPackage(CHROME_PACKAGE);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(chromeIntent);
        } catch (Exception e) {
            // Chrome not found — fall back to default browser
            try {
                Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
                fallbackIntent.setData(Uri.parse(url));
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(fallbackIntent);
            } catch (Exception e2) {
                // Nothing we can do
            }
        }
    }
}

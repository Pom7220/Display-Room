package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class BootReceiver extends BroadcastReceiver {

    // Cloudflare Worker proxy — DigiCert cert trusted natively by Android 4.4
    // Proxies to pom7220.github.io/Display-Room/ transparently
    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";

    private static final String CHROME_PACKAGE = "com.android.chrome";
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
                        // Continue even if interrupted
                    }
                    launchKiosk(context);
                }
            }).start();
        }
    }

    private void launchKiosk(Context context) {
        String url = BASE_URL + "?nocache=" + System.currentTimeMillis();

        try {
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW);
            chromeIntent.setData(Uri.parse(url));
            chromeIntent.setPackage(CHROME_PACKAGE);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(chromeIntent);
        } catch (Exception e) {
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

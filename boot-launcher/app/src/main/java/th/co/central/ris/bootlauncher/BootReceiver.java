package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {

    private static final long BOOT_DELAY_MS = 90000; // 90s — let Android settle after boot

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            try {
                Intent svc = new Intent(context, ForegroundWatchService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8+: background services restricted — must use foreground
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            } catch (Exception ignored) {}

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(BOOT_DELAY_MS); } catch (InterruptedException e) {}
                    launchWebView(context);
                }
            }).start();
        }
    }

    static void launchWebView(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }
}

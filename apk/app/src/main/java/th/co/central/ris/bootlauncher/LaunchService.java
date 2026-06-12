package th.co.central.ris.bootlauncher;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Waits 10 seconds after boot for WiFi to connect,
 * then launches Chrome to the RIS Room Display URL.
 * Dismisses the keyguard first if a screen lock is set
 * (required when ISRG Root X1 cert is installed as user cert).
 */
public class LaunchService extends Service {

    private static final String DISPLAY_URL = "https://pom7220.github.io/Display-Room/";
    private static final long BOOT_DELAY_MS = 10000; // 10 seconds for WiFi

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                wakeAndDismissKeyguard();
                launchChrome();
                stopSelf();
            }
        }, BOOT_DELAY_MS);
        return START_NOT_STICKY;
    }

    /**
     * Wake the screen and attempt to dismiss the keyguard.
     * If a PIN/pattern is set (required for user cert), this wakes the screen
     * so the lock screen is visible and ready for input.
     */
    private void wakeAndDismissKeyguard() {
        try {
            // Wake the screen
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "ris:bootlaunch");
                wl.acquire(5000);
            }

            // Attempt to dismiss keyguard (works for swipe lock, not PIN)
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                KeyguardManager.KeyguardLock kl = km.newKeyguardLock("RIS_KIOSK");
                kl.disableKeyguard();
            }
        } catch (Exception e) {
            // Non-critical — Chrome will still launch, user may need to unlock
        }
    }

    /**
     * Launch Chrome to the Display-Room URL with a cache-bust parameter.
     */
    private void launchChrome() {
        try {
            String url = DISPLAY_URL + "?nocache=" + System.currentTimeMillis();
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            chromeIntent.setPackage("com.android.chrome");
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(chromeIntent);
        } catch (Exception e) {
            // Chrome not installed — try default browser
            try {
                String url = DISPLAY_URL + "?nocache=" + System.currentTimeMillis();
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
            } catch (Exception e2) {
                // Nothing we can do
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

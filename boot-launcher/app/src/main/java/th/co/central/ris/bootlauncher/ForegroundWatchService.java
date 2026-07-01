package th.co.central.ris.bootlauncher;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

import java.util.List;

/**
 * Background watchdog — keeps KioskWebViewActivity in the foreground.
 *
 * Checks every 5 minutes if the kiosk app is the top app. If something else
 * (e.g., home screen, another app) is on top, it relaunches KioskWebViewActivity.
 *
 * Runs as an Android Service — works even when the WebView is suspended.
 */
public class ForegroundWatchService extends Service {

    // CHROME_PACKAGE is accepted as a valid foreground state (Chrome fallback from launchKioskActivity)
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final long CHECK_INTERVAL_MS = 300000; // 5 minutes
    private static final long INITIAL_DELAY_MS = 240000;  // 4 minutes (let boot sequence finish)

    private Handler handler;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.postDelayed(checkRunnable, INITIAL_DELAY_MS);
        }
        return START_STICKY;
    }

    private Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRestore();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private void checkAndRestore() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;

            // getRunningTasks works on API 19 (Android 4.4)
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;

            String topPackage = tasks.get(0).topActivity.getPackageName();
            String ownPackage = getPackageName();

            if (!ownPackage.equals(topPackage) && !CHROME_PACKAGE.equals(topPackage)) {
                // Neither kiosk app nor Chrome is on top — bring kiosk back
                BootReceiver.bringKioskToFront(getApplicationContext());
            }
        } catch (Exception e) {
            // Non-critical — will retry on next interval
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            handler.removeCallbacks(checkRunnable);
        }
        super.onDestroy();
    }
}

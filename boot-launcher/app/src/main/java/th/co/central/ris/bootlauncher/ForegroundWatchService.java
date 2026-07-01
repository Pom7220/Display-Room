package th.co.central.ris.bootlauncher;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

import java.util.List;

/**
 * Background watchdog service — keeps Chrome in the foreground.
 *
 * Checks every 5 minutes if Chrome is the top app. If something else
 * (e.g., Meet in Touch, home screen, another app) is on top, it
 * brings Chrome back to the foreground automatically.
 *
 * This runs as an Android Service in the APK — NOT in Chrome's
 * JavaScript. So it works even when Chrome is backgrounded/suspended.
 *
 * Also detects if Chrome has crashed (not in recent tasks at all)
 * and relaunches it with the room URL.
 */
public class ForegroundWatchService extends Service {

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
            // Start checking after initial delay (let boot launcher finish)
            handler.postDelayed(checkRunnable, INITIAL_DELAY_MS);
        }
        // Restart service if killed by system
        return START_STICKY;
    }

    private Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRestore();
            // Schedule next check
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private void checkAndRestore() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;

            // getRunningTasks is deprecated in API 21+ but works fine on API 19 (Android 4.4)
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;

            String topPackage = tasks.get(0).topActivity.getPackageName();

            String ownPackage = getPackageName();
            // Check if our WebView OR Chrome is in foreground
            if (!CHROME_PACKAGE.equals(topPackage) && !ownPackage.equals(topPackage)) {
                // Neither our app nor Chrome is on top — bring kiosk back
                BootReceiver.bringChromeToFront(getApplicationContext());
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

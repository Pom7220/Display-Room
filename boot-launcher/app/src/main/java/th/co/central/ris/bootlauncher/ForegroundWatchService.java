package th.co.central.ris.bootlauncher;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import java.util.List;

/**
 * Background watchdog service — keeps the kiosk WebView in the foreground.
 *
 * Android compatibility:
 *   API 19-20 (Android 4.4/4.4W) : getRunningTasks works, plain startService
 *   API 21-25 (Android 5-6)      : getRunningTasks limited to own app — skip check
 *   API 26+   (Android 8+)       : must use startForegroundService + notification channel
 */
public class ForegroundWatchService extends Service {

    private static final long CHECK_INTERVAL_MS = 300000; // 5 minutes
    private static final long INITIAL_DELAY_MS   = 240000; // 4 minutes
    private static final String CHANNEL_ID = "ris_kiosk_watchdog";
    private static final int    NOTIF_ID   = 1001;

    private Handler handler;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            startForeground(NOTIF_ID, buildSilentNotification());
        }
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
        // getRunningTasks returns only our own tasks on API 21+
        // — not useful for detecting other foreground apps on newer Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return;

        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return;

            String topPackage = tasks.get(0).topActivity.getPackageName();
            if (!getPackageName().equals(topPackage)) {
                BootReceiver.launchWebView(getApplicationContext());
            }
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "RIS Kiosk Watchdog",
                NotificationManager.IMPORTANCE_MIN); // completely silent
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildSilentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RIS Kiosk")
                .setContentText("Room display running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
        }
        return new Notification();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacks(checkRunnable);
        super.onDestroy();
    }
}

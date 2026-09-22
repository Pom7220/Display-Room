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
            checkHeartbeat();
            checkAndRestore();
            checkEscalation();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private void checkHeartbeat() {
        long lastSuccess = ScheduleReceiver.lastHeartbeatSuccessMs;
        // No heartbeat recorded yet in this process — skip on cold start.
        if (lastSuccess == 0L) return;

        java.util.Calendar bkk = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
                    + bkk.get(java.util.Calendar.MINUTE);
        if (timeBKK < 730 || timeBKK >= 2030) return; // standby hours

        long threshold = ScheduleReceiver.heartbeatIntervalMs + 15 * 60 * 1000L;
        if (System.currentTimeMillis() - lastSuccess <= threshold) return;

        // Heartbeat overdue — log and restart kiosk.
        final android.content.Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                ScheduleReceiver.logAlarmEventSync(ctx, "heartbeat_watchdog_restart");
                android.content.Intent launch =
                    ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }).start();
    }

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
                // During standby hours StandbyActivity owns the screen. Relaunching the
                // kiosk here wakes the display and, because launchWebView CLEAR_TASKs,
                // reloads the page — which the web app reports as a device reboot.
                if (isInStandbyWindow()) {
                    // Relaunching StandbyActivity silently would hide the fact that
                    // something took the foreground. Record the offending package so the
                    // trigger is visible in KV without needing ADB.
                    reportDisplacement(topPackage);
                    ScheduleReceiver.launchStandby(getApplicationContext());
                } else {
                    BootReceiver.launchWebView(getApplicationContext());
                }
            }
        } catch (Exception ignored) {}
    }

    // Capped at 6 per BKK day per tablet: enough samples to identify the culprit,
    // but it cannot run away and eat the 1000 writes/day KV budget.
    private static final int DISPLACE_LOG_CAP = 6;

    private void reportDisplacement(final String topPackage) {
        try {
            android.content.SharedPreferences p =
                getSharedPreferences("ris_displace_log", MODE_PRIVATE);
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
            final String key = "n_" + sdf.format(new java.util.Date());
            int n = p.getInt(key, 0);
            if (n >= DISPLACE_LOG_CAP) return;
            p.edit().putInt(key, n + 1).apply();

            final android.content.Context ctx = getApplicationContext();
            final int seq = n + 1;
            new Thread(new Runnable() {
                @Override public void run() {
                    ScheduleReceiver.logAlarmEventSync(ctx, "kiosk_displaced",
                        topPackage + " (" + seq + "/" + DISPLACE_LOG_CAP + ")");
                }
            }).start();
        } catch (Exception ignored) {}
    }

    private boolean isInStandbyWindow() {
        java.util.Calendar bkk = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        int h = bkk.get(java.util.Calendar.HOUR_OF_DAY);
        int m = bkk.get(java.util.Calendar.MINUTE);
        return (h > 20 || (h == 20 && m >= 30) || h < 6);
    }

    private void checkEscalation() {
        android.content.SharedPreferences prefs =
            getSharedPreferences("ris_kiosk_prefs", MODE_PRIVATE);
        long firstRestartMs = prefs.getLong("escalation_first_restart_ms", 0L);
        if (firstRestartMs == 0L) return; // no escalation in progress

        // Standby / weekend gate — same bounds as checkAndHeal()
        java.util.Calendar bkk = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        int timeBKK = bkk.get(java.util.Calendar.HOUR_OF_DAY) * 100
                    + bkk.get(java.util.Calendar.MINUTE);
        if (timeBKK < 730 || timeBKK >= 2030) return;
        int day = bkk.get(java.util.Calendar.DAY_OF_WEEK);
        if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) return;

        // 120-min threshold
        if (System.currentTimeMillis() - firstRestartMs < 120 * 60 * 1000L) return;

        fireEscalatedReboot(prefs);
    }

    private void fireEscalatedReboot(android.content.SharedPreferences prefs) {
        // Daily cap check — resets by BKK calendar date, not on cold_boot
        java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        String today = sdf.format(new java.util.Date());
        String lastDate = prefs.getString("escalation_daily_reboot_date", "");
        int dailyCount = today.equals(lastDate)
            ? prefs.getInt("escalation_daily_reboot_count", 0) : 0;
        if (dailyCount >= 3) return; // cap reached — wait for manual intervention

        // Commit escalation state update before reboot (sync write is critical here)
        prefs.edit()
            .putString("escalation_daily_reboot_date", today)
            .putInt("escalation_daily_reboot_count", dailyCount + 1)
            .putLong("escalation_first_restart_ms", 0L)
            .putInt("escalation_restart_count", 0)
            .commit(); // commit() not apply() — process may die immediately after

        final android.content.Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                ScheduleReceiver.logAlarmEventSync(ctx, "escalated_reboot");
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        Runtime.getRuntime().exec(new String[]{"reboot"});
                    } else {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                    }
                } catch (Exception ignored) {}
            }
        }).start();
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

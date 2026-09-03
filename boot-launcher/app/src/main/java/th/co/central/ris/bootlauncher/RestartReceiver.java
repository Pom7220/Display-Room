package th.co.central.ris.bootlauncher;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Relaunches the kiosk after a silent OTA install (pm install -r).
 * MY_PACKAGE_REPLACED is sent to the newly installed version once
 * the package manager finishes replacing the APK — no reboot needed.
 *
 * Android 4.4 (API 19-28): schedules a 5-second AlarmManager alarm targeting
 * DelayedLaunchReceiver so the package manager fully settles before the activity
 * launches — immediate startActivity() from MY_PACKAGE_REPLACED prevents
 * IMMERSIVE_STICKY from engaging on LG Android 4.4.
 *
 * Android 10+ (API 29+): background activity launch is restricted — uses a
 * full-screen notification with USE_FULL_SCREEN_INTENT. Screen off/locked →
 * auto-launches. Screen on → shows as persistent heads-up (tap to launch).
 */
public class RestartReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "kiosk_restart";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        Intent launch = new Intent(context, KioskWebViewActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: startActivity() blocked in background — use notification
            postFullScreenNotification(context, launch);
        } else {
            // Android 4.4–9: schedule launch 5 s later via AlarmManager so the package
            // manager fully settles before we start the activity. Immediate startActivity()
            // from MY_PACKAGE_REPLACED context starts before system UI is ready, preventing
            // IMMERSIVE_STICKY from engaging on LG Android 4.4.
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent delayed = new Intent(DelayedLaunchReceiver.ACTION);
            delayed.setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, delayed, 0);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5000, pi);
        }
    }

    private void postFullScreenNotification(Context context, Intent launch) {
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(context, 0, launch, piFlags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Kiosk Restart", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }

        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Restarting kiosk")
            .setContentText("APK updated — resuming display")
            .setFullScreenIntent(pi, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true);

        NotificationManagerCompat.from(context).notify(NOTIF_ID, nb.build());
    }
}

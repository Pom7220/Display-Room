package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

public class ScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_STANDBY = "th.co.central.ris.bootlauncher.ACTION_STANDBY";
    public static final String ACTION_WAKE    = "th.co.central.ris.bootlauncher.ACTION_WAKE";
    public static final String ACTION_RESTART = "th.co.central.ris.bootlauncher.ACTION_RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (ACTION_STANDBY.equals(action)) {
            launchStandby(context);

        } else if (ACTION_WAKE.equals(action)) {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            boolean isWeekend = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
            if (!isWeekend) {
                BootReceiver.launchWebView(context);
            }
            // Weekend: stay in standby, do nothing

        } else if (ACTION_RESTART.equals(action)) {
            // Switch to StandbyActivity to destroy the WebView and free memory.
            // The 08:00 ACTION_WAKE alarm will restore KioskWebViewActivity.
            launchStandby(context);
        }
    }

    static void schedule(Context context) {
        android.app.AlarmManager am = (android.app.AlarmManager)
            context.getSystemService(android.content.Context.ALARM_SERVICE);
        if (am == null) return;

        int flags = android.os.Build.VERSION.SDK_INT >= 23
            ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            : android.app.PendingIntent.FLAG_UPDATE_CURRENT;

        android.app.PendingIntent piStandby = android.app.PendingIntent.getBroadcast(context, 1,
            new android.content.Intent(ACTION_STANDBY)
                .setClass(context, ScheduleReceiver.class), flags);
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,
            nextOccurrence(20, 30), android.app.AlarmManager.INTERVAL_DAY, piStandby);

        android.app.PendingIntent piWake = android.app.PendingIntent.getBroadcast(context, 2,
            new android.content.Intent(ACTION_WAKE)
                .setClass(context, ScheduleReceiver.class), flags);
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,
            nextOccurrence(8, 0), android.app.AlarmManager.INTERVAL_DAY, piWake);

        android.app.PendingIntent piRestart = android.app.PendingIntent.getBroadcast(context, 3,
            new android.content.Intent(ACTION_RESTART)
                .setClass(context, ScheduleReceiver.class), flags);
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP,
            nextOccurrence(6, 0), android.app.AlarmManager.INTERVAL_DAY, piRestart);
    }

    private static long nextOccurrence(int hour, int minute) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    static void launchStandby(Context context) {
        try {
            Intent i = new Intent(context, StandbyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }
}

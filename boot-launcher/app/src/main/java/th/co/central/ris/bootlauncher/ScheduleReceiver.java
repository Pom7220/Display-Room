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
            // Reschedule next day's exact alarm
            setExactAlarm(context, ACTION_STANDBY, 1, 20, 30);

        } else if (ACTION_WAKE.equals(action)) {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            boolean isWeekend = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
            if (!isWeekend) {
                BootReceiver.launchWebView(context);
            }
            // Weekend: stay in standby, do nothing
            // Reschedule next day's exact alarm
            setExactAlarm(context, ACTION_WAKE, 2, 8, 0);

        } else if (ACTION_RESTART.equals(action)) {
            // Switch to StandbyActivity to destroy the WebView and free memory.
            // The 08:00 ACTION_WAKE alarm will restore KioskWebViewActivity.
            launchStandby(context);
            // Reschedule next day's exact alarm
            setExactAlarm(context, ACTION_RESTART, 3, 6, 0);
        }
    }

    // Called on every KioskWebViewActivity start and on boot — sets precise one-shot alarms
    // for tonight's occurrences. Each alarm reschedules itself for the next day when it fires.
    // Using setExact (not setInexactRepeating) so alarms fire within ~1 min of target,
    // not batched by Android which can delay setInexactRepeating by 2-3 hours.
    static void schedule(Context context) {
        setExactAlarm(context, ACTION_STANDBY, 1, 20, 30);
        setExactAlarm(context, ACTION_WAKE,    2,  8,  0);
        setExactAlarm(context, ACTION_RESTART, 3,  6,  0);
    }

    private static void setExactAlarm(Context context, String action, int requestCode,
                                      int hour, int minute) {
        android.app.AlarmManager am = (android.app.AlarmManager)
            context.getSystemService(android.content.Context.ALARM_SERVICE);
        if (am == null) return;

        int flags = android.os.Build.VERSION.SDK_INT >= 23
            ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            : android.app.PendingIntent.FLAG_UPDATE_CURRENT;

        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(context, requestCode,
            new android.content.Intent(action).setClass(context, ScheduleReceiver.class), flags);

        long triggerAt = nextOccurrence(hour, minute);

        // setExactAndAllowWhileIdle (API 23+) fires even in Doze mode — critical for 20:30 standby.
        // setExact (API 19-22) is precise but may be deferred in Doze (acceptable for older devices).
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
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

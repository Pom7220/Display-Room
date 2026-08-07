package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.security.Security;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.conscrypt.Conscrypt;

public class ScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_STANDBY      = "th.co.central.ris.bootlauncher.ACTION_STANDBY";
    public static final String ACTION_WAKE         = "th.co.central.ris.bootlauncher.ACTION_WAKE";
    public static final String ACTION_RESTART      = "th.co.central.ris.bootlauncher.ACTION_RESTART";
    public static final String ACTION_HEALTH_CHECK = "th.co.central.ris.bootlauncher.ACTION_HEALTH_CHECK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (ACTION_STANDBY.equals(action)) {
            logAlarmEvent(context, "standby");
            launchStandby(context);
            setExactAlarm(context, ACTION_STANDBY, 1, 20, 30);

        } else if (ACTION_WAKE.equals(action)) {
            int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            boolean isWeekend = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
            if (!isWeekend) {
                logAlarmEvent(context, "wake");
                BootReceiver.launchWebView(context);
            } else {
                logAlarmEvent(context, "wake_weekend");
            }
            setExactAlarm(context, ACTION_WAKE, 2, 7, 30);

        } else if (ACTION_RESTART.equals(action)) {
            logAlarmEvent(context, "restart");
            launchStandby(context);
            setExactAlarm(context, ACTION_RESTART, 3, 6, 0);

        } else if (ACTION_HEALTH_CHECK.equals(action)) {
            if (isBusinessHours()) {
                launchKioskHealthCheck(context);
            }
            scheduleHealthCheck(context); // always reschedule — business-hours gate is in onReceive
        }
    }

    // Called on every KioskWebViewActivity start and on boot — sets precise one-shot alarms
    // for tonight's occurrences. Each alarm reschedules itself for the next day when it fires.
    // Using setExact (not setInexactRepeating) so alarms fire within ~1 min of target,
    // not batched by Android which can delay setInexactRepeating by 2-3 hours.
    static void schedule(Context context) {
        setExactAlarm(context, ACTION_STANDBY, 1, 20, 30);
        setExactAlarm(context, ACTION_WAKE,    2,  7, 30);
        setExactAlarm(context, ACTION_RESTART, 3,  6,  0);
        scheduleHealthCheck(context);
    }

    static void scheduleHealthCheck(Context context) {
        android.app.AlarmManager am = (android.app.AlarmManager)
            context.getSystemService(android.content.Context.ALARM_SERVICE);
        if (am == null) return;
        int flags = android.os.Build.VERSION.SDK_INT >= 23
            ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(context, 4,
            new android.content.Intent(ACTION_HEALTH_CHECK).setClass(context, ScheduleReceiver.class), flags);
        long triggerAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private static boolean isBusinessHours() {
        java.util.Calendar bkk = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Bangkok"));
        int h = bkk.get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 8 && h < 20;
    }

    private static void launchKioskHealthCheck(Context context) {
        try {
            Intent i = new Intent(context, KioskWebViewActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            i.putExtra("health_check", true);
            context.startActivity(i);
        } catch (Exception ignored) {}
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

    private static void logAlarmEvent(final Context context, final String event) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Install Conscrypt so TLS 1.2 works on Android 4.4 even when the
                    // main app hasn't run yet (alarm fires from a fresh process).
                    try { Security.insertProviderAt(Conscrypt.newProvider(), 1); } catch (Throwable ignored) {}

                    SharedPreferences prefs = context.getSharedPreferences("ris_kiosk_prefs", Context.MODE_PRIVATE);
                    String room = prefs.getString("room_email", "");
                    if (room.isEmpty()) return;
                    String roomName = prefs.getString("room_name", "");
                    String apkVer = "";
                    try {
                        apkVer = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                    } catch (Exception ignored) {}

                    String json = "{\"room\":\"" + room
                        + "\",\"roomname\":\"" + roomName
                        + "\",\"event\":\"" + event
                        + "\",\"apkVersion\":\"" + apkVer + "\"}";

                    OkHttpClient client = new OkHttpClient();
                    RequestBody body = RequestBody.create(
                        MediaType.parse("application/json"), json);
                    Request req = new Request.Builder()
                        .url("https://ris-display.ris-display.workers.dev/api/alarm")
                        .post(body)
                        .build();
                    client.newCall(req).execute().close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    static void launchStandby(Context context) {
        try {
            Intent i = new Intent(context, StandbyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }
}

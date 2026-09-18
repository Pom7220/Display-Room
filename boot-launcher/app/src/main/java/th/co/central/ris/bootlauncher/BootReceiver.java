package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import java.security.Security;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.conscrypt.Conscrypt;

public class BootReceiver extends BroadcastReceiver {

    private static final long BOOT_DELAY_MS = 90000; // 90s — let Android + MEET IN TOUCH settle

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;

        try {
            Intent svc = new Intent(context, ForegroundWatchService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {}

        if (hasLgKioskMode(context)) {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm disable me.exzy.meetingroom/.SystemBroadcastReceiver"});
            } catch (Exception ignored) {}
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm clear com.lge.lgkioskmode"});
            } catch (Exception ignored) {}
        }

        // All post-boot logic on a background thread so we can make a blocking HTTP call
        // to fix the clock before deciding standby vs wake.
        new Thread(new Runnable() { @Override public void run() {
            // On API 21+ (Lenovo/Android 10): firmware writes LOCAL time to RTC on software
            // shutdown. Android reads it as UTC on next boot → clock is 7h ahead.
            // Fix: POST cold_boot to server, read the authoritative Date header, set clock.
            // On API < 21 (LG): no RTC bug; use existing sync log method.
            if (Build.VERSION.SDK_INT >= 21) {
                fixClockAndLogBoot(context);
            } else {
                ScheduleReceiver.logAlarmEventSync(context, "cold_boot");
            }

            // Calendar.getInstance() now reads the corrected clock.
            Calendar now = Calendar.getInstance();
            int hour = now.get(Calendar.HOUR_OF_DAY);
            int day  = now.get(Calendar.DAY_OF_WEEK);
            boolean isWeekend     = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
            boolean isOfficeHours = !isWeekend && hour >= 7 && hour < 20;

            if (isOfficeHours) {
                try { Thread.sleep(BOOT_DELAY_MS); } catch (InterruptedException e) {}
                launchWebView(context);
            } else {
                ScheduleReceiver.launchStandby(context);
            }

            // Re-register alarms lost on reboot (uses corrected clock for nextOccurrence).
            ScheduleReceiver.schedule(context, hasLgKioskMode(context));
        }}).start();
    }

    // POST cold_boot event and fix system clock from the server's Date response header.
    // Combines log + clock fix in one HTTP round-trip. Retries up to 3x (10 s apart).
    private static void fixClockAndLogBoot(Context context) {
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
            + "\",\"event\":\"cold_boot\""
            + ",\"apkVersion\":\"" + apkVer + "\"}";
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt == 0) {
                    Log.d("BootReceiver", "clock before fix: " + new java.util.Date(System.currentTimeMillis()));
                }
                if (attempt > 0) Thread.sleep(10000);
                Response resp = new OkHttpClient().newCall(new Request.Builder()
                    .url("https://ris-display.ris-display.workers.dev/api/alarm")
                    .post(body).build()).execute();
                String dateHeader = resp.header("Date");
                Log.d("BootReceiver", "server Date header: " + dateHeader);
                resp.close();
                if (dateHeader == null) return;

                // Parse UTC time from RFC 7231 Date header, e.g. "Thu, 17 Sep 2026 08:16:36 GMT"
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                java.util.Date serverUtc = sdf.parse(dateHeader);
                if (serverUtc == null) return;

                // Format as BKK local time for `su 0 date MMDDHHmmYYYY.ss`
                Calendar bkk = Calendar.getInstance(TimeZone.getTimeZone("Asia/Bangkok"));
                bkk.setTimeInMillis(serverUtc.getTime());
                String dateStr = String.format(Locale.US, "%02d%02d%02d%02d%04d.%02d",
                    bkk.get(Calendar.MONTH) + 1,
                    bkk.get(Calendar.DAY_OF_MONTH),
                    bkk.get(Calendar.HOUR_OF_DAY),
                    bkk.get(Calendar.MINUTE),
                    bkk.get(Calendar.YEAR),
                    bkk.get(Calendar.SECOND));
                Log.d("BootReceiver", "setting clock to BKK: " + dateStr);
                Runtime.getRuntime().exec(new String[]{"su", "0", "date", dateStr}).waitFor();
                Log.d("BootReceiver", "clock after fix: " + new java.util.Date(System.currentTimeMillis()));
                return;
            } catch (Exception e) {
                Log.e("BootReceiver", "fixClockAndLogBoot attempt " + attempt + " failed: " + e.getMessage());
            }
        }
    }

    static boolean hasLgKioskMode(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.lge.lgkioskmode", 0);
            return true;
        } catch (Exception e) {
            return false;
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

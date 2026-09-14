package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

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

        // Check current time to decide which activity to launch
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int day  = now.get(Calendar.DAY_OF_WEEK);
        boolean isWeekend    = (day == Calendar.SATURDAY || day == Calendar.SUNDAY);
        int minute = now.get(Calendar.MINUTE);
        boolean isOfficeHours = !isWeekend && (hour > 7 || (hour == 7 && minute >= 30)) && hour < 20;

        if (isOfficeHours) {
            // Delay so MEET IN TOUCH can open first, then our WebView takes over
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(BOOT_DELAY_MS); } catch (InterruptedException e) {}
                    launchWebView(context);
                }
            }).start();
        } else {
            // Off-hours or weekend — go straight to standby (no delay needed)
            ScheduleReceiver.launchStandby(context);
        }

        // Re-register alarms lost on reboot
        ScheduleReceiver.schedule(context);

        // Configure LGKioskMode daily 06:00 cold-reboot (persists across boots)
        sendLgRebootSchedule(context);

        // OTA check on boot — replaces the old ACTION_RESTART 06:00 handler (weekdays only)
        if (!isWeekend) {
            final Context ctx = context;
            new Thread(new Runnable() { @Override public void run() {
                ScheduleReceiver.logAlarmEventSync(ctx, "restart");
                UpdateChecker.silentInstall(ctx, null, null);
            }}).start();
        }
    }

    private static void sendLgRebootSchedule(Context context) {
        try {
            Intent rb = new Intent("com.lge.signage.intent.action.RB");
            rb.putExtra("KEY_ON_OFF", true);
            rb.putExtra("KEY_HOUR", 6);
            rb.putExtra("KEY_MINUTE", 0);
            context.sendBroadcast(rb);
        } catch (Exception ignored) {}
    }

    static void launchWebView(Context context) {
        try {
            Intent intent = new Intent(context, KioskWebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }
}

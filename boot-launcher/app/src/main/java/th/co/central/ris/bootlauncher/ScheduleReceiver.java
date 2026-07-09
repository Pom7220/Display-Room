package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

public class ScheduleReceiver extends BroadcastReceiver {

    static final String ACTION_STANDBY = "th.co.central.ris.bootlauncher.ACTION_STANDBY";
    static final String ACTION_WAKE    = "th.co.central.ris.bootlauncher.ACTION_WAKE";
    static final String ACTION_RESTART = "th.co.central.ris.bootlauncher.ACTION_RESTART";

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

    static void launchStandby(Context context) {
        try {
            Intent i = new Intent(context, StandbyActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {}
    }
}

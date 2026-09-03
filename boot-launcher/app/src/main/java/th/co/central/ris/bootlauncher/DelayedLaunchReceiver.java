package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires 5 seconds after MY_PACKAGE_REPLACED (scheduled by RestartReceiver via AlarmManager).
 * By this point the package manager has fully settled and system UI is stable, so
 * startActivity() gets proper window focus and IMMERSIVE_STICKY engages correctly.
 */
public class DelayedLaunchReceiver extends BroadcastReceiver {

    static final String ACTION = "th.co.central.ris.bootlauncher.ACTION_DELAYED_LAUNCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        Intent launch = new Intent(context, KioskWebViewActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
    }
}

package th.co.central.ris.bootlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Relaunches the kiosk after a silent OTA install (pm install -r).
 * MY_PACKAGE_REPLACED is sent to the newly installed version once
 * the package manager finishes replacing the APK — no reboot needed.
 */
public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Intent launch = new Intent(context, KioskWebViewActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }
}

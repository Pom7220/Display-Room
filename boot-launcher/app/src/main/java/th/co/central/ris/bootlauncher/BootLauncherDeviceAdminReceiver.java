package th.co.central.ris.bootlauncher;

import android.app.admin.DeviceAdminReceiver;

public class BootLauncherDeviceAdminReceiver extends DeviceAdminReceiver {
    // No callbacks needed — registration enables DevicePolicyManager.reboot() on API 21+.
}

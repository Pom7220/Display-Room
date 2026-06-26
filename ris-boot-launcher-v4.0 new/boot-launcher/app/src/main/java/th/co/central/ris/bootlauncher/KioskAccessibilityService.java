package th.co.central.ris.bootlauncher;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service that auto-taps the center of the screen
 * when Chrome opens in browser mode (to trigger fullscreen).
 *
 * Also available as a remote command from admin dashboard.
 *
 * On Android 4.4, AccessibilityService.performGlobalAction and
 * dispatchGesture are not available. Instead we use the service
 * to detect when Chrome is in the foreground and simulate a tap
 * via instrumentation. For Android 4.4 the simplest approach is
 * to use Runtime exec with input tap command (requires no root on
 * most Android 4.4 devices for accessibility purposes).
 */
public class KioskAccessibilityService extends AccessibilityService {

    private static KioskAccessibilityService instance;
    private Handler handler = new Handler();
    private boolean tapScheduled = false;

    public static KioskAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Detect Chrome window opening
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = event.getPackageName() != null ?
                event.getPackageName().toString() : "";
            if ("com.android.chrome".equals(pkg) && !tapScheduled) {
                // Chrome just opened — schedule a tap after 2 seconds
                // (let the page start loading first)
                tapScheduled = true;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        performCenterTap();
                        tapScheduled = false;
                    }
                }, 2000);
            }
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /**
     * Simulate tap at screen center using 'input tap' shell command.
     * Works on Android 4.4 without root for accessibility services.
     */
    public void performCenterTap() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int cx = dm.widthPixels / 2;
            int cy = dm.heightPixels / 2;
            Runtime.getRuntime().exec(
                new String[]{"input", "tap", String.valueOf(cx), String.valueOf(cy)}
            );
        } catch (Exception e) {
            // Fallback: use performGlobalAction if available
            try {
                performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Exception e2) {}
        }
    }

    /**
     * Called remotely from admin dashboard command 'auto_tap'
     */
    public static boolean triggerTap() {
        if (instance != null) {
            instance.performCenterTap();
            return true;
        }
        return false;
    }
}

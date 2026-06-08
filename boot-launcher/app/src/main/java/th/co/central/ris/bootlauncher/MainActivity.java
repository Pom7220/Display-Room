package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show brief confirmation toast
        Toast.makeText(this,
            "RIS Kiosk Launcher installed.\nChrome will auto-launch on next reboot.",
            Toast.LENGTH_LONG).show();

        // Close immediately — this app has no UI
        finish();
    }
}

package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.security.KeyChain;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Setup Activity for RIS Room Display kiosk.
 *
 * Provides:
 * 1. SSL certificate installer — bundles ISRG Root X1 (Let's Encrypt root)
 *    and installs it as a trusted CA on Android 4.4. This eliminates the
 *    Chrome SSL warning for GitHub Pages on old Android devices.
 *
 * 2. Manual Chrome launcher — for testing or first-time setup.
 *
 * After first setup, the BootReceiver handles auto-launch on every reboot.
 */
public class MainActivity extends Activity {

    private static final String DISPLAY_URL = "https://ris-display.ris-display.workers.dev/";
    private static final String CERT_FILENAME = "isrgrootx1.crt";
    private static final int CERT_INSTALL_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen — no title bar, no status bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // Install Certificate button
        Button btnCert = (Button) findViewById(R.id.btn_install_cert);
        btnCert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installCertificate();
            }
        });

        // Launch Display button
        Button btnLaunch = (Button) findViewById(R.id.btn_launch);
        btnLaunch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchChrome();
            }
        });

        // Also copy cert to Downloads button (alternative method)
        Button btnCopy = (Button) findViewById(R.id.btn_copy_cert);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCertToDownloads();
            }
        });

        // Show version
        TextView tvVersion = (TextView) findViewById(R.id.tv_version);
        tvVersion.setText("RIS Kiosk Launcher v2.0\nth.co.central.ris.bootlauncher");
    }

    /**
     * Method 1: Install cert using Android's KeyChain API.
     * Opens the system certificate installer dialog.
     * User taps "OK" and names the cert → installed as trusted CA.
     *
     * NOTE: Android 4.4 requires setting a screen lock (PIN/pattern)
     * when installing user certificates. This is an Android security
     * requirement that cannot be bypassed without root.
     */
    private void installCertificate() {
        try {
            // Read cert from bundled raw resource
            InputStream is = getResources().openRawResource(R.raw.isrgrootx1);
            byte[] certBytes = new byte[is.available()];
            is.read(certBytes);
            is.close();

            // Launch Android's built-in certificate installer
            Intent installIntent = KeyChain.createInstallIntent();
            installIntent.putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes);
            installIntent.putExtra(KeyChain.EXTRA_NAME, "ISRG Root X1 (Let's Encrypt)");
            startActivityForResult(installIntent, CERT_INSTALL_REQUEST);

        } catch (Exception e) {
            Toast.makeText(this,
                    "Error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Method 2: Copy cert to /sdcard/Download/ so user can install
     * manually via Settings → Security → Install from storage.
     * Useful if Method 1 fails for any reason.
     */
    private void copyCertToDownloads() {
        try {
            InputStream is = getResources().openRawResource(R.raw.isrgrootx1);
            byte[] certBytes = new byte[is.available()];
            is.read(certBytes);
            is.close();

            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            File certFile = new File(downloadDir, CERT_FILENAME);
            FileOutputStream fos = new FileOutputStream(certFile);
            fos.write(certBytes);
            fos.close();

            Toast.makeText(this,
                    "Certificate saved to Downloads/" + CERT_FILENAME
                            + "\n\nGo to: Settings → Security → Install from storage",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    "Error copying cert: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CERT_INSTALL_REQUEST) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this,
                        "✓ Certificate installed! Chrome will no longer show SSL warnings.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "Certificate installation was cancelled.\n"
                                + "You can try again or use 'Copy to Downloads' method.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Launch Chrome to the Display-Room URL.
     */
    private void launchChrome() {
        try {
            String url = DISPLAY_URL + "?nocache=" + System.currentTimeMillis();
            Intent chromeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            chromeIntent.setPackage("com.android.chrome");
            chromeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(chromeIntent);
            finish();
        } catch (Exception e) {
            // Chrome not found — try default browser
            try {
                String url = DISPLAY_URL + "?nocache=" + System.currentTimeMillis();
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                finish();
            } catch (Exception e2) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

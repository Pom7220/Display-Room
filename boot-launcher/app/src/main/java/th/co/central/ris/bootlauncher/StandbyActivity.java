package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.conscrypt.Conscrypt;

import java.security.Security;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class StandbyActivity extends Activity {

    private static final String PREFS_NAME      = "ris_kiosk_prefs";
    private static final String HEARTBEAT_URL   = "https://ris-display.ris-display.workers.dev/api/heartbeat";
    private static final long   HB_INTERVAL_MS  = 20 * 60 * 1000L; // 20 minutes
    private static final long   DIM_DELAY_MS    = 30 * 1000L;       // 30 seconds after touch

    private static final OkHttpClient HB_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build();

    private Handler  handler;
    private TextView hintText;
    private boolean  isDimmed = true;

    private final Runnable dimRunnable = new Runnable() {
        @Override public void run() { dim(); }
    };

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            sendHeartbeat();
            handler.postDelayed(this, HB_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        applyRotation();
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // No FLAG_KEEP_SCREEN_ON — let Android system timeout also apply

        handler = new Handler();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        hintText = new TextView(this);
        hintText.setText("Outside office hours\nResumes 08:00 weekdays");
        hintText.setTextColor(Color.parseColor("#3a4d6b"));
        hintText.setTextSize(14);
        hintText.setGravity(Gravity.CENTER);
        hintText.setVisibility(View.GONE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        root.addView(hintText, lp);
        setContentView(root);

        dim();

        // Initial heartbeat after 30s, then every 20 min
        handler.postDelayed(heartbeatRunnable, 30_000L);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (isDimmed) {
                brighten();
            } else {
                handler.removeCallbacks(dimRunnable);
                handler.postDelayed(dimRunnable, DIM_DELAY_MS);
            }
        }
        return true;
    }

    private void dim() {
        isDimmed = true;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        hintText.setVisibility(View.GONE);
    }

    private void brighten() {
        isDimmed = false;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        hintText.setVisibility(View.VISIBLE);
        handler.removeCallbacks(dimRunnable);
        handler.postDelayed(dimRunnable, DIM_DELAY_MS);
    }

    private void applyRotation() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean rotated = p.getBoolean("screen_rotated", false);
        setRequestedOrientation(rotated
            ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void sendHeartbeat() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String room     = p.getString("room_email", "");
        final String roomname = p.getString("room_name",  "");
        if (room.isEmpty()) return;

        String version = "unknown";
        try {
            version = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        final String ver = version;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    try { Security.insertProviderAt(Conscrypt.newProvider(), 1); } catch (Throwable ignored) {}
                    JSONObject body = new JSONObject();
                    body.put("room",       room);
                    body.put("roomname",   roomname);
                    body.put("status",     "sleep");
                    body.put("launchMode", "standby");
                    body.put("apkVersion", ver);
                    body.put("uptime",     0);

                    RequestBody rb = RequestBody.create(
                        MediaType.parse("application/json"),
                        body.toString());
                    Request req = new Request.Builder()
                        .url(HEARTBEAT_URL)
                        .post(rb)
                        .build();
                    HB_CLIENT.newCall(req).execute().close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @Override
    public void onBackPressed() { /* block back in kiosk mode */ }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(dimRunnable);
        handler.removeCallbacks(heartbeatRunnable);
        super.onDestroy();
    }
}

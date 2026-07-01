package th.co.central.ris.bootlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Room picker for the RIS Kiosk Launcher.
 * Shows all 12 RIS rooms — tap one to assign this tablet to that room.
 * Selection is saved in SharedPreferences and used by BootReceiver
 * to build the URL with room config params on every boot.
 *
 * After picking a room, tap "Launch Display" to open Chrome immediately.
 */
public class MainActivity extends Activity {

    private static final String BASE_URL =
        "https://ris-display.ris-display.workers.dev/";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String PREFS_NAME = "ris_kiosk_prefs";

    // All 12 RIS rooms
    private static final String[][] ROOMS = {
        // {name, email, seats, zone, approval}
        {"Espresso",    "risespresso@central.co.th",    "8-12", "lobby",  "no"},
        {"Doppio",      "risdoppio@central.co.th",      "6-8",  "lobby",  "no"},
        {"Cappuccino",  "riscappuccino@central.co.th",  "6",    "lobby",  "no"},
        {"Americano",   "risamericano@central.co.th",   "6",    "lobby",  "no"},
        {"Lungo",       "rislungo@central.co.th",       "4",    "lobby",  "no"},
        {"Ristretto",   "risristretto@central.co.th",   "4",    "lobby",  "no"},
        {"Macchiato",   "rismacchiato@central.co.th",   "5-8",  "office", "yes"},
        {"Viennese",    "risviennese@central.co.th",    "6",    "office", "no"},
        {"Decaffinato", "risdecaffeinato@central.co.th","6",    "office", "no"},
        {"Latte",       "rislatte@central.co.th",       "6",    "office", "no"},
        {"Mocha",       "rismocha@central.co.th",       "6",    "office", "no"},
        {"Affogato",    "risaffogato@central.co.th",    "6",    "office", "no"},
    };

    private SharedPreferences prefs;
    private String selectedEmail = "";
    private String selectedName = "";
    private View selectedView = null;
    private TextView statusText;
    private Button launchBtn;
    private EditText tabletKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        selectedEmail = prefs.getString("room_email", "");
        selectedName = prefs.getString("room_name", "");

        // Build UI programmatically (no XML layout needed)
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0a0a12"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));

        // Header
        TextView header = new TextView(this);
        header.setText("\u2B21 RIS KIOSK");
        header.setTextColor(Color.parseColor("#3b9eff"));
        header.setTextSize(22);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        root.addView(header);

        TextView sub = new TextView(this);
        sub.setText("Select the room for this tablet");
        sub.setTextColor(Color.parseColor("#6b82a8"));
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        // Status
        statusText = new TextView(this);
        updateStatus();
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 0, 0, dp(8));
        root.addView(statusText);

        // Tablet key input
        TextView keyLabel = new TextView(this);
        keyLabel.setText("Tablet Key (from admin dashboard)");
        keyLabel.setTextColor(Color.parseColor("#6b82a8"));
        keyLabel.setTextSize(11);
        keyLabel.setPadding(0, 0, 0, dp(4));
        root.addView(keyLabel);

        tabletKeyInput = new EditText(this);
        tabletKeyInput.setHint("RIS-TABLET-KEY2026");
        tabletKeyInput.setTextColor(Color.WHITE);
        tabletKeyInput.setHintTextColor(Color.parseColor("#3a4d6b"));
        tabletKeyInput.setBackgroundColor(Color.parseColor("#111d35"));
        tabletKeyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        tabletKeyInput.setTextSize(13);
        tabletKeyInput.setSingleLine(true);
        tabletKeyInput.setText(prefs.getString("admin_key", ""));
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keyLp.setMargins(0, 0, 0, dp(16));
        tabletKeyInput.setLayoutParams(keyLp);
        root.addView(tabletKeyInput);

        // Lobby header
        root.addView(makeZoneHeader("LOBBY AREA — rooms 1-6"));

        // Lobby rooms (1-6)
        LinearLayout lobbyGrid = new LinearLayout(this);
        lobbyGrid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 6; i += 2) {
            lobbyGrid.addView(makeRoomRow(i, i + 1));
        }
        lobbyGrid.setPadding(0, 0, 0, dp(12));
        root.addView(lobbyGrid);

        // Office header
        root.addView(makeZoneHeader("IN-OFFICE AREA — rooms 7-12"));

        // Office rooms (7-12)
        LinearLayout officeGrid = new LinearLayout(this);
        officeGrid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 6; i < 12; i += 2) {
            officeGrid.addView(makeRoomRow(i, i + 1));
        }
        officeGrid.setPadding(0, 0, 0, dp(20));
        root.addView(officeGrid);

        // Accessibility service setup button
        Button accessBtn = new Button(this);
        accessBtn.setText("\u2699 Enable Auto-tap (Accessibility)");
        accessBtn.setTextColor(Color.parseColor("#ff9500"));
        accessBtn.setBackgroundColor(Color.parseColor("#1a1200"));
        accessBtn.setPadding(dp(8), dp(12), dp(8), dp(12));
        accessBtn.setTextSize(13);
        accessBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(MainActivity.this,
                    "Find 'RIS Kiosk Auto-tap' and enable it",
                    Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessBtn);

        // Launch button
        launchBtn = new Button(this);
        launchBtn.setText("\uD83D\uDE80  Launch Room Display");
        launchBtn.setTextColor(Color.parseColor("#040810"));
        launchBtn.setTextSize(15);
        launchBtn.setTypeface(null, Typeface.BOLD);
        launchBtn.setBackgroundColor(Color.parseColor("#00d68f"));
        launchBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        launchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedEmail.length() == 0) {
                    Toast.makeText(MainActivity.this, "Select a room first", Toast.LENGTH_SHORT).show();
                    return;
                }
                launchChrome();
            }
        });
        root.addView(launchBtn);

        // Version
        TextView ver = new TextView(this);
        ver.setText("\nRIS Kiosk Launcher v4.0\nth.co.central.ris.bootlauncher");
        ver.setTextColor(Color.parseColor("#3a4d6b"));
        ver.setTextSize(10);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(16), 0, 0);
        root.addView(ver);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView makeZoneHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#00e5c8"));
        tv.setTextSize(11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(4), 0, dp(8));
        return tv;
    }

    private LinearLayout makeRoomRow(int idx1, int idx2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(0, 0, dp(8), dp(8));

        View btn1 = makeRoomButton(idx1);
        btn1.setLayoutParams(lp);
        row.addView(btn1);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.setMargins(dp(8), 0, 0, dp(8));

        View btn2 = makeRoomButton(idx2);
        btn2.setLayoutParams(lp2);
        row.addView(btn2);

        return row;
    }

    private View makeRoomButton(final int idx) {
        final String[] room = ROOMS[idx];

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#111d35"));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView name = new TextView(this);
        name.setText(room[0]);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        card.addView(name);

        TextView info = new TextView(this);
        info.setText(room[2] + " seats \u00B7 " + room[3]);
        info.setTextColor(Color.parseColor("#6b82a8"));
        info.setTextSize(10);
        card.addView(info);

        if ("yes".equals(room[4])) {
            TextView appr = new TextView(this);
            appr.setText("\u26A0 Approval required");
            appr.setTextColor(Color.parseColor("#ffb340"));
            appr.setTextSize(10);
            appr.setPadding(0, dp(2), 0, 0);
            card.addView(appr);
        }

        // Highlight if this is the currently selected room
        if (room[1].equals(selectedEmail)) {
            card.setBackgroundColor(Color.parseColor("#1a3a5c"));
            selectedView = card;
        }

        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Deselect previous
                if (selectedView != null) {
                    selectedView.setBackgroundColor(Color.parseColor("#111d35"));
                }

                // Select this
                v.setBackgroundColor(Color.parseColor("#1a3a5c"));
                selectedView = v;

                selectedEmail = room[1];
                selectedName = room[0];
                String tabletKey = tabletKeyInput.getText().toString().trim();
                // Save to SharedPreferences
                prefs.edit()
                    .putString("room_email", selectedEmail)
                    .putString("room_name", selectedName)
                    .putString("admin_key", tabletKey)
                    .apply();

                updateStatus();
                Toast.makeText(MainActivity.this,
                    room[0] + " selected — will persist across reboots",
                    Toast.LENGTH_SHORT).show();
            }
        });

        return card;
    }

    private void updateStatus() {
        if (selectedEmail.length() > 0) {
            statusText.setText("\u2705 Assigned: " + selectedName + "\n" + selectedEmail);
            statusText.setTextColor(Color.parseColor("#00d68f"));
        } else {
            statusText.setText("No room assigned — tap a room below");
            statusText.setTextColor(Color.parseColor("#ff9500"));
        }
    }

    private void launchChrome() {
        if (selectedEmail.length() == 0) {
            Toast.makeText(this, "Tap a room card first", Toast.LENGTH_LONG).show();
            return;
        }
        // Launch Chrome with room URL
        BootReceiver.launchChrome(this);
        finish();
    }

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (val * density + 0.5f);
    }
}

package com.kickrclick.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The one screen: connection status, the ERG target with − and +, live numbers and the log. */
public final class MainActivity extends Activity implements Hub.View {
    private static final int ASK_PERMISSIONS = 1;
    private static final int ASK_BLUETOOTH = 2;

    static final int BG = 0xFF0B0F14;
    static final int CARD = 0xFF151B23;
    static final int TEXT = 0xFFEEF2F6;
    static final int MUTED = 0xFF8B97A4;
    static final int ACCENT = 0xFFFFB547;
    static final int GOOD = 0xFF3FB950;
    static final int WORKING = 0xFFD29922;
    static final int BAD = 0xFFF85149;
    static final int RIPPLE = 0x33FFFFFF;

    private Hub hub;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView trainerChip;
    private TextView clickChip;
    private TextView hint;
    private TextView modeErg;
    private TextView modeGears;
    private TextView targetLabel;
    private TextView targetText;
    private TextView unitText;
    private TextView confirmedText;
    private TextView stepText;
    private TextView powerText;
    private TextView cadenceText;
    private TextView avgText;
    private TextView speedText;
    private TextView timeText;
    private TextView logToggle;
    private TextView logText;
    private boolean logOpen;
    private int shownLogVersion = -1;
    private int shownMode = -1;
    private String blocker;
    private Runnable blockerAction;
    private boolean askedPermissions;
    private boolean askedBluetooth;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        hub = Hub.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(buildScreen());
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        setContentView(buildScreen()); // folded, unfolded or turned: lay out again for the new size
        shownLogVersion = -1;
        shownMode = -1;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.setView(this);
        ensureReady();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        hub.setView(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) hub.stop();
    }

    @Override
    public void hubChanged() {
        render();
    }

    // ------------------------------------------------------------------ permissions and Bluetooth

    private String[] missingPermissions() {
        String[] wanted = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing.toArray(new String[0]);
    }

    private void ensureReady() {
        String[] missing = missingPermissions();
        if (missing.length > 0) {
            if (!askedPermissions) {
                askedPermissions = true;
                requestPermissions(missing, ASK_PERMISSIONS);
            }
            setBlocker("The app needs the “Nearby devices” permission to find the KICKR and the Click. Tap here to allow it.",
                    new Runnable() {
                        @Override
                        public void run() {
                            askAgainOrOpenSettings();
                        }
                    });
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setBlocker("This phone has no Bluetooth.", null);
            return;
        }
        if (!adapter.isEnabled()) {
            if (!askedBluetooth) {
                askedBluetooth = true;
                askToTurnOnBluetooth();
            }
            setBlocker("Bluetooth is off. Tap here to turn it on.", new Runnable() {
                @Override
                public void run() {
                    askToTurnOnBluetooth();
                }
            });
            return;
        }
        setBlocker(null, null);
        hub.start();
    }

    @SuppressWarnings("deprecation")
    private void askToTurnOnBluetooth() {
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ASK_BLUETOOTH);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private void askAgainOrOpenSettings() {
        String[] missing = missingPermissions();
        if (missing.length == 0) {
            ensureReady();
            return;
        }
        boolean canAsk = false;
        for (String p : missing) canAsk |= shouldShowRequestPermissionRationale(p);
        if (canAsk) {
            requestPermissions(missing, ASK_PERMISSIONS);
        } else {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean all = results.length > 0;
        for (int r : results) all &= r == PackageManager.PERMISSION_GRANTED;
        hub.log(all ? "Nearby devices permission allowed" : "Nearby devices permission not allowed");
        ensureReady();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ASK_BLUETOOTH) ensureReady();
    }

    private void setBlocker(String text, Runnable action) {
        blocker = text;
        blockerAction = action;
        render();
    }

    // ------------------------------------------------------------------ the screen

    @SuppressWarnings("deprecation")
    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Column col = new Column(this, dp(560));
        col.setPadding(dp(16), dp(10), dp(16), dp(28));
        outer.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Title
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("KICKR Click", 22, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(text("v" + hub.versionName(), 12, MUTED, false));
        col.addView(header, matchWrap(0));

        // Connection chips
        LinearLayout chips = row();
        trainerChip = chip();
        clickChip = chip();
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        left.rightMargin = dp(5);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        right.leftMargin = dp(5);
        chips.addView(trainerChip, left);
        chips.addView(clickChip, right);
        col.addView(chips, matchWrap(12));

        // Advice line
        hint = text("", 14, ACCENT, false);
        hint.setLineSpacing(0, 1.15f);
        hint.setPadding(dp(14), dp(10), dp(14), dp(10));
        hint.setBackground(rounded(0xFF2A2213, dp(14)));
        hint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (blocker != null && blockerAction != null) blockerAction.run();
            }
        });
        col.addView(hint, matchWrap(12));

        // ERG or free ride
        LinearLayout modes = row();
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(rounded(CARD, dp(16)));
        modeErg = modeButton("ERG", Hub.MODE_ERG);
        modeGears = modeButton("Free ride \u00b7 gears", Hub.MODE_GEARS);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        m1.rightMargin = dp(2);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        m2.leftMargin = dp(2);
        modes.addView(modeErg, m1);
        modes.addView(modeGears, m2);
        col.addView(modes, matchWrap(12));

        // Target power or gear
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(CARD, dp(22)));
        targetLabel = text("ERG TARGET", 12, MUTED, true);
        targetLabel.setLetterSpacing(0.12f);
        card.addView(targetLabel);
        LinearLayout value = row();
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        targetText = text("150", 104, ACCENT, true);
        digits(targetText);
        targetText.setIncludeFontPadding(false);
        value.addView(targetText);
        unitText = text(" W", 30, MUTED, true);
        value.addView(unitText);
        card.addView(value);
        confirmedText = text("", 14, MUTED, false);
        confirmedText.setGravity(Gravity.CENTER);
        card.addView(confirmedText);
        stepText = text("", 14, TEXT, false);
        stepText.setGravity(Gravity.CENTER);
        stepText.setPadding(dp(14), dp(8), dp(14), dp(8));
        stepText.setBackground(button(0xFF1F2731, dp(12)));
        stepText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.cycleStep();
            }
        });
        LinearLayout.LayoutParams stepLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stepLp.topMargin = dp(10);
        card.addView(stepText, stepLp);
        col.addView(card, matchWrap(12));

        // − and +
        LinearLayout buttons = row();
        TextView minus = bigButton("−", "Easier", -1);
        TextView plus = bigButton("+", "Harder", +1);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(96), 1);
        bl.rightMargin = dp(5);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(0, dp(96), 1);
        br.leftMargin = dp(5);
        buttons.addView(minus, bl);
        buttons.addView(plus, br);
        col.addView(buttons, matchWrap(10));

        // Live numbers
        LinearLayout r1 = row();
        powerText = stat(r1, "POWER · 3 S", true);
        cadenceText = stat(r1, "CADENCE", false);
        col.addView(r1, matchWrap(10));
        LinearLayout r2 = row();
        avgText = stat(r2, "AVERAGE POWER", true);
        speedText = stat(r2, "SPEED", false);
        col.addView(r2, matchWrap(10));
        LinearLayout r3 = row();
        timeText = stat(r3, "RIDING TIME", false);
        col.addView(r3, matchWrap(10));

        // Log
        LinearLayout logBar = row();
        logToggle = smallButton("Show log");
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logOpen = !logOpen;
                shownLogVersion = -1;
                render();
            }
        });
        TextView copy = smallButton("Copy log");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
        TextView quit = smallButton("Quit");
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.stop();
                finish();
            }
        });
        LinearLayout.LayoutParams g1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        g1.rightMargin = dp(5);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        g2.leftMargin = dp(5);
        g2.rightMargin = dp(5);
        LinearLayout.LayoutParams g3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        g3.leftMargin = dp(5);
        logBar.addView(logToggle, g1);
        logBar.addView(copy, g2);
        logBar.addView(quit, g3);
        col.addView(logBar, matchWrap(18));

        logText = text("", 11, 0xFFB8C2CC, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(12), dp(10), dp(12), dp(10));
        logText.setBackground(rounded(0xFF10151B, dp(12)));
        col.addView(logText, matchWrap(10));

        TextView note = text("Leaving the app keeps it connected. Tap Quit to let go of the trainer.", 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        col.addView(note, matchWrap(14));

        // Keep clear of the status bar, navigation bar and camera cut-out.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(s.left, s.top, s.right, s.bottom);
                } else {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
        if (Build.VERSION.SDK_INT < 30) {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        return root;
    }

    private void render() {
        if (trainerChip == null) return;
        setChip(trainerChip, "TRAINER", hub.trainerStatus(), hub.trainerLevel());
        setChip(clickChip, "CLICK", hub.clickStatus(), hub.clickLevel());

        String advice = blocker != null ? blocker : hub.hint();
        hint.setText(advice == null ? "" : advice);
        hint.setVisibility(advice == null ? View.GONE : View.VISIBLE);
        hint.setClickable(blocker != null && blockerAction != null);

        boolean gears = hub.mode() == Hub.MODE_GEARS;
        if (shownMode != hub.mode()) {
            shownMode = hub.mode();
            styleMode(modeErg, !gears);
            styleMode(modeGears, gears);
        }
        targetLabel.setText(gears ? "GEAR \u00b7 ERG OFF" : "ERG TARGET");
        targetText.setText(String.valueOf(gears ? hub.gear() : hub.target()));
        unitText.setText(gears ? " / " + Gears.COUNT : " W");
        confirmedText.setText(hub.confirmation());
        stepText.setText("Step " + hub.step() + " W \u00b7 tap to change");
        stepText.setVisibility(gears ? View.GONE : View.VISIBLE);

        int p = hub.power3s();
        powerText.setText(withUnit(p < 0 ? "–" : String.valueOf(p), "W"));
        int c = hub.cadence();
        cadenceText.setText(withUnit(c < 0 ? "–" : String.valueOf(c), "rpm"));
        int a = hub.averagePower();
        avgText.setText(withUnit(a < 0 ? "–" : String.valueOf(a), "W"));
        double s = hub.speed();
        speedText.setText(withUnit(Double.isNaN(s) ? "–" : String.format(Locale.US, "%.1f", s), "km/h"));
        long t = hub.rideSeconds();
        timeText.setText(withUnit(String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60), null));

        logToggle.setText(logOpen ? "Hide log" : "Show log");
        logText.setVisibility(logOpen ? View.VISIBLE : View.GONE);
        if (logOpen && shownLogVersion != hub.logVersion()) {
            shownLogVersion = hub.logVersion();
            logText.setText(hub.logTail(120));
        }
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("KICKR Click log", hub.logText()));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Could not copy the log", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "Log copied. Paste it into the chat.", Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ small view helpers

    /** A vertical column that stays centred and no wider than {@code maxWidth} on big screens. */
    static final class Column extends LinearLayout {
        private final int maxWidth;

        Column(android.content.Context context, int maxWidth) {
            super(context);
            this.maxWidth = maxWidth;
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = MeasureSpec.getSize(widthSpec);
            if (size > maxWidth) widthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private static void digits(TextView t) {
        t.setFontFeatureSettings("tnum");
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private RippleDrawable button(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), rounded(color, radius), null);
    }

    private TextView chip() {
        TextView t = text("", 15, TEXT, false);
        t.setPadding(dp(12), dp(9), dp(12), dp(10));
        t.setBackground(rounded(CARD, dp(14)));
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private void setChip(TextView chip, String name, String status, int level) {
        int color = level == 2 ? GOOD : level == 3 ? BAD : level == 1 ? WORKING : MUTED;
        SpannableStringBuilder b = new SpannableStringBuilder("●  ");
        b.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = b.length();
        b.append(name);
        b.setSpan(new ForegroundColorSpan(MUTED), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new RelativeSizeSpan(0.75f), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append('\n').append(status);
        chip.setText(b);
    }

    private TextView modeButton(String label, final int mode) {
        TextView b = text(label, 15, MUTED, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(11), dp(8), dp(11));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.setMode(mode);
            }
        });
        return b;
    }

    private void styleMode(TextView b, boolean selected) {
        b.setTextColor(selected ? BG : MUTED);
        b.setBackground(selected ? rounded(ACCENT, dp(12)) : button(CARD, dp(12)));
    }

    private TextView bigButton(String label, String description, final int direction) {
        TextView b = text(label, 46, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setBackground(button(CARD, dp(24)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(description);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hub.stepTarget(direction);
            }
        });
        return b;
    }

    private TextView smallButton(String label) {
        TextView b = text(label, 14, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        b.setBackground(button(CARD, dp(12)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView stat(LinearLayout row, String label, boolean leftOfPair) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(14), dp(10), dp(14), dp(12));
        cell.setBackground(rounded(CARD, dp(16)));
        TextView l = text(label, 11, MUTED, true);
        l.setLetterSpacing(0.08f);
        TextView v = text("–", 30, TEXT, true);
        digits(v);
        cell.addView(l);
        cell.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) lp.leftMargin = dp(5);
        if (leftOfPair) lp.rightMargin = dp(5);
        row.addView(cell, lp);
        return v;
    }

    private static CharSequence withUnit(String number, String unit) {
        SpannableStringBuilder b = new SpannableStringBuilder(number);
        if (unit != null) {
            int start = b.length();
            b.append(' ').append(unit);
            b.setSpan(new RelativeSizeSpan(0.5f), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(MUTED), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }
}

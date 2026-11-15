package com.kickrclick.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything that outlives the screen: finding the trainer and the Click, the rider's target,
 * the live numbers and the log. One instance per app process, used on the main thread only.
 */
@SuppressLint("MissingPermission")
final class Hub {
    interface View {
        void hubChanged();
    }

    static final int MIN_W = 50;
    static final int MAX_W = 500;
    static final int START_W = 150;
    static final int[] STEPS = {5, 10, 25};

    private static Hub instance;

    static Hub get(Context context) {
        if (instance == null) instance = new Hub(context.getApplicationContext());
        return instance;
    }

    final Context app;
    final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final BluetoothAdapter adapter;
    private View view;
    private boolean running;

    private int target = START_W;
    private int step;

    private Trainer trainer;
    private ClickPod click;
    private final Set<String> ignored = new HashSet<>();
    private final Set<String> seenZwift = new HashSet<>();
    private long trainerHoldUntil;
    private long clickHoldUntil;
    private long clickWatchUntil;
    private int trainerFailures;
    private int clickFailures;
    private long trainerMissingSince;

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private long lastScanStart = -100000;

    private final ArrayDeque<long[]> powerSamples = new ArrayDeque<>();
    private long powerAt = -100000;
    private long bikePowerAt = -100000;
    private long bikeCadenceAt = -100000;
    private long cadenceAt = -100000;
    private long speedAt = -100000;
    private double cadence;
    private double speed;
    private final Protocol.CrankCadence crank = new Protocol.CrankCadence();
    private long rideMs;
    private double powerSum;
    private int powerSeconds;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int logVersion;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Hub(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("kickr-click", Context.MODE_PRIVATE);
        step = prefs.getInt("step", 10);
        boolean known = false;
        for (int s : STEPS) known |= s == step;
        if (!known) step = 10;
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        recordCrashes();
        log("KICKR Click " + versionName() + " on " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        String crash = prefs.getString("crash", null);
        if (crash != null) {
            log("The app crashed last time:\n" + crash);
            prefs.edit().remove("crash").apply();
        }
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(bluetoothState, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(bluetoothState, filter);
    }

    // ------------------------------------------------------------------ lifecycle

    void setView(View v) {
        view = v;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) {
            updateScan();
            return;
        }
        running = true;
        rideMs = 0;
        powerSum = 0;
        powerSeconds = 0;
        trainerMissingSince = SystemClock.elapsedRealtime();
        log("Starting (target " + target + " W, step " + step + " W)");
        main.removeCallbacks(everySecond);
        main.postDelayed(everySecond, 1000);
        updateScan();
        changed();
    }

    void stop() {
        if (!running) return;
        running = false;
        log("Stopping: letting go of the trainer and the Click");
        main.removeCallbacks(everySecond);
        main.removeCallbacks(scanLater);
        main.removeCallbacks(watchEnd);
        stopScan();
        closeTrainer();
        closeClick();
        changed();
    }

    private void closeTrainer() {
        if (trainer == null) return;
        Trainer t = trainer;
        trainer = null;
        t.close();
    }

    private void closeClick() {
        if (click == null) return;
        ClickPod c = click;
        click = null;
        c.close();
    }

    // ------------------------------------------------------------------ the rider's target

    int target() {
        return target;
    }

    int step() {
        return step;
    }

    void stepTarget(int direction) {
        int next = Math.max(MIN_W, Math.min(MAX_W, target + direction * step));
        if (next == target) return;
        target = next;
        if (trainer != null) trainer.setTarget(target);
        changed();
    }

    void cycleStep() {
        int i = 0;
        for (int k = 0; k < STEPS.length; k++) if (STEPS[k] == step) i = k;
        step = STEPS[(i + 1) % STEPS.length];
        prefs.edit().putInt("step", step).apply();
        log("Step is now " + step + " W");
        changed();
    }

    void onClickButton(int bit) {
        String name = Protocol.buttonName(bit);
        if (bit == Protocol.BTN_PLUS) {
            stepTarget(+1);
            log("Click " + name + " → " + target + " W");
        } else if (bit == Protocol.BTN_B || bit == Protocol.BTN_MINUS) {
            stepTarget(-1);
            log("Click " + name + " → " + target + " W");
        } else {
            log("Click " + name + " (not used)");
        }
    }

    // ------------------------------------------------------------------ finding devices

    boolean bluetoothOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private final Runnable scanLater = new Runnable() {
        @Override
        public void run() {
            updateScan();
        }
    };

    private final Runnable watchEnd = new Runnable() {
        @Override
        public void run() {
            updateScan();
        }
    };

    private final Runnable scanRefresh = new Runnable() {
        @Override
        public void run() {
            stopScan();
            updateScan();
        }
    };

    private void updateScan() {
        main.removeCallbacks(scanLater);
        long now = SystemClock.elapsedRealtime();
        boolean want = running && bluetoothOn() && (trainer == null || click == null || now < clickWatchUntil);
        if (!want) {
            stopScan();
            return;
        }
        if (scanning) return;
        long wait = lastScanStart + 6500 - now; // Android allows 5 scan starts per 30 seconds
        if (wait > 0) {
            main.postDelayed(scanLater, wait);
            return;
        }
        startScan();
    }

    private void startScan() {
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                main.postDelayed(scanLater, 3000);
                return;
            }
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            lastScanStart = SystemClock.elapsedRealtime();
            main.removeCallbacks(scanRefresh);
            main.postDelayed(scanRefresh, 10 * 60 * 1000L); // Android slows down very long searches
            log("Searching for " + lookingFor());
        } catch (SecurityException e) {
            log("Cannot search: the Nearby devices permission is missing");
        } catch (RuntimeException e) {
            log("Cannot search: " + e);
            main.postDelayed(scanLater, 5000);
        }
    }

    private String lookingFor() {
        if (trainer == null && click == null) return "the trainer and the Click";
        if (trainer == null) return "the trainer";
        if (click == null) return "the Click";
        return "the Click after its restart";
    }

    private void stopScan() {
        main.removeCallbacks(scanRefresh);
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && bluetoothOn()) scanner.stopScan(scanCallback);
        } catch (RuntimeException ignored) {
            // Bluetooth already off
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onScan(result);
                }
            });
        }

        @Override
        public void onBatchScanResults(final List<ScanResult> results) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    for (ScanResult r : results) onScan(r);
                }
            });
        }

        @Override
        public void onScanFailed(final int errorCode) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (errorCode == SCAN_FAILED_ALREADY_STARTED) return;
                    scanning = false;
                    log("Search failed (code " + errorCode + "), trying again");
                    main.removeCallbacks(scanLater);
                    main.postDelayed(scanLater, 5000);
                }
            });
        }
    };

    private void onScan(ScanResult r) {
        if (!running) return;
        BluetoothDevice d = r.getDevice();
        ScanRecord rec = r.getScanRecord();
        if (d == null || rec == null) return;
        String address = d.getAddress();
        if (address == null || ignored.contains(address)) return;
        long now = SystemClock.elapsedRealtime();
        String name = rec.getDeviceName();
        byte[] zwift = rec.getManufacturerSpecificData(Protocol.ZWIFT_COMPANY_ID);
        int zwiftType = zwift != null && zwift.length > 0 ? zwift[0] & 0xFF : -1;

        if (zwiftType == Protocol.ZWIFT_CLICK_V2_RIGHT || zwiftType == Protocol.ZWIFT_CLICK_V1) {
            if (click != null) {
                // Seen advertising while we think it is connected: it restarted, so reconnect now.
                if (now < clickWatchUntil && address.equals(click.address()) && click.connectedForMs() > 2500) {
                    log("Click: it restarted, reconnecting");
                    closeClick();
                } else {
                    return;
                }
            }
            if (now >= clickHoldUntil && !busyConnecting()) connectClick(d, zwiftType, name);
            return;
        }
        if (zwiftType >= 0 && seenZwift.add(address)) {
            log("Seen a Zwift device, type 0x" + Integer.toHexString(zwiftType) + (name != null ? " \"" + name + "\"" : "")
                    + (zwiftType == Protocol.ZWIFT_CLICK_V2_LEFT ? " (the left Click pod only works inside Zwift, so it is not used)" : ""));
        }
        if (trainer == null && now >= trainerHoldUntil && !busyConnecting() && looksLikeTrainer(rec, name)) connectTrainer(d, name);
    }

    /** One connection attempt at a time: parallel attempts make some phones fail with error 133. */
    private boolean busyConnecting() {
        return (trainer != null && !trainer.isConnected()) || (click != null && !click.linkReady());
    }

    private static boolean looksLikeTrainer(ScanRecord rec, String name) {
        List<ParcelUuid> ids = rec.getServiceUuids();
        if (ids != null) {
            for (ParcelUuid id : ids) if (Protocol.FTMS_SERVICE.equals(id.getUuid())) return true;
        }
        return name != null && name.toUpperCase(Locale.US).contains("KICKR");
    }

    private void connectTrainer(BluetoothDevice d, String name) {
        log("Found the trainer" + (name != null ? " \"" + name + "\"" : "") + " (" + shortAddress(d.getAddress()) + ")");
        trainer = new Trainer(this, d, name != null ? name : "Trainer");
        trainer.connect();
        updateScan();
        changed();
    }

    private void connectClick(BluetoothDevice d, int type, String name) {
        log("Found " + (type == Protocol.ZWIFT_CLICK_V1 ? "a Zwift Click" : "the right Zwift Click v2 pod")
                + (name != null ? " \"" + name + "\"" : "") + " (" + shortAddress(d.getAddress()) + ")");
        click = new ClickPod(this, d, type);
        click.connect();
        updateScan();
        changed();
    }

    void onTrainerClosed(Trainer t, String reason) {
        log("Trainer: " + reason);
        if (t != trainer) return;
        trainer = null;
        trainerMissingSince = SystemClock.elapsedRealtime();
        if (t.wasEverReady()) trainerFailures = 0;
        else trainerFailures++;
        trainerHoldUntil = SystemClock.elapsedRealtime() + Math.min(30000L, 2000L << Math.min(trainerFailures, 4));
        updateScan();
        changed();
    }

    void onClickClosed(ClickPod c, String reason) {
        log("Click: " + reason);
        if (c != click) return;
        click = null;
        if (c.wasEverConnected()) clickFailures = 0;
        else clickFailures++;
        clickHoldUntil = SystemClock.elapsedRealtime() + (300L << Math.min(clickFailures, 5));
        updateScan();
        changed();
    }

    /** The Click was told to restart: keep searching for a while so it is picked up again quickly. */
    void watchForClickRestart() {
        clickWatchUntil = SystemClock.elapsedRealtime() + 15000;
        updateScan();
        main.removeCallbacks(watchEnd);
        main.postDelayed(watchEnd, 15100);
    }

    /** Never connect to this device again in this session. */
    void ignore(String address, String why) {
        if (ignored.add(address)) log("Ignoring " + shortAddress(address) + " from now on: " + why);
    }

    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                if (scanning) {
                    scanning = false;
                    main.removeCallbacks(scanRefresh);
                }
                if (trainer != null || click != null) log("Bluetooth turned off");
                closeTrainer();
                closeClick();
            } else if (state == BluetoothAdapter.STATE_ON) {
                if (running) log("Bluetooth turned on");
                updateScan();
            }
            changed();
        }
    };

    // ------------------------------------------------------------------ live numbers

    void onBikeData(Protocol.BikeData d) {
        long now = SystemClock.elapsedRealtime();
        if (d.hasPower) {
            bikePowerAt = now;
            addPower(now, d.powerW);
        }
        if (!Double.isNaN(d.cadenceRpm)) {
            bikeCadenceAt = now;
            cadence = d.cadenceRpm;
            cadenceAt = now;
        }
        if (!Double.isNaN(d.speedKmh)) {
            speed = d.speedKmh;
            speedAt = now;
        }
    }

    void onPowerData(Protocol.PowerData p) {
        if (!p.valid) return;
        long now = SystemClock.elapsedRealtime();
        if (now - bikePowerAt > 3000) addPower(now, p.powerW);
        if (p.crankRevs >= 0) {
            double rpm = crank.update(p.crankRevs, p.crankTime, now);
            if (now - bikeCadenceAt > 3000 && !Double.isNaN(rpm)) {
                cadence = rpm;
                cadenceAt = now;
            }
        }
    }

    private void addPower(long now, int watts) {
        powerSamples.addLast(new long[] {now, Math.max(0, watts)});
        while (!powerSamples.isEmpty() && now - powerSamples.peekFirst()[0] > 3000) powerSamples.removeFirst();
        powerAt = now;
    }

    /** Average power over the last 3 seconds, or -1. */
    int power3s() {
        long now = SystemClock.elapsedRealtime();
        if (now - powerAt > 4000) return -1;
        long sum = 0;
        int n = 0;
        for (long[] s : powerSamples) {
            if (now - s[0] <= 3000) {
                sum += s[1];
                n++;
            }
        }
        return n == 0 ? -1 : (int) Math.round((double) sum / n);
    }

    /** Cadence in rpm, or -1. */
    int cadence() {
        return SystemClock.elapsedRealtime() - cadenceAt > 4000 ? -1 : (int) Math.round(cadence);
    }

    /** Speed in km/h, or NaN. */
    double speed() {
        return SystemClock.elapsedRealtime() - speedAt > 4000 ? Double.NaN : speed;
    }

    long rideSeconds() {
        return rideMs / 1000;
    }

    int averagePower() {
        return powerSeconds == 0 ? -1 : (int) Math.round(powerSum / powerSeconds);
    }

    private final Runnable everySecond = new Runnable() {
        @Override
        public void run() {
            int p = power3s();
            if (p > 0 || cadence() > 0) {
                rideMs += 1000;
                if (p >= 0) {
                    powerSum += p;
                    powerSeconds++;
                }
            }
            if (running) main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ what the screen shows

    String trainerStatus() {
        if (!running) return "Off";
        if (!bluetoothOn()) return "Bluetooth off";
        if (trainer == null) return "Searching…";
        if (!trainer.isConnected()) return "Connecting…";
        TrainerControl c = trainer.control();
        if (c == null) return "Setting up…";
        switch (c.phase()) {
            case READY: return c.via() == TrainerControl.Via.WAHOO ? "ERG on · Wahoo" : "ERG on";
            case WAITING: return "Waiting for control";
            case FAILED: return "No control";
            default: return "Taking control…";
        }
    }

    /** 0 idle, 1 working on it, 2 good, 3 problem. */
    int trainerLevel() {
        if (!running || trainer == null) return 0;
        TrainerControl c = trainer.control();
        if (c == null || !trainer.isConnected()) return 1;
        switch (c.phase()) {
            case READY: return c.problem().isEmpty() ? 2 : 1;
            case WAITING:
            case FAILED: return 3;
            default: return 1;
        }
    }

    String clickStatus() {
        if (!running) return "Off";
        if (!bluetoothOn()) return "Bluetooth off";
        if (click == null) return "Searching…";
        if (!click.isConnected()) return "Connecting…";
        int b = click.battery();
        return b >= 0 ? "Connected · " + b + "%" : "Connected";
    }

    int clickLevel() {
        if (!running || click == null) return 0;
        return click.isConnected() ? 2 : 1;
    }

    /** The target the trainer has confirmed, or -1. */
    int confirmedTarget() {
        TrainerControl c = trainer == null ? null : trainer.control();
        return c == null || c.phase() != TrainerControl.Phase.READY ? -1 : c.applied();
    }

    /** One line of advice, or null. */
    String hint() {
        if (!running) return null;
        if (!bluetoothOn()) return "Bluetooth is off. Turn it on to connect.";
        TrainerControl c = trainer == null ? null : trainer.control();
        if (c != null && !c.problem().isEmpty()) return c.problem();
        if (trainer == null && SystemClock.elapsedRealtime() - trainerMissingSince > 15000) {
            return "Still looking for the KICKR. Check it has power, and close Zwift or the Wahoo app on other devices so a connection is free.";
        }
        if (click == null) return "Press a button on the right Click pod to wake it up.";
        return null;
    }

    // ------------------------------------------------------------------ log, timers, misc

    void log(String message) {
        lines.addLast(clock.format(new Date()) + "  " + message);
        while (lines.size() > 1000) lines.removeFirst();
        logVersion++;
        Log.i("KickrClick", message);
    }

    int logVersion() {
        return logVersion;
    }

    String logTail(int count) {
        StringBuilder b = new StringBuilder();
        int skip = Math.max(0, lines.size() - count);
        int i = 0;
        for (String line : lines) {
            if (i++ >= skip) b.append(line).append('\n');
        }
        return b.toString();
    }

    String logText() {
        return "KICKR Click " + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + "\n" + logTail(Integer.MAX_VALUE);
    }

    void changed() {
        if (view != null) view.hubChanged();
    }

    Async.Cancel schedule(final Runnable task, long delayMs) {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                task.run();
            }
        };
        main.postDelayed(r, delayMs);
        return new Async.Cancel() {
            @Override
            public void cancel() {
                main.removeCallbacks(r);
            }
        };
    }

    @SuppressWarnings("deprecation")
    String versionName() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String shortAddress(String address) {
        return address == null || address.length() < 5 ? String.valueOf(address) : "…" + address.substring(address.length() - 5);
    }

    /** Saves a crash report so the next start can show it in the log. */
    private void recordCrashes() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    prefs.edit().putString("crash", Log.getStackTraceString(e)).commit();
                } catch (Throwable ignored) {
                    // nothing more we can do
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }
}

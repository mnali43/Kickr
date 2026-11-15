package com.kickrclick.app;

import java.util.Map;
import java.util.TreeMap;

/**
 * Talks to one Zwift Click pod: greets it, turns its messages into button presses, and keeps it
 * awake.
 *
 * <p>Apps greet the pods in different ways ("RideOn", "RideOn" + FF 04 00, "RideOn" 02 03). Until
 * presses have been seen once, it tries each greeting in turn for a few seconds while the rider
 * presses a button, and remembers the one that works. Every message is counted, and the first
 * ones are logged in full, so a log shows exactly what the pod sends. Plain Java so it can be
 * tested off the phone.
 */
final class ClickControl {
    interface Io extends Async.Timer {
        /** Writes to the pod's command characteristic. */
        void write(byte[] data);

        void log(String message);

        /** A button went down. */
        void pressed(int button);

        void battery(int percent);

        /** The pod was told to restart; it will drop the connection and come back. */
        void restarting();

        /** Presses arrived after this greeting: use it first next time. */
        void greetingWorks(int greeting);
    }

    static final long TICK_MS = 5000;
    static final long TRY_NEXT_GREETING_MS = 6000;
    static final long TRIAL_MS = 60000;
    static final long RESTART_AFTER_IDLE_MS = 45000;
    static final long SUMMARY_MS = 30000;
    static final int RAW_LOG_LIMIT = 20;
    static final int RAW_PRESS_LOG_LIMIT = 6;

    private final Io io;
    private final boolean v2;
    private final boolean known; // a greeting already worked on an earlier connection
    private int greeting;
    private boolean working; // presses seen on this connection
    private int down; // buttons held right now
    private long startedAt;
    private long lastPress;
    private long lastRestart;
    private long lastSummary;
    private boolean answered;
    private boolean stopped;
    private int rawLogged;
    private int pressesLogged;
    private Async.Cancel ticker;
    private Async.Cancel trial;
    private boolean gaveUp;
    private final Map<String, Integer> counts = new TreeMap<>();

    /** @param knownGreeting a greeting that worked before, or -1 to try them all */
    ClickControl(Io io, boolean v2, int knownGreeting) {
        this.io = io;
        this.v2 = v2;
        this.known = knownGreeting >= 0 && knownGreeting < Protocol.CLICK_GREETINGS;
        this.greeting = known ? knownGreeting : 0;
    }

    boolean pressesSeen() {
        return working;
    }


    void begin() {
        long now = io.now();
        startedAt = now;
        lastPress = now;
        lastRestart = now;
        lastSummary = now;
        greet();
        ticker = io.schedule(tick, TICK_MS);
        if (!known) trial = io.schedule(nextGreeting, TRY_NEXT_GREETING_MS);
    }

    void stop() {
        stopped = true;
        if (ticker != null) ticker.cancel();
        if (trial != null) trial.cancel();
    }

    private void greet() {
        io.log("Click: greeting " + (greeting + 1) + " of " + Protocol.CLICK_GREETINGS + " (" + Protocol.clickGreetingName(greeting) + ")");
        for (byte[] part : Protocol.clickGreeting(greeting)) io.write(part);
    }

    private final Runnable nextGreeting = new Runnable() {
        @Override
        public void run() {
            if (stopped || working) return;
            if (!gaveUp && io.now() - startedAt >= TRIAL_MS) {
                gaveUp = true; // keep trying: the rider may simply not have pressed yet
                io.log("Click: no button presses yet; still trying the greetings in turn");
            }
            greeting = (greeting + 1) % Protocol.CLICK_GREETINGS;
            greet();
            trial = io.schedule(this, TRY_NEXT_GREETING_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            long now = io.now();
            // Count what arrived: every 30 s for the first 3 minutes, then every 5 minutes.
            long period = now - startedAt < 180000 ? SUMMARY_MS : 300000;
            if (now - lastSummary >= period) {
                long seconds = (now - lastSummary) / 1000;
                lastSummary = now;
                if (!counts.isEmpty()) {
                    StringBuilder b = new StringBuilder("Click: messages in the last " + seconds + " s:");
                    for (Map.Entry<String, Integer> e : counts.entrySet()) b.append(' ').append(e.getKey()).append(" ×").append(e.getValue());
                    io.log(b.toString());
                    counts.clear();
                } else {
                    io.log("Click: no messages in the last " + seconds + " s");
                }
            }
            // A Click v2 pod sleeps about a minute after its last press. Restarting it first keeps it
            // available. Only once presses are known to work, so a first test is never interrupted.
            if (v2 && (known || working) && now - Math.max(lastPress, lastRestart) >= RESTART_AFTER_IDLE_MS) {
                lastRestart = now;
                io.log("Click: no presses for 45 s, restarting the pod so it stays awake");
                io.write(Protocol.CLICK_RESET);
                io.restarting();
            }
            ticker = io.schedule(this, TICK_MS);
        }
    };

    /** A message from any of the pod's characteristics; {@code from} names it in the log. */
    void onMessage(String from, byte[] v) {
        if (stopped || v == null || v.length == 0) return;
        int type = v[0] & 0xFF;
        String key = from + " 0x" + (type < 16 ? "0" : "") + Integer.toHexString(type);
        Integer n = counts.get(key);
        counts.put(key, n == null ? 1 : n + 1);
        if (rawLogged < RAW_LOG_LIMIT) {
            rawLogged++;
            io.log("Click ← " + from + " [" + Protocol.hex(v) + "]");
        }

        if (Protocol.startsWith(v, Protocol.RIDE_ON)) {
            if (!answered) {
                answered = true;
                io.log("Click: answered the greeting");
            }
            return;
        }
        if (type == 0xFF && v.length >= 2) {
            if (v[1] == 0x03) {
                io.log("Click: unlock check, answering it");
                io.write(Protocol.CLICK_CHALLENGE_REPLY);
            } else if (v[1] == 0x05) {
                io.log("Click: says it stopped, greeting it again");
                greet();
            }
            return;
        }
        if (type == Protocol.MSG_BATTERY) {
            int level = Protocol.clickBattery(v);
            if (level >= 0) io.battery(level);
            return;
        }
        int nowDown = Protocol.clickButtonsDown(v);
        if (nowDown < 0) return;
        int pressedNow = nowDown & ~down;
        down = nowDown;
        if (pressedNow == 0) return;
        lastPress = io.now();
        if (pressesLogged < RAW_PRESS_LOG_LIMIT && rawLogged >= RAW_LOG_LIMIT) {
            pressesLogged++;
            io.log("Click ← " + from + " [" + Protocol.hex(v) + "]");
        }
        if (!working) {
            working = true;
            if (trial != null) trial.cancel();
            io.log("Click: button presses arrive (greeting " + (greeting + 1) + ", " + Protocol.clickGreetingName(greeting) + ")");
            io.greetingWorks(greeting);
        }
        for (int bit = 1; bit <= 0x8000; bit <<= 1) {
            if ((pressedNow & bit) != 0) io.pressed(bit);
        }
    }
}

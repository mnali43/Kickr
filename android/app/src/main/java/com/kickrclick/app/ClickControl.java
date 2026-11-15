package com.kickrclick.app;

import java.util.Map;
import java.util.TreeMap;

/**
 * Talks to one Zwift Click pod: greets it, turns its messages into button presses, and keeps it
 * working.
 *
 * <p>Apps greet the pods in different ways ("RideOn", "RideOn" + FF 04 00, "RideOn" 02 03). Until
 * presses have been seen once, it tries each greeting in turn while the rider presses a button,
 * and remembers the one that works.
 *
 * <p>Outside Zwift the pods need restarting now and then: the right one falls asleep about a
 * minute after its last press, and the left one stops sending presses about a minute after it
 * connects (Zwift locks it). A restart takes a few seconds and the pod connects again by itself.
 * Plain Java so it can be tested off the phone.
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

    /** When to restart the pod. */
    enum Keep {
        /** Never (the original Click). */
        NONE,
        /** After 45 s without a press, before it falls asleep (right pod of the Click v2). */
        WHEN_IDLE,
        /** Every 50 s, before Zwift's lock stops it (left pod of the Click v2). */
        EVERY_MINUTE
    }

    static final long TICK_MS = 1000;
    static final long TRY_NEXT_GREETING_MS = 6000;
    static final long TRIAL_MS = 60000;
    static final long RESTART_AFTER_IDLE_MS = 45000;
    static final long RESTART_EVERY_MS = 50000;
    static final long SUMMARY_MS = 30000;
    static final int RAW_LOG_LIMIT = 20;
    static final int RAW_PRESS_LOG_LIMIT = 6;

    private final Io io;
    private final String name;
    private final Keep keep;
    private final boolean verbose; // full logging; off for routine reconnects
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
    private boolean gaveUp;
    private int rawLogged;
    private int pressesLogged;
    private Async.Cancel ticker;
    private Async.Cancel trial;
    private final Map<String, Integer> counts = new TreeMap<>();

    /**
     * @param name how the log names this pod, e.g. "Left Click"
     * @param knownGreeting a greeting that worked before, or -1 to try them all
     */
    ClickControl(Io io, String name, Keep keep, int knownGreeting, boolean verbose) {
        this.io = io;
        this.name = name;
        this.keep = keep;
        this.verbose = verbose;
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
        if (verbose || !known) {
            io.log(name + ": greeting " + (greeting + 1) + " of " + Protocol.CLICK_GREETINGS + " (" + Protocol.clickGreetingName(greeting) + ")");
        }
        for (byte[] part : Protocol.clickGreeting(greeting)) io.write(part);
    }

    private void restart(String why) {
        lastRestart = io.now();
        if (verbose) io.log(name + ": " + why);
        io.write(Protocol.CLICK_RESET);
        io.restarting();
    }

    private final Runnable nextGreeting = new Runnable() {
        @Override
        public void run() {
            if (stopped || working) return;
            if (!gaveUp && io.now() - startedAt >= TRIAL_MS) {
                gaveUp = true; // keep trying: the rider may simply not have pressed yet
                io.log(name + ": no button presses yet; still trying the greetings in turn");
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
            if (verbose) summarise(now);
            if (keep == Keep.WHEN_IDLE) {
                // Only once presses are known to work, so a first test is never interrupted.
                if ((known || working) && now - Math.max(lastPress, lastRestart) >= RESTART_AFTER_IDLE_MS) {
                    restart("no presses for 45 s, restarting it so it stays awake");
                }
            } else if (keep == Keep.EVERY_MINUTE) {
                long age = now - lastRestart;
                // Not in the middle of a burst of presses, unless the lock is about to kick in.
                if (age >= RESTART_EVERY_MS && (now - lastPress >= 1500 || age >= RESTART_EVERY_MS + 5000)) {
                    restart("restarting it before Zwift's lock stops it (about a minute after it connects)");
                }
            }
            ticker = io.schedule(this, TICK_MS);
        }
    };

    /** Counts what arrived: every 30 s for the first 3 minutes, then every 5 minutes. */
    private void summarise(long now) {
        long period = now - startedAt < 180000 ? SUMMARY_MS : 300000;
        if (now - lastSummary < period) return;
        long seconds = (now - lastSummary) / 1000;
        lastSummary = now;
        if (counts.isEmpty()) {
            io.log(name + ": no messages in the last " + seconds + " s");
            return;
        }
        StringBuilder b = new StringBuilder(name + ": messages in the last " + seconds + " s:");
        for (Map.Entry<String, Integer> e : counts.entrySet()) b.append(' ').append(e.getKey()).append(" ×").append(e.getValue());
        io.log(b.toString());
        counts.clear();
    }

    /** A message from any of the pod's characteristics; {@code from} names it in the log. */
    void onMessage(String from, byte[] v) {
        if (stopped || v == null || v.length == 0) return;
        int type = v[0] & 0xFF;
        String key = from + " 0x" + (type < 16 ? "0" : "") + Integer.toHexString(type);
        Integer n = counts.get(key);
        counts.put(key, n == null ? 1 : n + 1);
        if (verbose && rawLogged < RAW_LOG_LIMIT) {
            rawLogged++;
            io.log(name + " ← " + from + " [" + Protocol.hex(v) + "]");
        }

        if (Protocol.startsWith(v, Protocol.RIDE_ON)) {
            if (!answered) {
                answered = true;
                if (verbose) io.log(name + ": answered the greeting");
            }
            return;
        }
        if (type == 0xFF && v.length >= 2) {
            if (v[1] == 0x03) {
                io.log(name + ": unlock check [" + Protocol.hex(v) + "], answering it");
                io.write(Protocol.CLICK_CHALLENGE_REPLY);
            } else if (v[1] == 0x05) {
                if (keep == Keep.NONE) {
                    io.log(name + ": says it stopped [" + Protocol.hex(v) + "], greeting it again");
                    greet();
                } else {
                    io.log(name + ": says it stopped [" + Protocol.hex(v) + "], restarting it");
                    restart("restarting after its stop message");
                }
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
        if (verbose && pressesLogged < RAW_PRESS_LOG_LIMIT && rawLogged >= RAW_LOG_LIMIT) {
            pressesLogged++;
            io.log(name + " ← " + from + " [" + Protocol.hex(v) + "]");
        }
        if (!working) {
            working = true;
            if (trial != null) trial.cancel();
            if (verbose || !known) {
                io.log(name + ": button presses arrive (greeting " + (greeting + 1) + ", " + Protocol.clickGreetingName(greeting) + ")");
            }
            io.greetingWorks(greeting);
        }
        for (int bit = 1; bit <= 0x8000; bit <<= 1) {
            if ((pressedNow & bit) != 0) io.pressed(bit);
        }
    }
}

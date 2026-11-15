package com.kickrclick.app;

import java.util.HashSet;
import java.util.Set;

/**
 * Talks to one Zwift Click pod: says hello, turns its messages into button presses, and keeps it
 * awake. A Click v2 pod that is not used with Zwift goes to sleep about a minute after the last
 * press; restarting it shortly before that keeps it available (the way BikeControl and others do
 * it). Plain Java so it can be tested off the phone.
 */
final class ClickControl {
    interface Io extends Async.Timer {
        /** Writes to the pod's command characteristic. */
        void write(byte[] data);

        void log(String message);

        /** A button went down. */
        void pressed(int button);

        void battery(int percent);

        /** The pod was told to restart; it will probably drop the connection and come back. */
        void restarting();
    }

    static final long TICK_MS = 5000;
    static final long HELLO_EVERY_MS = 10000;
    static final long RESTART_AFTER_IDLE_MS = 50000;

    private final Io io;
    private final boolean v2;
    private int down; // buttons held right now
    private long lastPress;
    private long lastRestart;
    private long lastHello;
    private boolean answered;
    private boolean stopped;
    private Async.Cancel ticker;
    private final Set<Integer> reported = new HashSet<>();

    ClickControl(Io io, boolean v2) {
        this.io = io;
        this.v2 = v2;
    }

    void begin() {
        long now = io.now();
        lastPress = now;
        lastRestart = now;
        hello();
        ticker = io.schedule(tick, TICK_MS);
    }

    void stop() {
        stopped = true;
        if (ticker != null) ticker.cancel();
    }

    private void hello() {
        lastHello = io.now();
        io.write(Protocol.clickStart(v2));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            long now = io.now();
            if (v2 && now - Math.max(lastPress, lastRestart) >= RESTART_AFTER_IDLE_MS) {
                lastRestart = now;
                io.log("Click: no presses for a while, restarting the pod so it stays awake");
                io.write(Protocol.CLICK_RESET);
                io.restarting();
                io.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (!stopped) hello(); // only matters if the pod stayed connected
                    }
                }, 1500);
            } else if (now - lastHello >= HELLO_EVERY_MS) {
                hello();
            }
            ticker = io.schedule(this, TICK_MS);
        }
    };

    /** A message from the pod's command-reply characteristic. */
    void onReply(byte[] v) {
        if (stopped || v == null || v.length == 0) return;
        if (Protocol.startsWith(v, Protocol.RIDE_ON)) {
            if (!answered) {
                answered = true;
                io.log("Click: said hello back [" + Protocol.hex(v) + "]");
            }
            return;
        }
        if (v.length >= 2 && (v[0] & 0xFF) == 0xFF && v[1] == 0x03) {
            io.log("Click: unlock check [" + Protocol.hex(v) + "], answering");
            io.write(Protocol.CLICK_CHALLENGE_REPLY);
            return;
        }
        if (v.length >= 2 && (v[0] & 0xFF) == 0xFF && v[1] == 0x05) {
            io.log("Click: says it stopped [" + Protocol.hex(v) + "], saying hello again");
            hello();
            return;
        }
        onMessage(v);
    }

    /** A message from the pod's notification characteristic (buttons, battery). */
    void onMessage(byte[] v) {
        if (stopped || v == null || v.length == 0) return;
        int type = v[0] & 0xFF;
        if (type == Protocol.MSG_BATTERY) {
            int level = Protocol.clickBattery(v);
            if (level >= 0) io.battery(level);
            return;
        }
        int nowDown = Protocol.clickButtonsDown(v);
        if (nowDown < 0) {
            if (type != Protocol.MSG_IDLE && reported.add(type)) {
                io.log("Click: message type 0x" + Integer.toHexString(type) + " [" + Protocol.hex(v) + "]");
            }
            return;
        }
        int pressedNow = nowDown & ~down;
        down = nowDown;
        if (pressedNow == 0) return;
        lastPress = io.now();
        for (int bit = 1; bit <= 0x8000; bit <<= 1) {
            if ((pressedNow & bit) != 0) io.pressed(bit);
        }
    }
}

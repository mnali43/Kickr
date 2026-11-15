package com.kickrclick.app;

import java.util.Locale;
import java.util.UUID;

/**
 * The Bluetooth messages this app speaks: FTMS (the standard trainer protocol), Cycling Power,
 * Wahoo's own trainer channel and the Zwift Click. Plain Java with no Android classes, so it can
 * be tested on a computer.
 */
final class Protocol {
    private Protocol() {}

    static UUID uuid16(int id) {
        return UUID.fromString(String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", id));
    }

    static final UUID CCCD = uuid16(0x2902);

    // Fitness Machine Service
    static final UUID FTMS_SERVICE = uuid16(0x1826);
    static final UUID FTMS_BIKE_DATA = uuid16(0x2AD2);
    static final UUID FTMS_CONTROL_POINT = uuid16(0x2AD9);
    static final UUID FTMS_STATUS = uuid16(0x2ADA);

    // Cycling Power Service
    static final UUID CPS_SERVICE = uuid16(0x1818);
    static final UUID CPS_MEASUREMENT = uuid16(0x2A63);

    // Wahoo's own trainer channel (KICKRs keep it inside the Cycling Power service)
    static final UUID WAHOO_TRAINER = UUID.fromString("a026e005-0a7d-4ab3-97fa-f1500f9feb8b");

    // Zwift controllers
    static final int ZWIFT_COMPANY_ID = 0x094A;
    static final int ZWIFT_CLICK_V1 = 0x09;
    static final int ZWIFT_CLICK_V2_RIGHT = 0x0A;
    static final int ZWIFT_CLICK_V2_LEFT = 0x0B;
    static final UUID ZWIFT_SERVICE = uuid16(0xFC82);
    static final UUID ZWIFT_SERVICE_OLD = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1");
    static final UUID ZWIFT_ASYNC = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1");
    static final UUID ZWIFT_SYNC_RX = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1");
    static final UUID ZWIFT_SYNC_TX = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1");

    // ---------------------------------------------------------------- status codes

    static final int OK = 0;
    static final int TIMEOUT = -1;
    static final int NOT_SENT = -2;
    static final int NO_CCCD = -3;
    static final int BUSY = -4;
    static final int NO_PERMISSION = -5;

    /** Result of a single Bluetooth request (a GATT/ATT status or one of the codes above). */
    static String opStatusName(int s) {
        switch (s) {
            case OK: return "ok";
            case TIMEOUT: return "no answer";
            case NOT_SENT: return "Android could not send it";
            case NO_CCCD: return "it has no on/off switch for updates";
            case BUSY: return "Bluetooth stayed busy";
            case NO_PERMISSION: return "no Bluetooth permission";
            case 0x01: return "invalid handle (1)";
            case 0x02: return "read not permitted (2)";
            case 0x03: return "write not permitted (3)";
            case 0x05: return "needs pairing (5)";
            case 0x06: return "request not supported (6)";
            case 0x07: return "invalid offset (7)";
            case 0x08: return "not authorised (8)";
            case 0x0D: return "invalid length (13)";
            case 0x0E: return "unlikely error (14)";
            case 0x0F: return "needs encryption (15)";
            case 0x80: return "no resources (128)";
            case 0x81: return "internal error (129)";
            case 0x85: return "Android Bluetooth error (133)";
            case 0x8F: return "connection congested (143)";
            case 0x101: return "failure (257)";
            default: return "error " + s;
        }
    }

    /** Reason a connection ended (a link-layer reason code). */
    static String linkStatusName(int s) {
        switch (s) {
            case 0: return "normal";
            case 8: return "signal lost (8)";
            case 19: return "the device closed the connection (19)";
            case 22: return "the phone closed the connection (22)";
            case 34: return "link timed out (34)";
            case 62: return "could not connect (62)";
            case 133: return "Android Bluetooth error (133)";
            case 147: return "connection attempt timed out (147)";
            default: return "code " + s;
        }
    }

    // ---------------------------------------------------------------- FTMS and Wahoo commands

    static final int FTMS_REQUEST_CONTROL = 0x00;
    static final int FTMS_RESET = 0x01;
    static final int FTMS_SET_TARGET_POWER = 0x05;
    static final int FTMS_START = 0x07;
    static final int FTMS_RESPONSE = 0x80;

    static final int RESULT_SUCCESS = 0x01;
    static final int RESULT_NOT_SUPPORTED = 0x02;
    static final int RESULT_INVALID = 0x03;
    static final int RESULT_FAILED = 0x04;
    static final int RESULT_NOT_PERMITTED = 0x05;

    static byte[] ftmsRequestControl() {
        return new byte[] {FTMS_REQUEST_CONTROL};
    }

    static byte[] ftmsStart() {
        return new byte[] {FTMS_START};
    }

    static byte[] ftmsTargetPower(int watts) {
        return new byte[] {FTMS_SET_TARGET_POWER, (byte) watts, (byte) (watts >> 8)};
    }

    static byte[] wahooUnlock() {
        return new byte[] {0x20, (byte) 0xEE, (byte) 0xFC};
    }

    static byte[] wahooErg(int watts) {
        return new byte[] {0x42, (byte) watts, (byte) (watts >> 8)};
    }

    static String ftmsOpName(int op) {
        switch (op) {
            case FTMS_REQUEST_CONTROL: return "request control";
            case FTMS_RESET: return "reset";
            case FTMS_SET_TARGET_POWER: return "set target power";
            case FTMS_START: return "start";
            case 0x11: return "set simulation";
            default: return String.format(Locale.US, "op 0x%02x", op);
        }
    }

    static String ftmsResultName(int r) {
        switch (r) {
            case RESULT_SUCCESS: return "OK";
            case RESULT_NOT_SUPPORTED: return "not supported";
            case RESULT_INVALID: return "invalid value";
            case RESULT_FAILED: return "failed";
            case RESULT_NOT_PERMITTED: return "control not permitted";
            default: return String.format(Locale.US, "result 0x%02x", r);
        }
    }

    static String machineStatusName(byte[] v) {
        if (v == null || v.length == 0) return "empty status";
        switch (v[0] & 0xFF) {
            case 0x01: return "reset";
            case 0x02: return "stopped or paused";
            case 0x03: return "stopped by the safety key";
            case 0x04: return "started";
            case 0x05: return "target speed changed";
            case 0x06: return "target incline changed";
            case 0x07: return "target resistance changed";
            case 0x08: return v.length >= 3 ? "target power changed to " + s16(v, 1) + " W" : "target power changed";
            case 0x12: return "simulation changed";
            case 0x14: return "spin-down status";
            case 0xFF: return "control taken by another app or device";
            default: return "status " + hex(v);
        }
    }

    // ---------------------------------------------------------------- trainer data

    /** One FTMS Indoor Bike Data message. Values the trainer did not include stay NaN / absent. */
    static final class BikeData {
        double speedKmh = Double.NaN;
        double cadenceRpm = Double.NaN;
        int powerW;
        boolean hasPower;
    }

    static BikeData parseBikeData(byte[] v) {
        BikeData d = new BikeData();
        if (v == null || v.length < 2) return d;
        int flags = u16(v, 0);
        int i = 2;
        if ((flags & 0x0001) == 0) { // "more data" bit clear means instantaneous speed is present
            if (i + 2 > v.length) return d;
            d.speedKmh = u16(v, i) / 100.0;
            i += 2;
        }
        if ((flags & 0x0002) != 0) i += 2; // average speed
        if ((flags & 0x0004) != 0) {
            if (i + 2 > v.length) return d;
            d.cadenceRpm = u16(v, i) / 2.0;
            i += 2;
        }
        if ((flags & 0x0008) != 0) i += 2; // average cadence
        if ((flags & 0x0010) != 0) i += 3; // total distance
        if ((flags & 0x0020) != 0) i += 2; // resistance level
        if ((flags & 0x0040) != 0) {
            if (i + 2 > v.length) return d;
            d.powerW = s16(v, i);
            d.hasPower = true;
        }
        return d;
    }

    /** One Cycling Power Measurement message. */
    static final class PowerData {
        int powerW;
        boolean valid;
        int crankRevs = -1; // cumulative crank revolutions (16 bit)
        int crankTime = -1; // time of the last crank event, 1/1024 s (16 bit)
    }

    static PowerData parsePower(byte[] v) {
        PowerData d = new PowerData();
        if (v == null || v.length < 4) return d;
        int flags = u16(v, 0);
        d.powerW = s16(v, 2);
        d.valid = true;
        int i = 4;
        if ((flags & 0x0001) != 0) i += 1; // pedal power balance
        if ((flags & 0x0004) != 0) i += 2; // accumulated torque
        if ((flags & 0x0010) != 0) i += 6; // wheel revolutions + event time
        if ((flags & 0x0020) != 0 && i + 4 <= v.length) {
            d.crankRevs = u16(v, i);
            d.crankTime = u16(v, i + 2);
        }
        return d;
    }

    /** Turns crank revolution counters into cadence. */
    static final class CrankCadence {
        private int revs = -1;
        private int time = -1;
        private long lastEventAt;
        private double rpm = Double.NaN;

        double update(int newRevs, int newTime, long nowMs) {
            if (revs >= 0) {
                int dRevs = (newRevs - revs) & 0xFFFF;
                int dTime = (newTime - time) & 0xFFFF;
                if (dRevs > 0 && dRevs < 50 && dTime > 0) {
                    rpm = dRevs * 60.0 * 1024.0 / dTime;
                    lastEventAt = nowMs;
                } else if (nowMs - lastEventAt > 3000) {
                    rpm = 0;
                }
            } else {
                lastEventAt = nowMs;
            }
            revs = newRevs;
            time = newTime;
            return rpm;
        }
    }

    // ---------------------------------------------------------------- Zwift Click

    // Button bits as the Zwift Ride / Click v2 report them. In our "down" masks a set bit means held.
    static final int BTN_LEFT = 0x0001;
    static final int BTN_UP = 0x0002;
    static final int BTN_RIGHT = 0x0004;
    static final int BTN_DOWN = 0x0008;
    static final int BTN_A = 0x0010;
    static final int BTN_B = 0x0020;
    static final int BTN_Y = 0x0040;
    static final int BTN_Z = 0x0080;
    static final int BTN_MINUS = 0x0100; // left shifter
    static final int BTN_SHIFT_DOWN_LEFT = 0x0200;
    static final int BTN_POWERUP_LEFT = 0x0400;
    static final int BTN_ONOFF_LEFT = 0x0800;
    static final int BTN_PLUS = 0x1000; // right shifter
    static final int BTN_SHIFT_DOWN_RIGHT = 0x2000;
    static final int BTN_POWERUP_RIGHT = 0x4000;
    static final int BTN_ONOFF_RIGHT = 0x8000;
    static final int BTN_ALL = 0xFFFF;

    static final byte[] RIDE_ON = {0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E}; // "RideOn"
    static final byte[] CLICK_RESET = {0x18};
    static final byte[] CLICK_CHALLENGE_REPLY = {(byte) 0xFF, 0x04, 0x00};

    static final int MSG_PLAY_KEYPAD = 0x07;
    static final int MSG_IDLE = 0x15;
    static final int MSG_BATTERY = 0x19;
    static final int MSG_RIDE_KEYPAD = 0x23; // Click v2 and Ride
    static final int MSG_CLICK_KEYPAD = 0x37; // Click v1

    /** The greeting that starts a session: "RideOn", plus 02 03 for the Click v2. */
    static byte[] clickStart(boolean v2) {
        return v2 ? concat(RIDE_ON, new byte[] {0x02, 0x03}) : RIDE_ON.clone();
    }

    /** Buttons held down in a controller message, or -1 when it is not a button message. */
    static int clickButtonsDown(byte[] f) {
        if (f == null || f.length < 1) return -1;
        int type = f[0] & 0xFF;
        if (type == MSG_RIDE_KEYPAD) {
            long map = protoVarint(f, 1, 1);
            if (map < 0) return -1;
            return (int) (~map) & BTN_ALL; // the controller clears a bit while its button is held
        }
        if (type == MSG_CLICK_KEYPAD) {
            long plus = protoVarint(f, 1, 1);
            long minus = protoVarint(f, 1, 2);
            int down = 0;
            if (plus <= 0) down |= BTN_PLUS; // 0 (or left out) means pressed
            if (minus <= 0) down |= BTN_MINUS;
            return down;
        }
        return -1;
    }

    /** Battery level from a battery message, or -1. */
    static int clickBattery(byte[] f) {
        if (f == null || f.length < 2 || (f[0] & 0xFF) != MSG_BATTERY) return -1;
        long level = protoVarint(f, 1, 1);
        return level >= 0 && level <= 100 ? (int) level : -1;
    }

    static String buttonName(int bit) {
        switch (bit) {
            case BTN_PLUS: return "+";
            case BTN_MINUS: return "−";
            case BTN_A: return "A";
            case BTN_B: return "B";
            case BTN_Y: return "Y";
            case BTN_Z: return "Z";
            case BTN_LEFT: return "left";
            case BTN_RIGHT: return "right";
            case BTN_UP: return "up";
            case BTN_DOWN: return "down";
            case BTN_SHIFT_DOWN_LEFT: return "left shift-down";
            case BTN_SHIFT_DOWN_RIGHT: return "right shift-down";
            case BTN_POWERUP_LEFT: return "left power-up";
            case BTN_POWERUP_RIGHT: return "right power-up";
            case BTN_ONOFF_LEFT: return "left on/off";
            case BTN_ONOFF_RIGHT: return "right on/off";
            default: return String.format(Locale.US, "0x%04x", bit);
        }
    }

    // ---------------------------------------------------------------- protobuf and byte helpers

    /** Value of the first varint field {@code wanted} in a protobuf message starting at {@code start}, or -1. */
    static long protoVarint(byte[] b, int start, int wanted) {
        int i = start;
        while (i < b.length) {
            long[] key = readVarint(b, i);
            if (key == null) return -1;
            i = (int) key[1];
            int field = (int) (key[0] >>> 3);
            int wire = (int) (key[0] & 7);
            if (wire == 0) {
                long[] value = readVarint(b, i);
                if (value == null) return -1;
                i = (int) value[1];
                if (field == wanted) return value[0];
            } else if (wire == 2) {
                long[] len = readVarint(b, i);
                if (len == null || len[0] < 0 || len[0] > b.length) return -1;
                i = (int) len[1] + (int) len[0];
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 1) {
                i += 8;
            } else {
                return -1;
            }
        }
        return -1;
    }

    /** Reads a varint at {@code b[i]}: returns {value, index after it} or null. */
    static long[] readVarint(byte[] b, int i) {
        long result = 0;
        int shift = 0;
        while (i < b.length && shift < 64) {
            int x = b[i++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return new long[] {result, i};
            shift += 7;
        }
        return null;
    }

    static int u16(byte[] v, int i) {
        return (v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8);
    }

    static int s16(byte[] v, int i) {
        return (short) u16(v, i);
    }

    static boolean startsWith(byte[] v, byte[] prefix) {
        if (v == null || v.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (v[i] != prefix[i]) return false;
        return true;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static String hex(byte[] v) {
        if (v == null) return "(nothing)";
        StringBuilder b = new StringBuilder(v.length * 3);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(' ');
            b.append(String.format(Locale.US, "%02x", v[i] & 0xFF));
        }
        return b.toString();
    }

    /** "2ad9" for standard Bluetooth UUIDs, the first 8 hex digits otherwise. */
    static String shortUuid(UUID u) {
        String s = u.toString();
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) return s.substring(4, 8);
        return s.substring(0, 8);
    }
}

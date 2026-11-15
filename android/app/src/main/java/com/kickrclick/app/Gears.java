package com.kickrclick.app;

import java.util.Locale;

/**
 * Virtual gears for free riding, like Zwift's: 24 gears from a 0.75 to a 5.5 ratio.
 *
 * <p>The trainer only knows the real gear the chain sits on (chainring × the 14-tooth Zwift Cog).
 * To make that gear feel like the virtual one, the simulated road is scaled: with the gear k times
 * bigger, the force from rolling and slope grows k times and air drag k³ times (it goes with speed
 * cubed). The trainer then needs the same power at the same cadence as the virtual gear would on
 * a real flat road with no wind: about 180 W at 33 km/h. Plain Java so it can be tested off the phone.
 */
final class Gears {
    private Gears() {}

    static final int COUNT = 24;
    static final int START = 15; // like 50×20: about 120 W at 90 rpm on the flat
    static final double LOWEST = 0.75;
    static final double HIGHEST = 5.5;
    static final int COG = 14; // Zwift Cog on Wahoo trainers
    static final double WHEEL_M = 2.096; // wheel circumference the trainer assumes
    static final double CRR = 0.004; // rolling resistance of a good road tyre on asphalt
    static final double CW = 0.19; // air drag, kg/m: a rider on the hoods (CdA 0.32 m²) in air of 1.2 kg/m³
    static final double MAX_GRADE = 16; // KICKR CORE 2 limit, %

    static double ratio(int gear) {
        return LOWEST * Math.pow(HIGHEST / LOWEST, (gear - 1) / (double) (COUNT - 1));
    }

    /** How much bigger the virtual gear is than the real one. */
    static double scale(int gear, int chainring) {
        return ratio(gear) / (chainring / (double) COG);
    }

    /** FTMS "set indoor bike simulation" for a road of {@code gradePercent} ridden in {@code gear}. */
    static byte[] simulation(int gear, double gradePercent, int chainring) {
        double k = scale(gear, chainring);
        int grade = (int) Math.round(clamp(gradePercent * k, -10, MAX_GRADE) * 100);
        int crr = (int) Math.round(clamp(CRR * k, 0, 0.0255) * 10000);
        int cw = (int) Math.round(clamp(CW * k * k * k, 0, 2.55) * 100);
        return new byte[] {(byte) Protocol.FTMS_SET_SIMULATION, 0, 0, (byte) grade, (byte) (grade >> 8), (byte) crr, (byte) cw};
    }

    /** A real gear with about the same ratio, e.g. "34×17". */
    static String likeGear(int gear) {
        double r = ratio(gear);
        int ring = 34 / r >= 14 ? 34 : 50; // small ring while the cog would be 14 or bigger, like on a real bike
        return ring + "\u00d7" + Math.round(ring / r);
    }

    /** Road speed in the virtual gear at this cadence, km/h. */
    static double speedKmh(int gear, double cadenceRpm) {
        return cadenceRpm / 60.0 * ratio(gear) * WHEEL_M * 3.6;
    }

    /**
     * The chainring the chain is on, worked out from the trainer's speed and cadence: the trainer
     * turns its speed from the real gear, so speed ÷ (cadence × wheel) × cog = chainring teeth.
     * Returns -1 when the numbers are too rough to tell.
     */
    static int chainringFrom(double speedKmh, double cadenceRpm) {
        if (Double.isNaN(speedKmh) || Double.isNaN(cadenceRpm) || cadenceRpm < 50 || cadenceRpm > 130 || speedKmh < 8) return -1;
        double teeth = (speedKmh / 3.6) / (cadenceRpm / 60.0 * WHEEL_M) * COG;
        for (int ring : new int[] {50, 34}) {
            if (Math.abs(teeth - ring) / ring < 0.08) return ring;
        }
        return teeth >= 28 && teeth <= 56 ? (int) Math.round(teeth) : -1;
    }

    static String describe(int gear) {
        return String.format(Locale.US, "gear %d (like %s)", gear, likeGear(gear));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.kickrclick.app;

import java.util.Arrays;

/**
 * Takes control of the trainer and keeps it doing what the rider chose: hold a power (ERG) or
 * ride a simulated road in a virtual gear (free ride).
 *
 * <p>FTMS first: request control, start, then send the command, each step waiting for the
 * trainer's answer. If the trainer refuses FTMS, it switches to Wahoo's own channel (ERG only).
 * Only one command is in flight at a time; when the rider presses quickly, the newest one is sent
 * as soon as the trainer answers the previous one. Plain Java so it can be tested off the phone.
 */
final class TrainerControl {
    interface Io extends Async.Timer {
        void writeFtms(byte[] data, Async.Done done);

        /** Switches on updates from the Wahoo channel, then calls {@code done}. */
        void prepareWahoo(Async.Done done);

        void writeWahoo(byte[] data, Async.Done done);

        void log(String message);

        /** Something shown on screen changed. */
        void changed();
    }

    /** What the trainer should do. */
    static final class Command {
        final byte[] ftms;
        final byte[] wahoo; // null when Wahoo's channel can't do it
        final String label; // "150 W" or "gear 12"
        final int watts; // the ERG target, or -1 for a simulated road

        private Command(byte[] ftms, byte[] wahoo, String label, int watts) {
            this.ftms = ftms;
            this.wahoo = wahoo;
            this.label = label;
            this.watts = watts;
        }

        /** Hold this power. */
        static Command erg(int watts) {
            return new Command(Protocol.ftmsTargetPower(watts), Protocol.wahooErg(watts), watts + " W", watts);
        }

        /** Ride this simulated road ({@link Protocol#FTMS_SET_SIMULATION} bytes). */
        static Command road(byte[] simulation, String label) {
            return new Command(simulation, null, label, -1);
        }

        int op() {
            return ftms[0] & 0xFF;
        }

        boolean sameAs(Command other) {
            return other != null && Arrays.equals(ftms, other.ftms);
        }
    }

    enum Phase { IDLE, REQUESTING, STARTING, READY, WAITING, FAILED }

    enum Via { NONE, FTMS, WAHOO }

    static final long REPLY_TIMEOUT_MS = 3000;
    static final long REFRESH_MS = 15000;
    static final long WAIT_FOR_CONTROL_MS = 5000;
    static final int MAX_REQUESTS = 3;
    static final int MAX_STARTS = 3;
    static final int MAX_WAITS = 6;

    private final Io io;
    private Phase phase = Phase.IDLE;
    private Via via = Via.NONE;
    private boolean hasWahoo;
    private boolean ftmsReplies;
    private boolean triedWahoo;
    private Command target; // what the rider wants
    private Command applied; // what the trainer last accepted
    private Command sending; // in flight, null when none
    private int awaitOp = -1; // FTMS command waiting for its answer
    private int token; // bumped whenever answers to older commands must be ignored
    private int requests;
    private int starts;
    private int waits;
    private int failures;
    private boolean quiet; // the command in flight is a routine refresh
    private long lastReassert = -100000;
    private long lastRestart = -100000;
    private Async.Cancel replyTimer;
    private Async.Cancel refreshTimer;
    private Async.Cancel retryTimer;
    private boolean stopped;
    private String problem = "";

    TrainerControl(Io io) {
        this.io = io;
    }

    Phase phase() {
        return phase;
    }

    Via via() {
        return via;
    }

    /** The command the trainer has confirmed, or null. */
    Command applied() {
        return applied;
    }

    /** Something the rider should know, or "". */
    String problem() {
        return problem;
    }

    void begin(boolean ftms, boolean replies, boolean wahoo) {
        hasWahoo = wahoo;
        ftmsReplies = replies;
        if (ftms) {
            via = Via.FTMS;
            if (!replies) io.log("Trainer: FTMS answers are off, so commands go out unconfirmed");
            requestControl();
        } else if (wahoo) {
            startWahoo("the trainer has no FTMS control point");
        } else {
            fail("This trainer has no way to set power (no FTMS and no Wahoo channel).");
        }
    }

    void setCommand(Command c) {
        target = c;
        if (phase == Phase.READY) send(false);
    }

    void stop() {
        stopped = true;
        token++;
        cancel(replyTimer);
        cancel(refreshTimer);
        cancel(retryTimer);
    }

    // ------------------------------------------------------------------ FTMS

    private void requestControl() {
        reset();
        phase = Phase.REQUESTING;
        requests++;
        changed();
        sendFtms(Protocol.FTMS_REQUEST_CONTROL, Protocol.ftmsRequestControl(), false);
    }

    private void startTraining() {
        reset();
        phase = Phase.STARTING;
        starts++;
        changed();
        sendFtms(Protocol.FTMS_START, Protocol.ftmsStart(), false);
    }

    private static boolean isCommandOp(int op) {
        return op == Protocol.FTMS_SET_TARGET_POWER || op == Protocol.FTMS_SET_SIMULATION;
    }

    private void sendFtms(final int op, byte[] data, boolean routine) {
        final int t = ++token;
        awaitOp = op;
        quiet = routine;
        if (!routine) io.log("Trainer: sent " + Protocol.ftmsOpName(op) + " [" + Protocol.hex(data) + "]");
        io.writeFtms(data, new Async.Done() {
            @Override
            public void done(int status) {
                if (stopped || t != token) return;
                if (status != Protocol.OK) {
                    ftmsWriteFailed(op, status);
                    return;
                }
                if (awaitOp != op) return; // the answer arrived first
                if (!ftmsReplies) {
                    ftmsResult(op, Protocol.RESULT_SUCCESS);
                    return;
                }
                replyTimer = io.schedule(new Runnable() {
                    @Override
                    public void run() {
                        if (!stopped && t == token && awaitOp == op) ftmsTimeout(op);
                    }
                }, REPLY_TIMEOUT_MS);
            }
        });
    }

    /** An indication from the FTMS control point. */
    void onFtmsReply(byte[] v) {
        if (stopped) return;
        if (v == null || v.length < 3 || (v[0] & 0xFF) != Protocol.FTMS_RESPONSE) {
            io.log("Trainer: control point sent [" + Protocol.hex(v) + "]");
            return;
        }
        int op = v[1] & 0xFF;
        int result = v[2] & 0xFF;
        boolean routineOk = quiet && op == awaitOp && result == Protocol.RESULT_SUCCESS;
        if (!routineOk) io.log("Trainer: " + Protocol.ftmsOpName(op) + " → " + Protocol.ftmsResultName(result));
        if (op != awaitOp) return;
        cancel(replyTimer);
        ftmsResult(op, result);
    }

    private void ftmsResult(int op, int result) {
        awaitOp = -1;
        if (isCommandOp(op)) {
            Command c = sending;
            sending = null;
            if (c == null) return;
            if (result == Protocol.RESULT_SUCCESS) {
                accepted(c);
            } else if (result == Protocol.RESULT_NOT_PERMITTED) {
                io.log("Trainer: control was lost, asking again");
                requests = 0;
                requestControl();
            } else {
                failures++;
                setProblem("The trainer refused " + c.label + " (" + Protocol.ftmsResultName(result) + ").");
            }
            return;
        }
        switch (op) {
            case Protocol.FTMS_REQUEST_CONTROL:
                if (result == Protocol.RESULT_SUCCESS) {
                    requests = 0;
                    waits = 0;
                    startTraining();
                } else if (result == Protocol.RESULT_NOT_PERMITTED) {
                    waitForControl();
                } else if (hasWahoo && !triedWahoo) {
                    startWahoo("FTMS answered " + Protocol.ftmsResultName(result));
                } else {
                    io.log("Trainer: carrying on without formal control");
                    startTraining();
                }
                break;
            case Protocol.FTMS_START:
                if (result == Protocol.RESULT_NOT_PERMITTED) {
                    requests = 0;
                    requestControl();
                } else if (result == Protocol.RESULT_SUCCESS || result == Protocol.RESULT_NOT_SUPPORTED || starts >= MAX_STARTS) {
                    starts = 0;
                    becomeReady();
                } else {
                    retry(new Runnable() {
                        @Override
                        public void run() {
                            startTraining();
                        }
                    }, 1000);
                }
                break;
            default:
                break;
        }
    }

    private void ftmsWriteFailed(int op, int status) {
        awaitOp = -1;
        String why = Protocol.opStatusName(status);
        io.log("Trainer: " + Protocol.ftmsOpName(op) + " could not be written: " + why);
        if (isCommandOp(op)) {
            sending = null;
            failures++;
            if (failures >= 3 && hasWahoo && !triedWahoo) {
                startWahoo("FTMS writes keep failing");
            } else {
                setProblem("The trainer did not take the change (" + why + ").");
                retrySend(2000);
            }
            return;
        }
        if (hasWahoo && !triedWahoo) {
            startWahoo("FTMS write refused: " + why);
        } else if (op == Protocol.FTMS_REQUEST_CONTROL && requests < MAX_REQUESTS) {
            retry(new Runnable() {
                @Override
                public void run() {
                    requestControl();
                }
            }, 2000);
        } else if (op == Protocol.FTMS_START) {
            becomeReady();
        } else {
            fail("The trainer refused control (" + why + "). Turn the trainer off and on, then tap Quit and open the app again.");
        }
    }

    private void ftmsTimeout(int op) {
        awaitOp = -1;
        io.log("Trainer: no answer to " + Protocol.ftmsOpName(op));
        if (isCommandOp(op)) {
            sending = null;
            failures++;
            if (failures >= 3 && hasWahoo && !triedWahoo) startWahoo("FTMS stopped answering");
            else retrySend(1000);
            return;
        }
        switch (op) {
            case Protocol.FTMS_REQUEST_CONTROL:
                if (requests < MAX_REQUESTS) {
                    requestControl();
                } else if (hasWahoo && !triedWahoo) {
                    startWahoo("FTMS did not answer");
                } else {
                    io.log("Trainer: carrying on without answers");
                    ftmsReplies = false;
                    startTraining();
                }
                break;
            case Protocol.FTMS_START:
                becomeReady();
                break;
            default:
                break;
        }
    }

    private void waitForControl() {
        waits++;
        phase = Phase.WAITING;
        setProblem("Another app or device is controlling the trainer. Close Zwift or other trainer apps, or turn off trainer control on the watch.");
        if (waits >= MAX_WAITS && hasWahoo && !triedWahoo) {
            startWahoo("FTMS control is held by another app or device");
            return;
        }
        retry(new Runnable() {
            @Override
            public void run() {
                requests = 0;
                requestControl();
            }
        }, waits >= MAX_WAITS ? 10000 : WAIT_FOR_CONTROL_MS);
    }

    private static int wattsOf(Command c) {
        return c == null ? -1 : c.watts;
    }

    /** A Machine Status notification. */
    void onMachineStatus(byte[] v) {
        if (stopped || v == null || v.length == 0) return;
        int op = v[0] & 0xFF;
        long now = io.now();
        switch (op) {
            case 0x08: {
                if (v.length < 3 || target == null || target.watts < 0) return; // only matters in ERG
                int w = Protocol.s16(v, 1);
                if (w == wattsOf(applied) || w == wattsOf(sending) || w == target.watts) return;
                io.log("Trainer: target changed to " + w + " W by another app or device");
                if (phase == Phase.READY && now - lastReassert > 5000) {
                    lastReassert = now;
                    send(false);
                }
                return;
            }
            case 0xFF:
                io.log("Trainer: " + Protocol.machineStatusName(v));
                if (via == Via.FTMS) {
                    requests = 0;
                    requestControl();
                }
                return;
            case 0x01:
                io.log("Trainer: reset");
                if (via == Via.FTMS) {
                    requests = 0;
                    requestControl();
                } else if (phase == Phase.READY) {
                    send(false);
                }
                return;
            case 0x02:
                io.log("Trainer: " + Protocol.machineStatusName(v));
                if (via == Via.FTMS && phase == Phase.READY && now - lastRestart > 10000) {
                    lastRestart = now;
                    starts = 0;
                    startTraining();
                }
                return;
            case 0x04:
                io.log("Trainer: started");
                return;
            default:
                // routine echoes such as "simulation changed"
        }
    }

    // ------------------------------------------------------------------ Wahoo channel

    private void startWahoo(String why) {
        reset();
        triedWahoo = true;
        via = Via.WAHOO;
        phase = Phase.REQUESTING;
        io.log("Trainer: switching to Wahoo's own channel (" + why + ")");
        changed();
        final int t = token;
        io.prepareWahoo(new Async.Done() {
            @Override
            public void done(int subscribed) {
                if (stopped || t != token) return;
                final int t2 = ++token;
                io.log("Trainer: sent Wahoo unlock [" + Protocol.hex(Protocol.wahooUnlock()) + "]");
                io.writeWahoo(Protocol.wahooUnlock(), new Async.Done() {
                    @Override
                    public void done(int status) {
                        if (stopped || t2 != token) return;
                        if (status == Protocol.OK) {
                            becomeReady();
                        } else {
                            fail("The trainer refused control on both channels (" + Protocol.opStatusName(status)
                                    + "). Turn the trainer off and on, then tap Quit and open the app again.");
                        }
                    }
                });
            }
        });
    }

    /** A notification from the Wahoo channel. */
    void onWahooReply(byte[] v) {
        if (stopped || via != Via.WAHOO || quiet) return;
        io.log("Trainer: Wahoo channel says [" + Protocol.hex(v) + "]");
    }

    // ------------------------------------------------------------------ shared

    private void becomeReady() {
        reset();
        phase = Phase.READY;
        problem = "";
        io.log("Trainer: under control (" + (via == Via.WAHOO ? "Wahoo channel" : "FTMS") + ")");
        changed();
        scheduleRefresh();
        send(false);
    }

    /** Re-sends the command every so often, in case another app or the watch changed it. */
    private void scheduleRefresh() {
        cancel(refreshTimer);
        refreshTimer = io.schedule(new Runnable() {
            @Override
            public void run() {
                if (stopped) return;
                if (phase == Phase.READY && sending == null && awaitOp < 0) send(true);
                scheduleRefresh();
            }
        }, REFRESH_MS);
    }

    private void send(boolean routine) {
        if (stopped || phase != Phase.READY || target == null) return;
        if (sending != null || awaitOp >= 0) return; // the answer to the one in flight sends the newest
        final Command c = target;
        if (via == Via.FTMS) {
            sending = c;
            sendFtms(c.op(), c.ftms, routine);
            return;
        }
        if (c.wahoo == null) {
            setProblem("Gears need the trainer's standard (FTMS) control, which it refused. ERG mode still works.");
            return;
        }
        sending = c;
        final int t = ++token;
        quiet = routine;
        if (!routine) io.log("Trainer: sent Wahoo ERG [" + Protocol.hex(c.wahoo) + "]");
        io.writeWahoo(c.wahoo, new Async.Done() {
            @Override
            public void done(int status) {
                if (stopped || t != token) return;
                sending = null;
                if (status == Protocol.OK) {
                    accepted(c);
                } else {
                    failures++;
                    io.log("Trainer: Wahoo ERG could not be written: " + Protocol.opStatusName(status));
                    setProblem("The trainer did not take the change (" + Protocol.opStatusName(status) + ").");
                    retrySend(2000);
                }
            }
        });
    }

    private void accepted(Command c) {
        failures = 0;
        if (!c.sameAs(applied)) io.log("Trainer: set to " + c.label);
        applied = c;
        problem = "";
        changed();
        if (target != null && !target.sameAs(applied)) send(false);
    }

    private void retrySend(long delayMs) {
        retry(new Runnable() {
            @Override
            public void run() {
                send(false);
            }
        }, delayMs);
    }

    private void retry(final Runnable step, long delayMs) {
        cancel(retryTimer);
        retryTimer = io.schedule(new Runnable() {
            @Override
            public void run() {
                if (!stopped) step.run();
            }
        }, delayMs);
    }

    /** Forgets whatever is in flight; answers to it are ignored from now on. */
    private void reset() {
        token++;
        awaitOp = -1;
        sending = null;
        cancel(replyTimer);
        cancel(retryTimer);
    }

    private void fail(String why) {
        reset();
        phase = Phase.FAILED;
        problem = why;
        io.log("Trainer: " + why);
        changed();
    }

    private void setProblem(String text) {
        problem = text;
        changed();
    }

    private void changed() {
        io.changed();
    }

    private static void cancel(Async.Cancel c) {
        if (c != null) c.cancel();
    }
}

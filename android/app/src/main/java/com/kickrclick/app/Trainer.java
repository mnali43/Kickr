package com.kickrclick.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.SystemClock;

import java.util.UUID;

/** The KICKR: its connection, its data, and the {@link TrainerControl} that sets its power. */
final class Trainer implements BleLink.Listener {
    final String name;
    private final Hub hub;
    private final BleLink link;
    private TrainerControl control;
    private BluetoothGattCharacteristic controlPoint;
    private BluetoothGattCharacteristic wahoo;
    private boolean wahooSubscribed;
    private boolean everReady;

    Trainer(Hub hub, BluetoothDevice device, String name) {
        this.hub = hub;
        this.name = name;
        this.link = new BleLink(hub, device, "Trainer", this, true);
    }

    void connect() {
        link.connect();
    }

    void close() {
        if (control != null) control.stop();
        link.close("closed by the app");
    }

    boolean isConnected() {
        return link.isReady();
    }

    boolean wasEverReady() {
        return everReady;
    }

    String address() {
        return link.address();
    }

    /** Null until the connection is set up. */
    TrainerControl control() {
        return control;
    }

    void setTarget(int watts) {
        if (control != null) control.setTarget(watts);
    }

    @Override
    public void onLinkReady(BleLink l) {
        everReady = true;
        hub.log("Trainer: services " + l.describeServices());
        controlPoint = l.find(Protocol.FTMS_SERVICE, Protocol.FTMS_CONTROL_POINT);
        BluetoothGattCharacteristic status = l.find(Protocol.FTMS_SERVICE, Protocol.FTMS_STATUS);
        BluetoothGattCharacteristic bike = l.find(Protocol.FTMS_SERVICE, Protocol.FTMS_BIKE_DATA);
        BluetoothGattCharacteristic power = l.find(Protocol.CPS_SERVICE, Protocol.CPS_MEASUREMENT);
        wahoo = l.find(null, Protocol.WAHOO_TRAINER);
        hub.log("Trainer: FTMS control " + yesNo(controlPoint) + ", Wahoo channel " + yesNo(wahoo)
                + ", bike data " + yesNo(bike) + ", power data " + yesNo(power));

        // The control point first: control matters more than numbers on the screen.
        final boolean[] answersOn = {false};
        if (controlPoint != null) {
            l.subscribe(controlPoint, new Async.Done() {
                @Override
                public void done(int s) {
                    answersOn[0] = s == Protocol.OK;
                }
            });
        }
        if (status != null) l.subscribe(status, null);
        if (bike != null) l.subscribe(bike, null);
        if (power != null) l.subscribe(power, null);
        l.then(new Runnable() {
            @Override
            public void run() {
                control = new TrainerControl(io);
                control.setTarget(hub.target());
                control.begin(controlPoint != null, answersOn[0], wahoo != null);
                hub.changed();
            }
        });
        hub.changed();
    }

    @Override
    public void onLinkData(BleLink l, BluetoothGattCharacteristic ch, byte[] v) {
        UUID u = ch.getUuid();
        if (Protocol.FTMS_CONTROL_POINT.equals(u)) {
            if (control != null) control.onFtmsReply(v);
            else hub.log("Trainer: early control point message [" + Protocol.hex(v) + "]");
        } else if (Protocol.FTMS_STATUS.equals(u)) {
            if (control != null) control.onMachineStatus(v);
        } else if (Protocol.FTMS_BIKE_DATA.equals(u)) {
            hub.onBikeData(Protocol.parseBikeData(v));
        } else if (Protocol.CPS_MEASUREMENT.equals(u)) {
            hub.onPowerData(Protocol.parsePower(v));
        } else if (Protocol.WAHOO_TRAINER.equals(u)) {
            if (control != null) control.onWahooReply(v);
        }
    }

    @Override
    public void onLinkClosed(BleLink l, String reason) {
        if (control != null) control.stop();
        hub.onTrainerClosed(this, reason);
    }

    private final TrainerControl.Io io = new TrainerControl.Io() {
        @Override
        public void writeFtms(byte[] data, Async.Done done) {
            send(controlPoint, data, done);
        }

        @Override
        public void prepareWahoo(Async.Done done) {
            if (wahoo == null || link.isClosed()) {
                done.done(Protocol.NOT_SENT);
            } else if (wahooSubscribed) {
                done.done(Protocol.OK);
            } else {
                wahooSubscribed = true;
                link.subscribe(wahoo, done);
            }
        }

        @Override
        public void writeWahoo(byte[] data, Async.Done done) {
            send(wahoo, data, done);
        }

        @Override
        public Async.Cancel schedule(Runnable task, long delayMs) {
            return hub.schedule(task, delayMs);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void log(String message) {
            hub.log(message);
        }

        @Override
        public void changed() {
            hub.changed();
        }
    };

    private void send(BluetoothGattCharacteristic ch, byte[] data, Async.Done done) {
        if (ch == null || link.isClosed()) {
            done.done(Protocol.NOT_SENT);
            return;
        }
        link.write(ch, data, true, done);
    }

    private static String yesNo(Object o) {
        return o != null ? "yes" : "no";
    }
}

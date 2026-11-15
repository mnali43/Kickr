package com.kickrclick.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.SystemClock;

import java.util.UUID;

/** One Zwift Click pod: its connection and the {@link ClickControl} that reads its buttons. */
final class ClickPod implements BleLink.Listener {
    private static final UUID BATTERY_SERVICE = Protocol.uuid16(0x180F);
    private static final UUID BATTERY_LEVEL = Protocol.uuid16(0x2A19);

    final int type;
    final int side; // Hub.RIGHT or Hub.LEFT
    final String name;
    private final Hub hub;
    private final BleLink link;
    private final boolean verbose;
    private ClickControl control;
    private BluetoothGattCharacteristic commands;
    private int battery = -1;
    private boolean everConnected;
    private boolean restartRequested;

    /** @param verbose log everything (the first connections); routine reconnects only log problems */
    ClickPod(Hub hub, BluetoothDevice device, int type, int side, boolean verbose) {
        this.hub = hub;
        this.type = type;
        this.side = side;
        this.verbose = verbose;
        this.name = type == Protocol.ZWIFT_CLICK_V1 ? "Click" : side == Hub.LEFT ? "Left Click" : "Right Click";
        this.link = new BleLink(hub, device, name, this, false, true);
        this.link.setQuiet(!verbose);
    }

    void connect() {
        link.connect();
    }

    void close() {
        if (control != null) control.stop();
        link.close("closed by the app");
    }

    boolean isConnected() {
        return control != null && link.isReady();
    }

    boolean linkReady() {
        return link.isReady();
    }

    boolean wasEverConnected() {
        return everConnected;
    }

    /** The pod was told to restart, so losing it is expected. */
    boolean restartRequested() {
        return restartRequested;
    }

    boolean pressesSeen() {
        return control != null && control.pressesSeen();
    }

    String address() {
        return link.address();
    }

    int battery() {
        return battery;
    }

    @Override
    public void onLinkReady(final BleLink l) {
        everConnected = true;
        if (verbose) hub.log(name + ": services " + l.describeServices());
        BluetoothGattService s = l.service(Protocol.ZWIFT_SERVICE);
        if (s == null) s = l.service(Protocol.ZWIFT_SERVICE_OLD);
        if (s == null) {
            hub.ignore(l.address(), "it does not offer the Zwift controller service");
            l.close("not usable");
            return;
        }
        commands = s.getCharacteristic(Protocol.ZWIFT_SYNC_RX);
        if (commands == null) {
            hub.ignore(l.address(), "its Zwift service has no command characteristic");
            l.close("not usable");
            return;
        }
        // Listen to everything the Zwift service can send, so nothing the pod says is missed.
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
            int p = c.getProperties();
            if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                l.subscribe(c, null);
            }
        }
        BluetoothGattCharacteristic level = l.find(BATTERY_SERVICE, BATTERY_LEVEL);
        if (level != null && (level.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            l.subscribe(level, null);
        }
        l.then(new Runnable() {
            @Override
            public void run() {
                ClickControl.Keep keep = type == Protocol.ZWIFT_CLICK_V1 ? ClickControl.Keep.NONE
                        : side == Hub.LEFT ? ClickControl.Keep.EVERY_MINUTE : ClickControl.Keep.WHEN_IDLE;
                control = new ClickControl(io, name, keep, type == Protocol.ZWIFT_CLICK_V1 ? 0 : hub.clickGreeting(), verbose);
                control.begin();
                l.relax();
                hub.onClickReady(ClickPod.this);
            }
        });
    }

    @Override
    public void onLinkData(BleLink l, BluetoothGattCharacteristic ch, byte[] v) {
        if (BATTERY_LEVEL.equals(ch.getUuid())) {
            if (v.length > 0) io.battery(v[0] & 0xFF);
            return;
        }
        if (control != null) control.onMessage(Protocol.shortUuid(ch.getUuid()), v);
    }

    @Override
    public void onLinkClosed(BleLink l, String reason) {
        if (control != null) control.stop();
        hub.onClickClosed(this, reason);
    }

    private final ClickControl.Io io = new ClickControl.Io() {
        @Override
        public void write(byte[] data) {
            if (commands != null && !link.isClosed()) link.write(commands, data, false, null);
        }

        @Override
        public void log(String message) {
            hub.log(message);
        }

        @Override
        public void pressed(int button) {
            hub.onClickButton(ClickPod.this, button);
        }

        @Override
        public void battery(int percent) {
            if (percent != battery && percent >= 0 && percent <= 100) {
                battery = percent;
                hub.changed();
            }
        }

        @Override
        public void restarting() {
            restartRequested = true;
            hub.onClickRestarting(ClickPod.this);
        }

        @Override
        public void greetingWorks(int greeting) {
            hub.rememberClickGreeting(greeting);
        }

        @Override
        public Async.Cancel schedule(Runnable task, long delayMs) {
            return hub.schedule(task, delayMs);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }
    };
}

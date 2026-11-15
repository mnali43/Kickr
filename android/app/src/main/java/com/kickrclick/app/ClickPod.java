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
    private final Hub hub;
    private final BleLink link;
    private ClickControl control;
    private BluetoothGattCharacteristic commands;
    private int battery = -1;
    private boolean everConnected;

    ClickPod(Hub hub, BluetoothDevice device, int type) {
        this.hub = hub;
        this.type = type;
        this.link = new BleLink(hub, device, "Click", this, false);
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
    public void onLinkReady(BleLink l) {
        everConnected = true;
        hub.log("Click: services " + l.describeServices());
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
                boolean v2 = type != Protocol.ZWIFT_CLICK_V1;
                control = new ClickControl(io, v2, v2 ? hub.clickGreeting() : 0);
                control.begin();
                hub.changed();
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
            hub.onClickButton(button);
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
            hub.watchForClickRestart();
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

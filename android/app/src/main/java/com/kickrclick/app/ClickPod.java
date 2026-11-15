package com.kickrclick.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.SystemClock;

/** One Zwift Click pod: its connection and the {@link ClickControl} that reads its buttons. */
final class ClickPod implements BleLink.Listener {
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

    long connectedForMs() {
        return link.readyForMs();
    }

    boolean wasEverConnected() {
        return everConnected;
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
        BluetoothGattService s = l.service(Protocol.ZWIFT_SERVICE);
        if (s == null) s = l.service(Protocol.ZWIFT_SERVICE_OLD);
        if (s == null) {
            hub.log("Click: the Zwift service is missing; it has " + l.describeServices());
            hub.ignore(l.address(), "it does not offer the Zwift controller service");
            l.close("not usable");
            return;
        }
        BluetoothGattCharacteristic messages = s.getCharacteristic(Protocol.ZWIFT_ASYNC);
        BluetoothGattCharacteristic replies = s.getCharacteristic(Protocol.ZWIFT_SYNC_TX);
        commands = s.getCharacteristic(Protocol.ZWIFT_SYNC_RX);
        if (messages == null || replies == null || commands == null) {
            hub.log("Click: some Zwift characteristics are missing; it has " + l.describeServices());
            hub.ignore(l.address(), "its Zwift service is incomplete");
            l.close("not usable");
            return;
        }
        l.subscribe(messages, null);
        l.subscribe(replies, null);
        l.then(new Runnable() {
            @Override
            public void run() {
                control = new ClickControl(io, type != Protocol.ZWIFT_CLICK_V1);
                control.begin();
                hub.changed();
            }
        });
    }

    @Override
    public void onLinkData(BleLink l, BluetoothGattCharacteristic ch, byte[] v) {
        if (control == null) return;
        if (Protocol.ZWIFT_SYNC_TX.equals(ch.getUuid())) control.onReply(v);
        else control.onMessage(v);
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
            if (percent != battery) {
                battery = percent;
                hub.changed();
            }
        }

        @Override
        public void restarting() {
            hub.watchForClickRestart();
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

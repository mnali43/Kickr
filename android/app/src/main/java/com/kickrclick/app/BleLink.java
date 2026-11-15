package com.kickrclick.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * One Bluetooth LE connection. Android runs one GATT request at a time, so every write and
 * subscription goes through a queue and waits for its answer. All callbacks are moved to the
 * main thread.
 */
@SuppressLint("MissingPermission")
final class BleLink {
    interface Listener {
        void onLinkReady(BleLink link);

        void onLinkData(BleLink link, BluetoothGattCharacteristic ch, byte[] value);

        void onLinkClosed(BleLink link, String reason);
    }

    private interface Starter {
        int start();
    }

    private static final class Op {
        final String what;
        final Starter starter;
        final Async.Done done;

        Op(String what, Starter starter, Async.Done done) {
            this.what = what;
            this.starter = starter;
            this.done = done;
        }
    }

    private static final int STARTED = 0; // waiting for Android's callback
    private static final int FINISHED = 1; // completed without a callback
    private static final int MTU = 517;
    private static final long OP_TIMEOUT_MS = 5000;
    private static final long CONNECT_TIMEOUT_MS = 20000;

    final BluetoothDevice device;
    final String tag;
    private final Hub hub;
    private final Listener listener;
    private final boolean refreshServices;
    private final Handler main;
    private final ArrayDeque<Op> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private Op current;
    private int busyRetries;
    private boolean closed;
    private boolean ready;
    private boolean discovering;
    private long readyAt;

    BleLink(Hub hub, BluetoothDevice device, String tag, Listener listener, boolean refreshServices) {
        this.hub = hub;
        this.device = device;
        this.tag = tag;
        this.listener = listener;
        this.refreshServices = refreshServices;
        this.main = hub.main;
    }

    boolean isClosed() {
        return closed;
    }

    boolean isReady() {
        return ready && !closed;
    }

    long readyForMs() {
        return ready ? SystemClock.elapsedRealtime() - readyAt : 0;
    }

    String address() {
        return device.getAddress();
    }

    void connect() {
        hub.log(tag + ": connecting");
        try {
            gatt = device.connectGatt(hub.app, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException e) {
            close("could not start the connection (" + e.getClass().getSimpleName() + ")");
            return;
        }
        if (gatt == null) {
            close("Android would not start the connection");
            return;
        }
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    /** Ends the connection. The listener hears about it once, a moment later. */
    void close(final String reason) {
        if (closed) return;
        closed = true;
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(opTimeout);
        main.removeCallbacks(mtuFallback);
        queue.clear();
        current = null;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (RuntimeException ignored) {
                // already gone
            }
            try {
                g.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onLinkClosed(BleLink.this, reason);
            }
        });
    }

    // ------------------------------------------------------------------ Android callbacks

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onConnectionState(g, status, newState);
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, final int mtu, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onMtu(g, mtu, status);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onServices(g, status);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onOpResult(g, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onOpResult(g, status);
                }
            });
        }

        // Android 13 and newer
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliver(g, c, value);
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            deliver(g, c, c.getValue());
        }

        private void deliver(final BluetoothGatt g, final BluetoothGattCharacteristic c, byte[] value) {
            final byte[] copy = value == null ? new byte[0] : value.clone();
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt || closed) return;
                    listener.onLinkData(BleLink.this, c, copy);
                }
            });
        }
    };

    private void onConnectionState(final BluetoothGatt g, int status, int newState) {
        if (g != gatt || closed) return;
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            hub.log(tag + ": connected, reading its services");
            if (refreshServices) refreshSavedServices(g);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt && !closed) askForBiggerPackets(g);
                }
            }, 400);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            close((ready ? "disconnected: " : "could not connect: ") + Protocol.linkStatusName(status));
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            close("connection error: " + Protocol.linkStatusName(status));
        }
    }

    /** Android keeps a saved copy of a device's services; a stale copy sends writes to the wrong place. */
    private void refreshSavedServices(BluetoothGatt g) {
        try {
            Method refresh = g.getClass().getMethod("refresh");
            Object ok = refresh.invoke(g);
            hub.log(tag + ": cleared Android's saved copy of its services" + (Boolean.TRUE.equals(ok) ? "" : " (not supported)"));
        } catch (Exception | LinkageError e) {
            hub.log(tag + ": could not clear the saved services (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void askForBiggerPackets(BluetoothGatt g) {
        boolean asked = false;
        try {
            asked = g.requestMtu(MTU);
        } catch (RuntimeException ignored) {
            // carry on with the default size
        }
        if (asked) main.postDelayed(mtuFallback, 3000);
        else discover();
    }

    private final Runnable mtuFallback = new Runnable() {
        @Override
        public void run() {
            discover();
        }
    };

    private void onMtu(BluetoothGatt g, int mtu, int status) {
        if (g != gatt || closed) return;
        main.removeCallbacks(mtuFallback);
        discover();
    }

    private void discover() {
        BluetoothGatt g = gatt;
        if (closed || discovering || g == null) return;
        discovering = true;
        boolean ok = false;
        try {
            ok = g.discoverServices();
        } catch (RuntimeException ignored) {
            // reported below
        }
        if (!ok) close("could not read its services");
    }

    private void onServices(BluetoothGatt g, int status) {
        if (g != gatt || closed || ready) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            close("reading its services failed: " + Protocol.opStatusName(status));
            return;
        }
        ready = true;
        readyAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(connectTimeout);
        listener.onLinkReady(this);
        next();
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (!ready) close("connection timed out");
        }
    };

    // ------------------------------------------------------------------ the request queue

    private void enqueue(Op op) {
        if (closed) return;
        queue.addLast(op);
        next();
    }

    private void next() {
        while (current == null && ready && !closed && !queue.isEmpty()) {
            Op op = queue.pollFirst();
            current = op;
            int r;
            try {
                r = op.starter.start();
            } catch (SecurityException e) {
                r = Protocol.NO_PERMISSION;
            } catch (RuntimeException e) {
                hub.log(tag + ": " + op.what + " failed: " + e);
                r = Protocol.NOT_SENT;
            }
            if (closed) return;
            if (r == STARTED) {
                main.removeCallbacks(opTimeout);
                main.postDelayed(opTimeout, OP_TIMEOUT_MS);
                return;
            }
            current = null;
            if (r == Protocol.BUSY && busyRetries < 20) {
                busyRetries++;
                queue.addFirst(op);
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        next();
                    }
                }, 100);
                return;
            }
            busyRetries = 0;
            complete(op, r == FINISHED ? Protocol.OK : r);
        }
    }

    private void complete(Op op, int status) {
        if (op.done == null) return;
        try {
            op.done.done(status);
        } catch (RuntimeException e) {
            hub.log(tag + ": error after " + op.what + ": " + e);
        }
    }

    private void onOpResult(BluetoothGatt g, int status) {
        if (g != gatt || closed) return;
        Op op = current;
        if (op == null) return;
        current = null;
        busyRetries = 0;
        main.removeCallbacks(opTimeout);
        complete(op, status);
        next();
    }

    private final Runnable opTimeout = new Runnable() {
        @Override
        public void run() {
            Op op = current;
            if (op == null || closed) return;
            current = null;
            hub.log(tag + ": no answer to " + op.what);
            complete(op, Protocol.TIMEOUT);
            next();
        }
    };

    // ------------------------------------------------------------------ what the rest of the app uses

    BluetoothGattService service(UUID uuid) {
        BluetoothGatt g = gatt;
        return g == null ? null : g.getService(uuid);
    }

    /** Finds a characteristic, in one service or (service == null) in any. */
    BluetoothGattCharacteristic find(UUID service, UUID characteristic) {
        BluetoothGatt g = gatt;
        if (g == null) return null;
        for (BluetoothGattService s : g.getServices()) {
            if (service != null && !service.equals(s.getUuid())) continue;
            BluetoothGattCharacteristic c = s.getCharacteristic(characteristic);
            if (c != null) return c;
        }
        return null;
    }

    /** Switches on notifications (or indications) and reports the result to {@code done}. */
    void subscribe(final BluetoothGattCharacteristic ch, final Async.Done done) {
        final String name = Protocol.shortUuid(ch.getUuid());
        int props = ch.getProperties();
        boolean notify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean indicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        if (!notify && !indicate) {
            hub.log(tag + ": " + name + " cannot send updates");
            if (done != null) done.done(Protocol.NOT_SENT);
            return;
        }
        final byte[] value = notify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        enqueue(new Op("updates from " + name, new Starter() {
            @Override
            public int start() {
                BluetoothGatt g = gatt;
                if (g == null) return Protocol.NOT_SENT;
                if (!g.setCharacteristicNotification(ch, true)) return Protocol.NOT_SENT;
                BluetoothGattDescriptor d = ch.getDescriptor(Protocol.CCCD);
                if (d == null) return Protocol.NO_CCCD;
                return writeDescriptor(g, d, value);
            }
        }, new Async.Done() {
            @Override
            public void done(int status) {
                hub.log(tag + ": updates from " + name + (status == Protocol.OK ? " on" : " refused: " + Protocol.opStatusName(status)));
                if (done != null) done.done(status);
            }
        }));
    }

    /** Writes a value. Uses write-with-answer when asked and the characteristic allows it. */
    void write(final BluetoothGattCharacteristic ch, final byte[] data, boolean withAnswer, Async.Done done) {
        int props = ch.getProperties();
        boolean canAnswer = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean canCommand = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        final int type = (withAnswer && canAnswer) || !canCommand
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        enqueue(new Op("write to " + Protocol.shortUuid(ch.getUuid()), new Starter() {
            @Override
            public int start() {
                BluetoothGatt g = gatt;
                if (g == null) return Protocol.NOT_SENT;
                return writeCharacteristic(g, ch, data, type);
            }
        }, done));
    }

    /** Runs {@code step} once every request queued before it has finished. */
    void then(final Runnable step) {
        enqueue(new Op("next step", new Starter() {
            @Override
            public int start() {
                step.run();
                return FINISHED;
            }
        }, null));
    }

    @SuppressWarnings("deprecation")
    private static int writeCharacteristic(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] data, int type) {
        if (Build.VERSION.SDK_INT >= 33) return startCode(g.writeCharacteristic(ch, data, type));
        ch.setWriteType(type);
        ch.setValue(data);
        return g.writeCharacteristic(ch) ? STARTED : Protocol.BUSY;
    }

    @SuppressWarnings("deprecation")
    private static int writeDescriptor(BluetoothGatt g, BluetoothGattDescriptor d, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) return startCode(g.writeDescriptor(d, value));
        d.setValue(value);
        return g.writeDescriptor(d) ? STARTED : Protocol.BUSY;
    }

    private static int startCode(int rc) {
        if (rc == BluetoothStatusCodes.SUCCESS) return STARTED;
        if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) return Protocol.BUSY;
        if (rc == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) return Protocol.NO_PERMISSION;
        return Protocol.NOT_SENT;
    }

    /** A one-line summary of the device's services, for the log. */
    String describeServices() {
        BluetoothGatt g = gatt;
        if (g == null) return "(none)";
        StringBuilder b = new StringBuilder();
        for (BluetoothGattService s : g.getServices()) {
            if (b.length() > 0) b.append("; ");
            b.append(Protocol.shortUuid(s.getUuid())).append(" [");
            boolean first = true;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (!first) b.append(", ");
                first = false;
                b.append(Protocol.shortUuid(c.getUuid())).append(' ').append(properties(c.getProperties()));
            }
            b.append(']');
        }
        return b.toString();
    }

    private static String properties(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append('r');
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append('w');
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append('c');
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append('n');
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append('i');
        return b.length() == 0 ? "-" : b.toString();
    }
}

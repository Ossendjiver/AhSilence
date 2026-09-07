package com.p38.anclab.telemetry;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small generic ELM327-over-Bluetooth SPP reader for standard OBD-II live PIDs. */
public final class ObdClient implements AutoCloseable {
    public record DeviceChoice(String address, String name) {
        @Override public String toString() { return name + " · " + address; }
    }
    public interface Listener {
        void onObd(double speedKmh, double rpm, double loadPercent, double throttlePercent,
                   String status, long nowMs);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile BluetoothSocket socket;
    private volatile Thread worker;

    public ObdClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public List<DeviceChoice> pairedDevices() {
        List<DeviceChoice> result = new ArrayList<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasConnectPermission()) return result;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            String name = device.getName();
            result.add(new DeviceChoice(device.getAddress(), name == null ? "Paired Bluetooth device" : name));
        }
        return result;
    }

    public void connect(String address) {
        close();
        if (!hasConnectPermission()) {
            listener.onObd(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    "Bluetooth permission required", SystemClock.elapsedRealtime());
            return;
        }
        running.set(true);
        worker = new Thread(() -> runConnection(address), "P38-OBD");
        worker.start();
    }

    @SuppressLint("MissingPermission")
    private void runConnection(String address) {
        double speed = Double.NaN, rpm = Double.NaN, load = Double.NaN, throttle = Double.NaN;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) throw new IOException("Bluetooth is unavailable");
            listener.onObd(speed, rpm, load, throttle, "Connecting to OBD…", SystemClock.elapsedRealtime());
            BluetoothDevice device = adapter.getRemoteDevice(address);
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            command(input, output, "ATZ", 2500);
            command(input, output, "ATE0", 1200);
            command(input, output, "ATL0", 1200);
            command(input, output, "ATS0", 1200);
            command(input, output, "ATH0", 1200);
            command(input, output, "ATSP0", 3000);
            while (running.get()) {
                Double v = pidByte(command(input, output, "010D", 1500), "410D");
                Double r = pidRpm(command(input, output, "010C", 1500));
                Double l = pidPercent(command(input, output, "0104", 1500), "4104");
                Double t = pidPercent(command(input, output, "0111", 1500), "4111");
                if (v != null) speed = v;
                if (r != null) rpm = r;
                if (l != null) load = l;
                if (t != null) throttle = t;
                listener.onObd(speed, rpm, load, throttle, "OBD connected", SystemClock.elapsedRealtime());
            }
        } catch (Throwable exception) {
            if (running.get()) {
                String message = exception.getMessage();
                listener.onObd(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        "OBD error: " + (message == null ? "connection stopped" : message), SystemClock.elapsedRealtime());
            }
        } finally {
            running.set(false);
            closeSocket();
        }
    }

    private static String command(InputStream input, OutputStream output, String command, long timeoutMs) throws IOException {
        while (input.available() > 0) input.read();
        output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        output.flush();
        StringBuilder response = new StringBuilder();
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            while (input.available() > 0) {
                int value = input.read();
                if (value < 0) throw new IOException("OBD disconnected");
                if (value == '>') return normalize(response.toString());
                response.append((char) value);
            }
            try { Thread.sleep(12); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); throw new IOException("OBD stopped", exception);
            }
        }
        throw new IOException("OBD timeout on " + command);
    }

    private static String normalize(String raw) {
        return raw.toUpperCase(Locale.US).replace("SEARCHING...", "")
                .replaceAll("[^0-9A-F]", "");
    }

    private static Double pidByte(String response, String marker) {
        int i = response.indexOf(marker);
        if (i < 0 || response.length() < i + marker.length() + 2) return null;
        return (double) Integer.parseInt(response.substring(i + marker.length(), i + marker.length() + 2), 16);
    }

    private static Double pidRpm(String response) {
        int i = response.indexOf("410C");
        if (i < 0 || response.length() < i + 8) return null;
        int a = Integer.parseInt(response.substring(i + 4, i + 6), 16);
        int b = Integer.parseInt(response.substring(i + 6, i + 8), 16);
        return (256.0 * a + b) / 4.0;
    }

    private static Double pidPercent(String response, String marker) {
        Double value = pidByte(response, marker);
        return value == null ? null : value * 100.0 / 255.0;
    }

    public boolean isRunning() { return running.get(); }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void close() {
        running.set(false);
        closeSocket();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        worker = null;
    }

    private void closeSocket() {
        BluetoothSocket current = socket;
        socket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}

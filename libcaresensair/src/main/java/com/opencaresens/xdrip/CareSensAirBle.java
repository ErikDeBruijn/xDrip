package com.opencaresens.xdrip;

import static com.opencaresens.xdrip.iface.DataKey.*;
import static com.opencaresens.xdrip.iface.State.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import com.opencaresens.air.BlePacketParser;
import com.opencaresens.air.CalibrationResult;
import com.opencaresens.air.CareSensCalibrator;
import com.opencaresens.air.SensorConfig;
import com.opencaresens.xdrip.iface.DataKey;
import com.opencaresens.xdrip.iface.Listener;
import com.opencaresens.xdrip.iface.State;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.observer.ConnectionObserver;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

/**
 * CareSens Air BLE orchestrator.
 *
 * Handles BLE scanning, connection management, characteristic subscriptions,
 * packet parsing, and calibration for CareSens Air CGM sensors.
 *
 * Follows the GluProBle pattern from xDrip+.
 */
public class CareSensAirBle {

    public static final String TAG = CareSensAirBle.class.getSimpleName();

    // TODO: Replace with actual CareSens Air BLE UUIDs once confirmed from device testing
    // These are placeholder UUIDs. The real device may use a proprietary service UUID
    // or the standard CGM service (0x181F).
    private static final UUID SERVICE_UUID =
            UUID.fromString("0000181F-0000-1000-8000-00805f9b34fb"); // TODO: confirm

    // The C5 characteristic that delivers 84-byte sensor data notifications
    private static final UUID C5_CHARACTERISTIC_UUID =
            UUID.fromString("00002AC5-0000-1000-8000-00805f9b34fb"); // TODO: confirm

    // Known device name prefix for scan filtering
    private static final String DEVICE_NAME_PREFIX = "CareSens"; // TODO: confirm exact name

    private final Context context;
    private volatile InternalManager bleManager;
    private Listener listener;
    private volatile String targetAddress;
    private volatile BluetoothDevice connectedDevice;

    private volatile boolean shouldReconnect = false;
    private volatile int reconnectAttempt = 0;

    private final Map<DataKey, String> data = new ConcurrentHashMap<>();

    private volatile State lastState = INIT;

    private CareSensCalibrator calibrator;
    private SensorConfig sensorConfig;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingReconnect;
    private Runnable pendingScanCancel;

    private ILog logger;

    /**
     * Simple logging interface for injection from the host app.
     */
    public interface ILog {
        void d(String tag, String msg);

        void e(String tag, String msg);
    }

    public CareSensAirBle(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public void setLogger(ILog logger) {
        this.logger = logger;
    }

    /**
     * Configure the sensor calibration parameters.
     * Must be called before start() or when a new sensor is detected.
     *
     * @param config sensor factory calibration from BLE advertisement
     */
    public void setSensorConfig(SensorConfig config) {
        this.sensorConfig = config;
        this.calibrator = new CareSensCalibrator(config);
    }

    /**
     * Restore calibrator state from previously saved bytes.
     *
     * @param stateBytes saved state from CareSensCalibrator.saveState()
     * @param config     sensor factory calibration parameters
     */
    public void restoreCalibrator(byte[] stateBytes, SensorConfig config) {
        this.sensorConfig = config;
        this.calibrator = CareSensCalibrator.restoreState(stateBytes, config);
    }

    /**
     * Save calibrator state for persistence across restarts.
     *
     * @return serialized state bytes, or null if calibrator not initialized
     */
    @Nullable
    public byte[] saveCalibratorState() {
        if (calibrator != null) {
            return calibrator.saveState();
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start(String deviceAddress, Listener listener) {
        this.targetAddress = deviceAddress;
        this.listener = listener;
        initManager();

        this.shouldReconnect = true;
        if (targetAddress != null && !targetAddress.isEmpty()) {
            log("Connecting to known device: " + targetAddress);
            // TODO: use getDeviceFromMac once we confirm BLE address handling
            startScan();
        } else {
            log("Starting general scan for CareSens Air");
            startScan();
        }
    }

    public synchronized void stop() {
        shouldReconnect = false;
        stopScan();
        cancelPendingReconnect();
        if (bleManager != null) {
            bleManager.disconnect().enqueue();
            bleManager.close();
            bleManager = null;
        }
        setState(SHUTDOWN);
    }

    private synchronized void initManager() {
        if (bleManager == null) {
            log("Initializing BLE manager");
            bleManager = new InternalManager(context);
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        log("Scanning for CareSens Air devices...");

        if (bleManager != null) {
            bleManager.disconnect().enqueue();
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Scan by service UUID or device name - try both strategies
        List<ScanFilter> filters = new ArrayList<>();
        // TODO: Once service UUID is confirmed, add UUID filter:
        // filters.add(new ScanFilter.Builder()
        //         .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID.toString()))
        //         .build());

        // For now, scan without filters and match by device name in callback
        try {
            stopScan();
            setState(SCANNING);
            scheduleScanCancel(30_000);
            BluetoothLeScannerCompat.getScanner()
                    .startScan(filters, settings, scannerCallback);
        } catch (SecurityException e) {
            logError("Permission Error scanning: " + e);
            setState(INSUFFICIENT_PERMISSIONS);
        } catch (IllegalArgumentException e) {
            log("Got error with scanning: " + e);
            setState(SCANNING_ERROR);
        } catch (IllegalStateException e) {
            log("Got state exception error with scanning: " + e);
            setState(SCANNING_ERROR);
        } catch (Exception e) {
            setState(SCANNING_ERROR);
            logError("Error starting scan: " + e);
        }
    }

    private synchronized void stopScan() {
        log("Stopping scan");
        cancelPendingScanCancel();
        try {
            BluetoothLeScannerCompat.getScanner().stopScan(scannerCallback);
        } catch (Exception ignore) {
        }
        if (lastState == SCANNING) {
            setState(SCAN_STOPPED);
        }
    }

    private final ScanCallback scannerCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();

            // Filter by device name prefix
            if (name == null || !name.startsWith(DEVICE_NAME_PREFIX)) {
                return;
            }

            log("Found CareSens Air device: " + name + " [" + device.getAddress() + "]");

            // TODO: Parse BLE advertisement for SensorConfig parameters here.
            // The advertisement contains factory calibration values (eapp, slope100, vref, etc.)
            // For now, SensorConfig must be set externally via setSensorConfig().
            // byte[] advData = result.getScanRecord() != null
            //         ? result.getScanRecord().getBytes() : null;
            // parseSensorConfigFromAdvertisement(advData);

            // Match target or connect to first found device
            boolean isTarget = targetAddress != null
                    && (targetAddress.equals(device.getAddress()) || targetAddress.equals(name));

            if (isTarget || targetAddress == null || targetAddress.isEmpty()) {
                log("Matched target, stopping scan and connecting");
                stopScan();
                reconnectAttempt = 0;
                connect(device);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(final BluetoothDevice device) {
        if (device == null) {
            log("Device is null, cannot connect");
            return;
        }
        connectedDevice = device;
        shouldReconnect = true;

        bleManager.setConnectionObserver(connectionObserver);
        stopScan();

        try {
            log("BLE Connect request to " + device.getAddress());
            setState(CONNECTING);
            bleManager.connect(device)
                    .useAutoConnect(false)
                    .retry(3, 100)
                    .enqueue();
        } catch (SecurityException e) {
            logError("Permission Error connecting: " + e);
            setState(INSUFFICIENT_PERMISSIONS);
        }
    }

    private void scheduleReconnect(String reason) {
        if (!shouldReconnect || connectedDevice == null) {
            log("Refusing to reconnect: " + reason);
            return;
        }

        reconnectAttempt++;
        final int delay = Math.min(300_000, reconnectAttempt * 5000);

        if (listener != null) {
            listener.onReconnecting(reconnectAttempt);
        }

        log("Reconnect attempt " + reconnectAttempt + " in " + delay + "ms: " + reason);

        if (bleManager != null) {
            bleManager.disconnect().enqueue();
        }

        cancelPendingReconnect();
        pendingReconnect = () -> {
            if (shouldReconnect) {
                startScan();
            }
        };
        handler.postDelayed(pendingReconnect, delay);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            handler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    private void scheduleScanCancel(long delay) {
        cancelPendingScanCancel();
        pendingScanCancel = this::stopScan;
        handler.postDelayed(pendingScanCancel, delay);
    }

    private void cancelPendingScanCancel() {
        if (pendingScanCancel != null) {
            handler.removeCallbacks(pendingScanCancel);
            pendingScanCancel = null;
        }
    }

    private final ConnectionObserver connectionObserver = new ConnectionObserver() {

        @Override
        public void onDeviceConnecting(@NonNull BluetoothDevice device) {
            log("onDeviceConnecting " + device.getAddress());
            setState(CONNECTING);
        }

        @Override
        public void onDeviceConnected(@NonNull BluetoothDevice device) {
            log("onDeviceConnected " + device.getAddress());
            cancelPendingReconnect();
            reconnectAttempt = 0;
            connectedDevice = device;
            data.put(DEVICE_ADDRESS, device.getAddress());
            setState(CONNECTED);
            if (listener != null) {
                listener.onConnected(device.getAddress());
            }
        }

        @Override
        public void onDeviceFailedToConnect(@NonNull BluetoothDevice device, int reason) {
            log("Failed to connect: " + device.getAddress() + " reason:" + reason);
            setState(CONNECT_FAILED);
            if (listener != null) {
                listener.onError("Failed to connect: " + reason);
            }
            scheduleReconnect("Failed to connect: " + reason);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onDeviceReady(@NonNull BluetoothDevice device) {
            log("Device ready: " + device.getAddress());
            setState(READY);
        }

        @Override
        public void onDeviceDisconnecting(@NonNull BluetoothDevice device) {
            log("Disconnecting... " + device.getAddress());
        }

        @Override
        public void onDeviceDisconnected(@NonNull BluetoothDevice device, int reason) {
            log("Disconnected: " + device.getAddress() + " reason:" + reason);
            setState(DISCONNECTED);
            if (listener != null) {
                listener.onDisconnected(device.getAddress(), reason);
            }
            scheduleReconnect("Device disconnected");
        }
    };

    private synchronized void setState(State state) {
        if (state == lastState) return;
        lastState = state;
        Listener l = listener;
        if (l != null) {
            l.onState(state);
            log("State changed to " + state);
        }
    }

    private void onC5Notification(byte[] bleData) {
        if (bleData == null || bleData.length < BlePacketParser.PACKET_SIZE) {
            logError("Invalid C5 notification: " + (bleData == null ? "null" : bleData.length + " bytes"));
            return;
        }

        try {
            BlePacketParser.ParsedReading reading = BlePacketParser.parse(bleData);

            log("C5 packet: seq=" + reading.getSequenceNumber()
                    + " time=" + reading.getTimestamp()
                    + " temp=" + reading.getTemperature()
                    + " battery=" + reading.getBattery()
                    + " error=" + reading.getDeviceErrorCode());

            data.put(SEQUENCE_NUMBER, String.valueOf(reading.getSequenceNumber()));
            data.put(BATTERY, String.valueOf(reading.getBattery()));
            data.put(TEMPERATURE, String.valueOf(reading.getTemperature()));

            if (reading.getDeviceErrorCode() != 0) {
                logError("Device error code: " + reading.getDeviceErrorCode());
            }

            if (calibrator == null) {
                logError("Calibrator not initialized. Call setSensorConfig() first.");
                return;
            }

            CalibrationResult result = calibrator.processReading(
                    reading.getSequenceNumber(),
                    reading.getTimestamp(),
                    reading.getAdcSamples(),
                    reading.getTemperature()
            );

            log("Calibration: glucose=" + result.getGlucoseMgdl()
                    + " trend=" + result.getTrendRateMgdlPerMin()
                    + " error=0x" + Integer.toHexString(result.getErrorCode())
                    + " stage=" + result.getStage()
                    + " valid=" + result.isValid());

            // Convert timestamp from Unix seconds to millis
            long timestampMs = reading.getTimestamp() * 1000L;

            data.put(TIMESTAMP, String.valueOf(timestampMs));
            data.put(ERROR_CODE, String.valueOf(result.getErrorCode()));
            data.put(STAGE, String.valueOf(result.getStage()));
            data.put(SENSOR_WARMED_UP, String.valueOf(calibrator.isWarmedUp()));

            if (result.isValid()) {
                data.put(MGDL, String.valueOf(result.getGlucoseMgdl()));
                if (result.isTrendAvailable()) {
                    data.put(TREND, String.valueOf(result.getTrendRateMgdlPerMin()));
                }
            }

            // Notify listener with data snapshot
            Listener l = listener;
            if (l != null) {
                l.onData(new ConcurrentHashMap<>(data));
            }

        } catch (Exception e) {
            logError("Error processing C5 notification: " + e.getMessage());
        }
    }

    private void log(String msg) {
        if (logger != null) {
            logger.d(TAG, msg);
        }
    }

    private void logError(String msg) {
        if (logger != null) {
            logger.e(TAG, msg);
        }
    }

    // ====================================================================
    // Internal BLE Manager (Nordic BLE library)
    // ====================================================================

    private class InternalManager extends BleManager {

        private BluetoothGattCharacteristic c5Characteristic;

        public InternalManager(@NonNull Context ctx) {
            super(ctx);
        }

        @Override
        public boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            // Try to find our service and C5 characteristic
            android.bluetooth.BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service != null) {
                c5Characteristic = service.getCharacteristic(C5_CHARACTERISTIC_UUID);
            }

            // TODO: If the standard CGM service UUID doesn't work, try iterating
            // all services/characteristics to find the 84-byte notification source.
            // This will need real device testing.

            return c5Characteristic != null;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        protected void initialize() {
            log("Configuring C5 notifications...");
            setState(CONFIGURING);

            if (c5Characteristic == null) {
                logError("No C5 characteristic found");
                return;
            }

            setNotificationCallback(c5Characteristic).with(c5DataCallback);

            enableNotifications(c5Characteristic)
                    .fail((device, status) -> {
                        logError("Failed to enable C5 notifications, status=" + status);
                        if (listener != null) {
                            listener.onError("Failed to enable notifications: " + status);
                        }
                    })
                    .done(device -> log("C5 notifications enabled"))
                    .enqueue();
        }

        @Override
        protected void onServicesInvalidated() {
            c5Characteristic = null;
        }

        private final DataReceivedCallback c5DataCallback = (device, data) -> {
            byte[] value = data.getValue();
            onC5Notification(value);
        };

        @Override
        public void log(final int priority, @NonNull final String message) {
            CareSensAirBle.this.log(message);
        }
    }
}

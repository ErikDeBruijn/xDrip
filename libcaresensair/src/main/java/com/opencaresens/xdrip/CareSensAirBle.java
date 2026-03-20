package com.opencaresens.xdrip;

import static com.opencaresens.xdrip.iface.DataKey.*;
import static com.opencaresens.xdrip.iface.State.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import com.opencaresens.air.BlePacketParser;
import com.opencaresens.air.CalibrationResult;
import com.opencaresens.air.CareSensCalibrator;
import com.opencaresens.air.SensorConfig;
import com.opencaresens.xdrip.config.Protocol;
import com.opencaresens.xdrip.config.Uuids;
import com.opencaresens.xdrip.iface.DataKey;
import com.opencaresens.xdrip.iface.Listener;
import com.opencaresens.xdrip.iface.State;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.observer.ConnectionObserver;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

/**
 * CareSens Air BLE orchestrator.
 *
 * Handles BLE scanning, connection management, the CareSens Air proprietary
 * protocol handshake (app info, sensor info, time sync, data request),
 * packet parsing, and calibration.
 *
 * Connection flow:
 * 1. Scan for devices with name prefix "CSAir " or service UUID
 * 2. Connect, request MTU 512, discover services
 * 3. Read device info (model, serial, firmware, software revision)
 * 4. Bond with device
 * 5. Enable notifications on C5 data and control characteristics
 * 6. Send app info request (0xC0,0x02) -> receive calibration params
 * 7. Request sensor info (0xC2,0x01) -> receive 3 parts
 * 8. Sync time (0xC3,0x02)
 * 9. Request data (0xC4,0x01 to C5 char) -> receive glucose notifications
 */
public class CareSensAirBle {

    public static final String TAG = CareSensAirBle.class.getSimpleName();

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

    /** Last record ID received, used when requesting new data */
    private volatile int lastRecordId = 0;

    /** Bluetooth pairing PIN (6-digit number from sensor label or NFC scan) */
    private volatile String pin;

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
     */
    public void setSensorConfig(SensorConfig config) {
        this.sensorConfig = config;
        this.calibrator = new CareSensCalibrator(config);
    }

    /**
     * Restore calibrator state from previously saved bytes.
     */
    public void restoreCalibrator(byte[] stateBytes, SensorConfig config) {
        this.sensorConfig = config;
        this.calibrator = CareSensCalibrator.restoreState(stateBytes, config);
    }

    /**
     * Save calibrator state for persistence across restarts.
     */
    @Nullable
    public byte[] saveCalibratorState() {
        if (calibrator != null) {
            return calibrator.saveState();
        }
        return null;
    }

    /**
     * Set the last record ID so we only request new data on reconnect.
     */
    public void setLastRecordId(int recordId) {
        this.lastRecordId = recordId;
    }

    /**
     * Set the Bluetooth pairing PIN.
     * This is the 6-digit number printed on the sensor (also readable via NFC scan).
     * Must be set before or during pairing; if the device requests a PIN and none
     * is set, {@link Listener#onPairingRequired()} will be called.
     */
    public void setPin(@Nullable String pin) {
        this.pin = pin;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start(String deviceAddress, Listener listener) {
        this.targetAddress = deviceAddress;
        this.listener = listener;
        initManager();

        this.shouldReconnect = true;
        if (targetAddress != null && !targetAddress.isEmpty()) {
            log("Connecting to known device: " + targetAddress);
        } else {
            log("Starting general scan for CareSens Air");
        }
        startScan();
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

        List<ScanFilter> filters = new ArrayList<>();
        // Filter by the data service UUID
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(Uuids.DATA_SERVICE.toString()))
                .build());

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

            // Filter by device name prefix "CSAir "
            if (name == null || !name.startsWith(Protocol.DEVICE_NAME_PREFIX)) {
                return;
            }

            log("Found CareSens Air device: " + name + " [" + device.getAddress() + "]");

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

    // ====================================================================
    // Protocol: C5 glucose data handling
    // ====================================================================

    private void onC5Notification(byte[] bleData) {
        if (bleData == null || bleData.length < 2) {
            logError("Invalid C5 notification: " + (bleData == null ? "null" : bleData.length + " bytes"));
            return;
        }

        // Check if this is a glucose data packet (0xC5, 0x01)
        if (bleData[0] == Protocol.CMD_GLUCOSE_DATA && bleData[1] == Protocol.RESP_GLUCOSE_DATA) {
            processGlucosePacket(bleData);
            return;
        }

        log("C5 non-glucose notification: cmd=0x" + String.format("%02X", bleData[0])
                + " sub=0x" + String.format("%02X", bleData[1])
                + " len=" + bleData.length);
    }

    private void processGlucosePacket(byte[] bleData) {
        if (bleData.length < Protocol.C5_PACKET_SIZE) {
            logError("Glucose packet too short: " + bleData.length + " bytes, expected "
                    + Protocol.C5_PACKET_SIZE);
            return;
        }

        try {
            BlePacketParser.ParsedReading reading = BlePacketParser.parse(bleData);

            log("C5 packet: seq=" + reading.getSequenceNumber()
                    + " time=" + reading.getTimestamp()
                    + " temp=" + reading.getTemperature()
                    + " battery=" + reading.getBattery()
                    + " error=" + reading.getDeviceErrorCode());

            // Track last record ID for future data requests
            lastRecordId = Math.max(lastRecordId, reading.getSequenceNumber());

            data.put(SEQUENCE_NUMBER, String.valueOf(reading.getSequenceNumber()));
            data.put(BATTERY, String.valueOf(reading.getBattery()));
            data.put(TEMPERATURE, String.valueOf(reading.getTemperature()));

            if (reading.getDeviceErrorCode() != 0) {
                logError("Device error code: " + reading.getDeviceErrorCode());
            }

            if (calibrator == null) {
                logError("Calibrator not initialized. Awaiting sensor info.");
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

            Listener l = listener;
            if (l != null) {
                l.onData(new ConcurrentHashMap<>(data));
            }

        } catch (Exception e) {
            logError("Error processing C5 notification: " + e.getMessage());
        }
    }

    // ====================================================================
    // Protocol: Control characteristic response handling
    // ====================================================================

    private void onControlNotification(byte[] bleData) {
        if (bleData == null || bleData.length < 2) {
            logError("Invalid control notification: "
                    + (bleData == null ? "null" : bleData.length + " bytes"));
            return;
        }

        byte cmd = bleData[0];
        byte sub = bleData[1];

        log("Control notification: cmd=0x" + String.format("%02X", cmd)
                + " sub=0x" + String.format("%02X", sub)
                + " len=" + bleData.length);

        if (cmd == Protocol.CMD_APP_INFO) {
            handleAppInfoResponse(sub, bleData);
        } else if (cmd == Protocol.CMD_SENSOR_INFO) {
            handleSensorInfoResponse(sub, bleData);
        } else if (cmd == Protocol.CMD_SENSOR_ENDED) {
            handleSensorEnded(sub, bleData);
        } else {
            log("Unhandled control cmd=0x" + String.format("%02X", cmd));
        }
    }

    /**
     * Handle 0xC0 responses: app info, calibration params, pairing.
     */
    private void handleAppInfoResponse(byte sub, byte[] bleData) {
        if (sub == Protocol.RESP_CALIBRATION_PARAMS) {
            parseCalibrationParams(bleData);
        } else if (sub == Protocol.RESP_APP_INFO) {
            log("App info response received");
        } else if (sub == Protocol.RESP_PAIRING) {
            handlePairingResponse(bleData);
        }
    }

    /**
     * Parse calibration parameters from 0xC0,0x02 response.
     * Bytes 2-5: eapp (float LE), bytes 6-9: vref (float LE), bytes 10-13: elapsed (u32 LE).
     */
    private void parseCalibrationParams(byte[] bleData) {
        if (bleData.length < 14) {
            logError("Calibration params too short: " + bleData.length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(bleData).order(ByteOrder.LITTLE_ENDIAN);

        float eapp = buf.getFloat(Protocol.CAL_EAPP_OFFSET);
        float vref = buf.getFloat(Protocol.CAL_VREF_OFFSET);
        long elapsed = Integer.toUnsignedLong(buf.getInt(Protocol.CAL_ELAPSED_OFFSET));

        log("Calibration params: eapp=" + eapp + " vref=" + vref + " elapsed=" + elapsed + "s");

        data.put(EAPP, String.valueOf(eapp));
        data.put(VREF, String.valueOf(vref));
    }

    // ====================================================================
    // Protocol: Sensor info state machine (3 BLE messages)
    // ====================================================================

    /** Accumulates sensor info parts as they arrive */
    private final byte[][] sensorInfoParts = new byte[Protocol.SENSOR_INFO_PARTS][];
    private volatile int sensorInfoPartsReceived = 0;

    /**
     * Handle 0xC2 sensor info responses (parts 1, 2, 3).
     */
    private void handleSensorInfoResponse(byte sub, byte[] bleData) {
        int partIndex;
        if (sub == Protocol.RESP_SENSOR_INFO_PART1) {
            partIndex = 0;
        } else if (sub == Protocol.RESP_SENSOR_INFO_PART2) {
            partIndex = 1;
        } else if (sub == Protocol.RESP_SENSOR_INFO_PART3) {
            partIndex = 2;
        } else {
            log("Unknown sensor info sub-command: 0x" + String.format("%02X", sub));
            return;
        }

        log("Sensor info part " + (partIndex + 1) + "/" + Protocol.SENSOR_INFO_PARTS
                + " received (" + bleData.length + " bytes)");

        sensorInfoParts[partIndex] = bleData;

        // Count how many parts we have
        int count = 0;
        for (byte[] part : sensorInfoParts) {
            if (part != null) count++;
        }
        sensorInfoPartsReceived = count;

        if (sensorInfoPartsReceived == Protocol.SENSOR_INFO_PARTS) {
            assembleSensorInfo();
        }
    }

    /**
     * Assemble SensorConfig from 3 sensor info messages and initialize calibrator.
     */
    private void assembleSensorInfo() {
        log("All sensor info parts received, assembling SensorConfig");

        try {
            // Concatenate raw bytes from all 3 parts (skip 2-byte header from each)
            int totalLen = 0;
            for (byte[] part : sensorInfoParts) {
                totalLen += part.length - 2; // skip cmd+sub header
            }

            byte[] combined = new byte[totalLen];
            int offset = 0;
            for (byte[] part : sensorInfoParts) {
                int payloadLen = part.length - 2;
                System.arraycopy(part, 2, combined, offset, payloadLen);
                offset += payloadLen;
            }

            // Parse the combined payload into SensorConfig
            // TODO: Implement SensorConfig.fromBlePayload() in the calibration library
            // For now, log the raw data for development
            log("Combined sensor info payload: " + combined.length + " bytes");

            if (sensorConfig != null) {
                // SensorConfig was set externally (e.g. from persistence)
                log("Using externally provided SensorConfig");
            } else {
                // TODO: Parse SensorConfig from combined payload
                // sensorConfig = SensorConfig.fromBlePayload(combined);
                logError("SensorConfig not set and BLE parsing not yet implemented");
            }

            if (sensorConfig != null && calibrator == null) {
                calibrator = new CareSensCalibrator(sensorConfig);
                log("Calibrator initialized from sensor info");
            }

            // After sensor info, proceed with time sync and data request
            InternalManager mgr = bleManager;
            if (mgr != null) {
                mgr.syncTimeAndRequestData();
            }

        } catch (Exception e) {
            logError("Error assembling sensor info: " + e.getMessage());
        } finally {
            // Reset for next time
            for (int i = 0; i < sensorInfoParts.length; i++) {
                sensorInfoParts[i] = null;
            }
            sensorInfoPartsReceived = 0;
        }
    }

    /**
     * Handle 0xCC sensor ended notification.
     */
    private void handleSensorEnded(byte sub, byte[] bleData) {
        log("Sensor ended notification received");
        setState(SENSOR_ENDED);
        Listener l = listener;
        if (l != null) {
            l.onError("Sensor ended");
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
    // Pairing: AES handshake and PIN handling
    // ====================================================================

    /**
     * Build the AES-encrypted pairing payload from the sensor serial number.
     *
     * Uses AES/CBC/PKCS5Padding with the hardcoded key and IV from the
     * CareSens Air protocol (Jugluco GPL source).
     *
     * @param serialNumber the sensor serial number (read from device info service)
     * @return encrypted bytes to write to the APP_PAIRING characteristic
     * @throws GeneralSecurityException if AES encryption fails
     */
    static byte[] buildPairingPayload(String serialNumber) throws GeneralSecurityException {
        byte[] keyBytes = Protocol.AES_KEY.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

        // IV: "badnonse" (8 bytes) padded to 16 bytes with zeros
        byte[] ivBytes = new byte[16];
        byte[] ivBase = Protocol.AES_IV_BASE.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(ivBase, 0, ivBytes, 0, ivBase.length);
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] plaintext = serialNumber.getBytes(StandardCharsets.UTF_8);
        return cipher.doFinal(plaintext);
    }

    /**
     * Handle Android's Bluetooth PIN request during bonding.
     * Sets the PIN on the device if one has been provided, otherwise notifies the listener.
     *
     * @param device the BluetoothDevice requesting a PIN
     */
    @SuppressLint("MissingPermission")
    private void handlePinRequest(BluetoothDevice device) {
        String currentPin = this.pin;
        if (currentPin == null || currentPin.isEmpty()) {
            log("PIN requested but none set, notifying listener");
            Listener l = listener;
            if (l != null) {
                l.onPairingRequired();
            }
            return;
        }

        log("Setting Bluetooth PIN for bonding");
        byte[] pinBytes = currentPin.getBytes(StandardCharsets.UTF_8);
        boolean success = device.setPin(pinBytes);
        if (!success) {
            logError("device.setPin() returned false");
        }
    }

    /**
     * Handle the 0xC0,0x03 pairing response from the device.
     */
    private void handlePairingResponse(byte[] bleData) {
        if (bleData.length < 3) {
            logError("Pairing response too short: " + bleData.length);
            Listener l = listener;
            if (l != null) {
                l.onPairingFailed("Pairing response too short");
            }
            return;
        }

        int result = bleData[2] & 0xFF;
        switch (result) {
            case Protocol.PAIRING_SUCCESS:
                log("Pairing verified successfully");
                setState(BONDED);
                break;
            case Protocol.PAIRING_DEVICE_MATCH_FAILED:
                logError("Pairing failed: device match failed");
                setState(BONDING_FAILED);
                if (listener != null) {
                    listener.onPairingFailed("Device match failed");
                }
                break;
            case Protocol.PAIRING_APPID_MATCH_FAILED:
                logError("Pairing failed: app ID match failed");
                setState(BONDING_FAILED);
                if (listener != null) {
                    listener.onPairingFailed("App ID match failed");
                }
                break;
            default:
                logError("Pairing failed: unknown result code " + result);
                setState(BONDING_FAILED);
                if (listener != null) {
                    listener.onPairingFailed("Unknown pairing result: " + result);
                }
                break;
        }
    }

    // ====================================================================
    // Internal BLE Manager (Nordic BLE library)
    // ====================================================================

    private class InternalManager extends BleManager {

        private BluetoothGattCharacteristic c5Characteristic;
        private BluetoothGattCharacteristic controlCharacteristic;
        private BluetoothGattCharacteristic appPairingCharacteristic;

        // Device info characteristics
        private BluetoothGattCharacteristic modelNumberChar;
        private BluetoothGattCharacteristic serialNumberChar;
        private BluetoothGattCharacteristic firmwareRevisionChar;
        private BluetoothGattCharacteristic softwareRevisionChar;

        public InternalManager(@NonNull Context ctx) {
            super(ctx);
        }

        @Override
        public int getMinLogPriority() {
            return android.util.Log.DEBUG;
        }

        @Override
        protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
            // Data/control service (required)
            BluetoothGattService dataService = gatt.getService(Uuids.DATA_SERVICE);
            if (dataService == null) {
                logError("Data service not found");
                return false;
            }

            c5Characteristic = dataService.getCharacteristic(Uuids.C5_DATA);
            controlCharacteristic = dataService.getCharacteristic(Uuids.CONTROL);

            if (c5Characteristic == null) {
                logError("C5 data characteristic not found");
                return false;
            }
            if (controlCharacteristic == null) {
                logError("Control characteristic not found");
                return false;
            }

            // App pairing characteristic (in additional service)
            BluetoothGattService additionalService = gatt.getService(Uuids.ADDITIONAL_SERVICE);
            if (additionalService != null) {
                appPairingCharacteristic = additionalService.getCharacteristic(Uuids.APP_PAIRING);
            }
            if (appPairingCharacteristic == null) {
                log("App pairing characteristic not found (non-critical if already bonded)");
            }

            // Device info service (optional but expected)
            BluetoothGattService infoService = gatt.getService(Uuids.DEVICE_INFO_SERVICE);
            if (infoService != null) {
                modelNumberChar = infoService.getCharacteristic(Uuids.MODEL_NUMBER);
                serialNumberChar = infoService.getCharacteristic(Uuids.SERIAL_NUMBER);
                firmwareRevisionChar = infoService.getCharacteristic(Uuids.FIRMWARE_REVISION);
                softwareRevisionChar = infoService.getCharacteristic(Uuids.SOFTWARE_REVISION);
            } else {
                log("Device info service not found (non-critical)");
            }

            return true;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        protected void initialize() {
            log("Initializing CareSens Air protocol...");
            setState(CONFIGURING);

            // Step 1: Request MTU 512
            requestMtu(Protocol.REQUESTED_MTU)
                    .done(device -> log("MTU negotiated"))
                    .fail((device, status) -> log("MTU request failed, status=" + status))
                    .enqueue();

            // Step 2: Read device info characteristics (serial number needed for pairing)
            readDeviceInfo();

            // Step 3: Perform AES pairing handshake if not already bonded
            performPairingHandshake();

            // Step 4: Bond with device (PIN-based)
            ensureBond();

            // Step 5: Enable notifications on C5 data characteristic
            if (c5Characteristic != null) {
                setNotificationCallback(c5Characteristic).with(c5DataCallback);
                enableNotifications(c5Characteristic)
                        .fail((device, status) -> {
                            logError("Failed to enable C5 notifications, status=" + status);
                            if (listener != null) {
                                listener.onError("Failed to enable C5 notifications: " + status);
                            }
                        })
                        .done(device -> log("C5 notifications enabled"))
                        .enqueue();
            }

            // Step 6: Enable notifications on control characteristic
            if (controlCharacteristic != null) {
                setNotificationCallback(controlCharacteristic).with(controlDataCallback);
                enableNotifications(controlCharacteristic)
                        .fail((device, status) -> {
                            logError("Failed to enable control notifications, status=" + status);
                            if (listener != null) {
                                listener.onError("Failed to enable control notifications: " + status);
                            }
                        })
                        .done(device -> {
                            log("Control notifications enabled");
                            // Step 7: Send app info with AppID to complete pairing verification
                            sendAppInfoRequest();
                        })
                        .enqueue();
            }
        }

        @Override
        protected void onServicesInvalidated() {
            c5Characteristic = null;
            controlCharacteristic = null;
            appPairingCharacteristic = null;
            modelNumberChar = null;
            serialNumberChar = null;
            firmwareRevisionChar = null;
            softwareRevisionChar = null;
        }

        // -- Device info reading --

        private void readDeviceInfo() {
            readCharToData(modelNumberChar, MODEL_NUMBER);
            readCharToData(serialNumberChar, SERIAL_NUMBER);
            readCharToData(firmwareRevisionChar, FIRMWARE_VERSION);
            readCharToData(softwareRevisionChar, DEVICE_NAME);
        }

        private void readCharToData(@Nullable BluetoothGattCharacteristic ch, DataKey key) {
            if (ch == null) return;
            readCharacteristic(ch)
                    .with((device, rdata) -> {
                        String value = rdata.getStringValue(0);
                        log("Read " + key + ": " + value);
                        data.put(key, value);
                    })
                    .fail((device, status) -> log("Failed to read " + key + ", status=" + status))
                    .enqueue();
        }

        // -- Pairing and bonding --

        /**
         * Perform the AES pairing handshake by writing the encrypted serial
         * number to the APP_PAIRING characteristic.
         */
        private void performPairingHandshake() {
            BluetoothDevice device = connectedDevice;
            if (device == null) return;

            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                log("Already bonded, skipping AES pairing handshake");
                return;
            }

            if (appPairingCharacteristic == null) {
                log("App pairing characteristic not available, skipping handshake");
                return;
            }

            String serial = data.get(SERIAL_NUMBER);
            if (serial == null || serial.isEmpty()) {
                logError("Serial number not yet read, cannot perform pairing handshake");
                return;
            }

            try {
                setState(PAIRING);
                byte[] payload = buildPairingPayload(serial);
                log("Writing AES pairing payload (" + payload.length + " bytes)");

                writeCharacteristic(appPairingCharacteristic, payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        .done(d -> log("Pairing payload written successfully"))
                        .fail((d, status) -> {
                            logError("Failed to write pairing payload, status=" + status);
                            if (listener != null) {
                                listener.onPairingFailed("Failed to write pairing payload: " + status);
                            }
                        })
                        .enqueue();
            } catch (GeneralSecurityException e) {
                logError("AES encryption failed: " + e.getMessage());
                if (listener != null) {
                    listener.onPairingFailed("AES encryption failed: " + e.getMessage());
                }
            }
        }

        @SuppressLint("MissingPermission")
        private void ensureBond() {
            BluetoothDevice device = connectedDevice;
            if (device == null) return;

            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                log("Device already bonded");
                return;
            }

            log("Requesting bond with device");
            setState(BONDING);
            boolean result = device.createBond();
            if (!result) {
                logError("createBond() returned false");
            }
            // When Android requests a PIN, handlePinRequest() will be called
            // via the BroadcastReceiver for ACTION_PAIRING_REQUEST.
        }

        // -- Protocol commands --

        /**
         * Send app info with AppID (0xC0, 0x02, "csair") to control characteristic.
         * The device verifies the AppID and responds with calibration params
         * and a pairing response (0xC0, 0x03).
         */
        private void sendAppInfoRequest() {
            if (controlCharacteristic == null) return;

            byte[] appIdBytes = Protocol.APP_ID.getBytes(StandardCharsets.UTF_8);
            byte[] cmd = new byte[2 + appIdBytes.length];
            cmd[0] = Protocol.CMD_APP_INFO;
            cmd[1] = Protocol.SUB_SET_APP_INFO;
            System.arraycopy(appIdBytes, 0, cmd, 2, appIdBytes.length);

            log("Sending app info with AppID (0xC0, 0x02, \"" + Protocol.APP_ID + "\")");
            writeCharacteristic(controlCharacteristic, cmd,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .done(device -> {
                        log("App info request sent, requesting sensor info next");
                        requestSensorInfo();
                    })
                    .fail((device, status) ->
                            logError("Failed to send app info request, status=" + status))
                    .enqueue();
        }

        /**
         * Request sensor info (0xC2, 0x01) to control characteristic.
         * The device responds with 3 messages containing full SensorInfo.
         */
        private void requestSensorInfo() {
            if (controlCharacteristic == null) return;
            log("Requesting sensor info (0xC2, 0x01)");
            byte[] cmd = new byte[]{Protocol.CMD_SENSOR_INFO, Protocol.SUB_REQUEST_SENSOR_INFO};
            writeCharacteristic(controlCharacteristic, cmd,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .done(device -> log("Sensor info request sent"))
                    .fail((device, status) ->
                            logError("Failed to request sensor info, status=" + status))
                    .enqueue();
        }

        /**
         * Sync time with device (0xC3, 0x02 + timestamp bytes).
         * Called after sensor info is assembled.
         */
        private void syncTime() {
            if (controlCharacteristic == null) return;
            long nowSec = System.currentTimeMillis() / 1000L;
            log("Syncing time (0xC3, 0x02) epoch=" + nowSec);

            ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(Protocol.CMD_TIME_SYNC);
            buf.put(Protocol.SUB_TIME_SYNC);
            buf.putInt((int) nowSec);

            writeCharacteristic(controlCharacteristic, buf.array(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .done(device -> log("Time sync sent"))
                    .fail((device, status) ->
                            logError("Failed to sync time, status=" + status))
                    .enqueue();
        }

        /**
         * Request glucose data (0xC4, 0x01 + last record ID) written to C5 characteristic.
         */
        private void requestData() {
            if (c5Characteristic == null) return;
            log("Requesting data (0xC4, 0x01) lastRecordId=" + lastRecordId);

            ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(Protocol.CMD_DATA_REQUEST);
            buf.put(Protocol.SUB_REQUEST_DATA);
            buf.putInt(lastRecordId);

            writeCharacteristic(c5Characteristic, buf.array(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .done(device -> log("Data request sent"))
                    .fail((device, status) ->
                            logError("Failed to request data, status=" + status))
                    .enqueue();
        }

        /**
         * Called after sensor info is fully assembled.
         * Syncs time then requests data.
         */
        void syncTimeAndRequestData() {
            syncTime();
            // Small delay to let time sync complete before data request
            handler.postDelayed(this::requestData, 500);
        }

        // -- Notification callbacks --

        private final DataReceivedCallback c5DataCallback = (device, rdata) -> {
            byte[] value = rdata.getValue();
            onC5Notification(value);
        };

        private final DataReceivedCallback controlDataCallback = (device, rdata) -> {
            byte[] value = rdata.getValue();
            onControlNotification(value);
        };

        @Override
        public void log(final int priority, @NonNull final String message) {
            CareSensAirBle.this.log(message);
        }
    }
}

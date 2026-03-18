package com.eveningoutpost.dexdrip.cgm.caresensair;

import static com.eveningoutpost.dexdrip.models.JoH.msSince;
import static com.eveningoutpost.dexdrip.models.JoH.tsl;
import static com.opencaresens.xdrip.iface.DataKey.*;
import static com.opencaresens.xdrip.iface.State.*;

import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.SpannableString;

import androidx.annotation.Nullable;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.utilitymodels.StatusItem;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.utils.framework.ForegroundService;
import com.eveningoutpost.dexdrip.utils.framework.WakeLockTrampoline;
import com.eveningoutpost.dexdrip.xdrip;
import com.opencaresens.xdrip.CareSensAirBle;
import com.opencaresens.xdrip.iface.DataKey;
import com.opencaresens.xdrip.iface.Listener;
import com.opencaresens.xdrip.iface.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CareSens Air CGM collector service for xDrip+.
 *
 * Follows the GluProService pattern. Manages the BLE connection lifecycle,
 * receives calibrated glucose readings from the CareSens Air library, and
 * inserts them into xDrip+'s BgReading database.
 */
public class CareSensAirService extends ForegroundService {

    public static final String TAG = CareSensAirService.class.getSimpleName();

    private static final String PREFS_LAST_CARESENS_ADDRESS = "LAST_CARESENS_AIR_ADDRESS";

    private static final ConcurrentHashMap<DataKey, String> lastData = new ConcurrentHashMap<>();

    private volatile long lastTimestamp = -1;
    private static volatile long lastOnData = -1;
    long wakeup_time = -1;

    private static volatile boolean connected = false;
    static volatile State lastState = UNKNOWN;
    static volatile int lastReconnectAttempt = -1;

    CareSensAirBle client;

    private final Listener listener = new Listener() {
        @Override
        public void onConnected(String deviceAddress) {
            connected = true;
            lastReconnectAttempt = 0;
        }

        @Override
        public void onDisconnected(String deviceAddress, int reason) {
            connected = false;
        }

        @Override
        public void onReconnecting(int attempt) {
            lastReconnectAttempt = attempt;
        }

        @Override
        public synchronized void onData(final Map<DataKey, String> incomingData) {
            lastOnData = tsl();
            lastData.clear();
            lastData.putAll(incomingData);

            if (incomingData.containsKey(MGDL) && incomingData.containsKey(TIMESTAMP)) {
                Sensor.createDefaultIfMissing();
                long timestamp = Long.parseLong(incomingData.get(TIMESTAMP));
                if (timestamp != lastTimestamp) {
                    lastTimestamp = timestamp;
                    try {
                        double mgdl = Double.parseDouble(incomingData.get(MGDL));
                        BgReading.bgReadingInsertFromGluPro(mgdl, timestamp, "CareSensAir");
                    } catch (Exception e) {
                        UserError.Log.e(TAG, "Error inserting glucose reading: " + e.getMessage());
                    }
                } else {
                    UserError.Log.d(TAG, "Duplicate timestamp, skipping");
                }
            }

            if (incomingData.containsKey(DEVICE_ADDRESS)) {
                String mac = incomingData.get(DEVICE_ADDRESS);
                String oldMac = Pref.getString(PREFS_LAST_CARESENS_ADDRESS, "");
                if (!oldMac.equals(mac)) {
                    Pref.setString(PREFS_LAST_CARESENS_ADDRESS, mac);
                    UserError.Log.d(TAG, "Device address changed: " + mac);
                }
            }

            scheduleWakeUp();
        }

        @Override
        public synchronized void onState(State state) {
            lastState = state;
        }

        @Override
        public void onError(String message) {
            UserError.Log.e(TAG, "Error: " + message);
        }
    };


    @Override
    public void onCreate() {
        connected = false;
        lastReconnectAttempt = 0;

        client = new CareSensAirBle(xdrip.getAppContext());

        client.setLogger(new CareSensAirBle.ILog() {
            @Override
            public void d(String tag, String msg) {
                UserError.Log.d(tag, msg);
            }

            @Override
            public void e(String tag, String msg) {
                UserError.Log.e(tag, msg);
            }
        });

        // TODO: Restore calibrator state from PersistentStore if available.
        // byte[] savedState = CareSensAir.getSavedCalibratorState();
        // SensorConfig config = CareSensAir.getSavedSensorConfig();
        // if (savedState != null && config != null) {
        //     client.restoreCalibrator(savedState, config);
        // }

        // TODO: SensorConfig needs to be parsed from BLE advertisement
        // or restored from persistent storage. For now the calibrator won't
        // be initialized until setSensorConfig() is called on the client.

        Sensor.createDefaultIfMissing();
        startInitialConnect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final PowerManager.WakeLock wl = JoH.getWakeLock(TAG + "-osc", 60_000);
        try {
            UserError.Log.d(TAG, "Service wakeup");

            if (!shouldServiceRun()) {
                UserError.Log.d(TAG, "Stopping service - not the active collector");
                stopSelf();
                return START_NOT_STICKY;
            }

            startIfDisconnected();
            scheduleWakeUp();
        } finally {
            JoH.releaseWakeLock(wl);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        UserError.Log.d(TAG, "Destroying service");
        if (client != null) {
            // TODO: Save calibrator state before stopping
            // byte[] state = client.saveCalibratorState();
            // if (state != null) CareSensAir.saveCalibratorState(state);
            client.stop();
        }
        super.onDestroy();
    }

    private void startIfDisconnected() {
        if (!connected || State.isCriticalError(lastState)) {
            startInitialConnect();
            return;
        }
        switch (lastState) {
            case DISCONNECTED:
            case SCANNING_ERROR:
            case CONNECT_FAILED:
                startInitialConnect();
                break;
        }
    }

    private void startInitialConnect() {
        String lastAddress = Pref.getString(PREFS_LAST_CARESENS_ADDRESS, null);
        if (lastAddress != null && !lastAddress.isEmpty()) {
            UserError.Log.d(TAG, "Connecting to last known address: " + lastAddress);
            client.start(lastAddress, listener);
        } else {
            UserError.Log.d(TAG, "No known address, starting open scan");
            client.start(null, listener);
        }
    }

    void scheduleWakeUp() {
        long next = tsl() + Constants.MINUTE_IN_MS * 15;
        if (Math.abs(next - wakeup_time) > Constants.SECOND_IN_MS * 10) {
            wakeup_time = next;
            JoH.wakeUpIntent(xdrip.getAppContext(), JoH.msTill(next),
                    WakeLockTrampoline.getPendingIntent(CareSensAirService.class,
                            Constants.CARESENS_AIR_SERVICE_FAILOVER_ID));
        }
    }

    private static boolean shouldServiceRun() {
        return DexCollectionType.getDexCollectionType() == DexCollectionType.CareSensAir;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * MegaStatus - provides status information for the xDrip+ status page.
     */
    public static List<StatusItem> megaStatus() {
        List<StatusItem> si = new ArrayList<>();
        si.add(new StatusItem("CareSens Air State",
                lastState != null ? lastState.toString() : "Unknown",
                colorForState(lastState)));

        if (!connected && lastReconnectAttempt > 2) {
            si.add(new StatusItem("Reconnect Attempt",
                    "" + lastReconnectAttempt,
                    lastReconnectAttempt > 10 ? StatusItem.Highlight.BAD : StatusItem.Highlight.NOTICE));
        }

        String errorCode = lastData.getOrDefault(ERROR_CODE, "0");
        if (!"0".equals(errorCode)) {
            si.add(new StatusItem("Error Code", errorCode, StatusItem.Highlight.BAD));
        }

        if (lastData.containsKey(BATTERY)) {
            si.add(new StatusItem("Battery", lastData.get(BATTERY)));
        }

        if (lastData.containsKey(TEMPERATURE)) {
            si.add(new StatusItem("Temperature", lastData.get(TEMPERATURE) + " C"));
        }

        if (lastData.containsKey(SEQUENCE_NUMBER)) {
            si.add(new StatusItem("Sequence", lastData.get(SEQUENCE_NUMBER)));
        }

        String warmedUp = lastData.getOrDefault(SENSOR_WARMED_UP, "false");
        if ("false".equals(warmedUp)) {
            si.add(new StatusItem("Warmup", "In progress", StatusItem.Highlight.NOTICE));
        }

        return si;
    }

    // accessed via reflection
    public static boolean isCollecting() {
        return lastState == READY && msSince(lastOnData) < Constants.MINUTE_IN_MS * 12;
    }

    // accessed via reflection
    public static SpannableString nanoStatus() {
        if (lastState != null && (State.isError(lastState) || State.isCriticalError(lastState))) {
            return new SpannableString(lastState.toString());
        }
        return null;
    }

    private static StatusItem.Highlight colorForState(State state) {
        if (state == null) return StatusItem.Highlight.NORMAL;
        if (State.isGood(state)) return StatusItem.Highlight.GOOD;
        if (State.isWarning(state)) return StatusItem.Highlight.NOTICE;
        if (State.isError(state)) return StatusItem.Highlight.BAD;
        if (State.isCriticalError(state)) return StatusItem.Highlight.CRITICAL;
        return StatusItem.Highlight.NORMAL;
    }
}

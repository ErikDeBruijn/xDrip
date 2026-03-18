package com.eveningoutpost.dexdrip.cgm.caresensair;

import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.PersistentStore;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;

/**
 * CareSens Air static helper class.
 *
 * Follows the GluPro.java pattern. Provides static methods for
 * status queries and calibrator state persistence.
 */
public class CareSensAir {

    private static final String TAG = CareSensAir.class.getSimpleName();
    private static final String CARESENS_CALIBRATOR_STATE = "CARESENS-AIR-CALIBRATOR-STATE";
    private static final String CARESENS_SENSOR_CONFIG = "CARESENS-AIR-SENSOR-CONFIG";

    public static boolean acceptCommands() {
        return DexCollectionType.getDexCollectionType() == DexCollectionType.CareSensAir;
    }

    /**
     * Save calibrator state bytes for persistence.
     */
    public static void saveCalibratorState(byte[] state) {
        if (state != null) {
            PersistentStore.setBytes(CARESENS_CALIBRATOR_STATE, state);
            UserError.Log.d(TAG, "Saved calibrator state: " + state.length + " bytes");
        }
    }

    /**
     * Retrieve saved calibrator state bytes.
     *
     * @return saved state or null
     */
    public static byte[] getSavedCalibratorState() {
        byte[] state = PersistentStore.getBytes(CARESENS_CALIBRATOR_STATE);
        if (state != null && state.length > 0) {
            return state;
        }
        return null;
    }

    /**
     * Clear saved calibrator state (e.g., on sensor change).
     */
    public static void clearCalibratorState() {
        PersistentStore.setBytes(CARESENS_CALIBRATOR_STATE, null);
        UserError.Log.d(TAG, "Cleared calibrator state");
    }
}

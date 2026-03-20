package com.opencaresens.xdrip.config;

import java.util.UUID;

/**
 * CareSens Air BLE UUID constants.
 *
 * All UUIDs are proprietary to the CareSens Air CGM protocol.
 */
public final class Uuids {

    private Uuids() {
    }

    // -- Services --

    /** Device information service (model, serial, firmware, etc.) */
    public static final UUID DEVICE_INFO_SERVICE =
            UUID.fromString("c4de7bda-5a9d-11e9-8647-d663bd873d93");

    /** Data/control service — also used for scan filtering */
    public static final UUID DATA_SERVICE =
            UUID.fromString("c4de9a20-5a9d-11e9-8647-d663bd873d93");

    /** Additional service */
    public static final UUID ADDITIONAL_SERVICE =
            UUID.fromString("c4de9dc2-5a9d-11e9-8647-d663bd873d93");

    // -- Device info characteristics --

    public static final UUID MODEL_NUMBER =
            UUID.fromString("c4de83c8-5a9d-11e9-8647-d663bd873d93");

    public static final UUID SERIAL_NUMBER =
            UUID.fromString("c4de8544-5a9d-11e9-8647-d663bd873d93");

    public static final UUID FIRMWARE_REVISION =
            UUID.fromString("c4de86a2-5a9d-11e9-8647-d663bd873d93");

    public static final UUID HARDWARE_REVISION =
            UUID.fromString("c4de87e2-5a9d-11e9-8647-d663bd873d93");

    public static final UUID SOFTWARE_REVISION =
            UUID.fromString("c4de89ae-5a9d-11e9-8647-d663bd873d93");

    // -- Data/control characteristics --

    /** C5 data characteristic — receives 84-byte glucose notifications */
    public static final UUID C5_DATA =
            UUID.fromString("c4de9b74-5a9d-11e9-8647-d663bd873d93");

    /** Control/command characteristic — for sending commands and receiving responses */
    public static final UUID CONTROL =
            UUID.fromString("c4de9ee4-5a9d-11e9-8647-d663bd873d93");

    /** App pairing characteristic */
    public static final UUID APP_PAIRING =
            UUID.fromString("c4dec61c-5a9d-11e9-8647-d663bd873d93");
}

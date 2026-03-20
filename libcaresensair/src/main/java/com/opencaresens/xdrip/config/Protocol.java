package com.opencaresens.xdrip.config;

/**
 * CareSens Air BLE protocol constants.
 *
 * Command bytes for the proprietary CareSens Air CGM protocol.
 * Commands are sent as byte arrays where the first byte identifies the
 * command group and the second byte the sub-command.
 */
public final class Protocol {

    private Protocol() {
    }

    // -- Command group bytes (first byte) --

    /** App info / calibration params group */
    public static final byte CMD_APP_INFO = (byte) 0xC0;

    /** Sensor info group */
    public static final byte CMD_SENSOR_INFO = (byte) 0xC2;

    /** Time sync group */
    public static final byte CMD_TIME_SYNC = (byte) 0xC3;

    /** Data request group (written to C5 characteristic) */
    public static final byte CMD_DATA_REQUEST = (byte) 0xC4;

    /** Glucose data notification group */
    public static final byte CMD_GLUCOSE_DATA = (byte) 0xC5;

    /** Sensor ended notification group */
    public static final byte CMD_SENSOR_ENDED = (byte) 0xCC;

    // -- Sub-command bytes (second byte) --

    // Phone -> Device commands:

    /** Set app info: write {0xC0, 0x02} to control characteristic */
    public static final byte SUB_SET_APP_INFO = (byte) 0x02;

    /** Request sensor info: write {0xC2, 0x01} to control characteristic */
    public static final byte SUB_REQUEST_SENSOR_INFO = (byte) 0x01;

    /** Time sync: write {0xC3, 0x02, ...timestamp...} to control characteristic */
    public static final byte SUB_TIME_SYNC = (byte) 0x02;

    /** Request data: write {0xC4, 0x01, ...last_record_id...} to C5 characteristic */
    public static final byte SUB_REQUEST_DATA = (byte) 0x01;

    // Device -> Phone response sub-commands:

    /** App info response: 0xC0, 0x01 */
    public static final byte RESP_APP_INFO = (byte) 0x01;

    /** Calibration params response: 0xC0, 0x02 — contains eapp, vref, elapsed */
    public static final byte RESP_CALIBRATION_PARAMS = (byte) 0x02;

    /** Pairing response: 0xC0, 0x03 */
    public static final byte RESP_PAIRING = (byte) 0x03;

    /** Sensor info part 1: 0xC2, 0x01 */
    public static final byte RESP_SENSOR_INFO_PART1 = (byte) 0x01;

    /** Sensor info part 2: 0xC2, 0x02 */
    public static final byte RESP_SENSOR_INFO_PART2 = (byte) 0x02;

    /** Sensor info part 3: 0xC2, 0x03 */
    public static final byte RESP_SENSOR_INFO_PART3 = (byte) 0x03;

    /** Glucose data: 0xC5, 0x01 — 84-byte packet */
    public static final byte RESP_GLUCOSE_DATA = (byte) 0x01;

    /** Sensor ended: 0xCC, 0x02 */
    public static final byte RESP_SENSOR_ENDED = (byte) 0x02;

    // -- Sensor info message count --

    /** Number of BLE messages that make up a complete SensorInfo */
    public static final int SENSOR_INFO_PARTS = 3;

    // -- Calibration param offsets (within 0xC0,0x02 response) --

    /** Offset of eapp float (little-endian) in calibration response */
    public static final int CAL_EAPP_OFFSET = 2;

    /** Offset of vref float (little-endian) in calibration response */
    public static final int CAL_VREF_OFFSET = 6;

    /** Offset of elapsed seconds u32 (little-endian) in calibration response */
    public static final int CAL_ELAPSED_OFFSET = 10;

    // -- Pairing / bonding --

    /** AES key for app-pairing handshake (from Jugluco GPL source) */
    public static final String AES_KEY = "tq1Tg265o4UFD8tfPvNqUCiYyCxkhdZV";

    /** AES IV base — "badnonse" padded to 16 bytes with zeros for AES/CBC */
    public static final String AES_IV_BASE = "badnonse";

    /** App ID sent during pairing verification */
    public static final String APP_ID = "csair";

    /** Pairing response: success */
    public static final int PAIRING_SUCCESS = 0;

    /** Pairing response: device match failed */
    public static final int PAIRING_DEVICE_MATCH_FAILED = 1;

    /** Pairing response: app ID match failed */
    public static final int PAIRING_APPID_MATCH_FAILED = 2;

    // -- Misc --

    /** Device name prefix used for scan filtering */
    public static final String DEVICE_NAME_PREFIX = "CSAir ";

    /** MTU size to request after connection */
    public static final int REQUESTED_MTU = 512;

    /** C5 glucose notification packet size */
    public static final int C5_PACKET_SIZE = 84;
}

package com.opencaresens.xdrip.iface;

import java.util.Map;

/**
 * Callback interface for CareSens Air BLE events.
 */
public interface Listener {
    void onConnected(String deviceAddress);

    void onDisconnected(String deviceAddress, int reason);

    void onReconnecting(int attempt);

    void onData(Map<DataKey, String> data);

    void onState(State state);

    void onError(String message);

    /**
     * Called when the device requires a PIN for Bluetooth bonding.
     * The PIN is a 6-digit number printed on the sensor (also readable via NFC).
     */
    void onPairingRequired();

    /**
     * Called when the pairing handshake fails.
     * @param reason human-readable failure reason
     */
    void onPairingFailed(String reason);
}

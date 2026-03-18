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
}

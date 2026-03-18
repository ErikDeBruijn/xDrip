package com.opencaresens.xdrip.iface;

/**
 * CareSens Air collector states.
 */
public enum State {
    INIT,
    SCANNING,
    SCANNING_ERROR,
    INSUFFICIENT_PERMISSIONS,
    BLUETOOTH_DISABLED,
    SCAN_STOPPED,
    CONNECTING,
    CONNECTED,
    CONFIGURING,
    READY,
    CONNECT_FAILED,
    DISCONNECTED,
    SHUTDOWN,
    UNKNOWN;

    public static State getState(String stateName) {
        if (stateName == null) {
            return UNKNOWN;
        }
        try {
            return State.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public static boolean isCriticalError(State state) {
        switch (state) {
            case BLUETOOTH_DISABLED:
            case INSUFFICIENT_PERMISSIONS:
                return true;
            default:
                return false;
        }
    }

    public static boolean isError(State state) {
        switch (state) {
            case CONNECT_FAILED:
            case SCANNING_ERROR:
                return true;
            default:
                return false;
        }
    }

    public static boolean isWarning(State state) {
        switch (state) {
            case CONNECTING:
            case CONNECTED:
            case SCANNING:
                return true;
            default:
                return false;
        }
    }

    public static boolean isGood(State state) {
        switch (state) {
            case READY:
                return true;
            default:
                return false;
        }
    }
}

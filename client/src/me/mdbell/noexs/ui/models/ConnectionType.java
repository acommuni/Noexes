package me.mdbell.noexs.ui.models;

public enum ConnectionType {

    USB(1), NETWORK(10);

    private int maxConnectionFailureCount;

    ConnectionType(int maxConnectionFailureCount) {
        this.maxConnectionFailureCount = maxConnectionFailureCount;
    }

    public int getMaxConnectionFailureCountt() {
        return maxConnectionFailureCount;
    }
}

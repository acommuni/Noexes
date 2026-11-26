package me.mdbell.noexs.ui.models;

public enum EConnectionType {

    USB(1), NETWORK(10);

    private int maxConnectionFailureCount;

    EConnectionType(int maxConnectionFailureCount) {
        this.maxConnectionFailureCount = maxConnectionFailureCount;
    }

    public int getMaxConnectionFailureCountt() {
        return maxConnectionFailureCount;
    }
}

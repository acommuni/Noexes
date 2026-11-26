package me.mdbell.noexs.core;

public enum EDebuggerStatus {
    STOPPED(0, "Shutting down"), RUNNING(1, "Running"), PAUSED(2, "Paused");

    int id;
    String str;

    EDebuggerStatus(int id, String str) {
        this.id = id;
        this.str = str;
    }

    public int getId() {
        return id;
    }

    public String getStatus() {
        return str;
    }

    public static EDebuggerStatus forId(int status) {
        for (EDebuggerStatus s : values()) {
            if (s.id == status) {
                return s;
            }
        }
        return null;
    }
}

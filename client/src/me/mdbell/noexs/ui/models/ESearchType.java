package me.mdbell.noexs.ui.models;

public enum ESearchType {
    UNKNOWN("Unknown"), PREVIOUS("Previous"), KNOWN("Known"), DIFFERENT("Different");

    String readable;

    ESearchType(String str) {
        this.readable = str;
    }

    public String toString() {
        return readable;
    }

    public boolean requiresPrevious() {
        return this == PREVIOUS || this == DIFFERENT;
    }
}

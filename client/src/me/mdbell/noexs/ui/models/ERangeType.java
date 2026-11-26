package me.mdbell.noexs.ui.models;

public enum ERangeType {
    ALL("All (R/W)"), RANGE("Range"), MAIN_AND_HEAP("Main and Heap Range"), HEAP("Heap"), TLS("Thread Local Storage");

    String str;

    ERangeType(String str) {
        this.str = str;
    }

    public String toString() {
        return str;
    }
}

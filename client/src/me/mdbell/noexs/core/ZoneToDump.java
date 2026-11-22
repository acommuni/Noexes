package me.mdbell.noexs.core;

/**
 * Zone mémoire à lire via readmemMulti (commande 0x13).
 */
public record ZoneToDump(long address, int dataSize) {

    public ZoneToDump {
        if (dataSize <= 0 || dataSize > 2048 * 32) {
            throw new IllegalArgumentException("dataSize invalide: " + dataSize);
        }
    }
}

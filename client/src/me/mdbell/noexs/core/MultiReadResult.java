package me.mdbell.noexs.core;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Résultat d'un readmemMulti (0x13) : toutes les zones concaténées dans un seul buffer,
 * avec pour chaque zone son offset dans ce buffer.
 */
public record MultiReadResult(List<ZoneRead> zones, int totalSize, byte[] buffer) {

    /** Une zone lue : adresse demandée, offset dans le buffer résultat, taille, données. */
    public record ZoneRead(long address, int offset, int size, ByteBuffer data) {
    }

    /** Retourne le buffer de la zone à l'index i. */
    public ByteBuffer getZone(int i) {
        return zones.get(i).data();
    }
}

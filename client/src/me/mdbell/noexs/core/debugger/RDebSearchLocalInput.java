package me.mdbell.noexs.core.debugger;

/**
 * Paramètres de la recherche locale côté serveur (commande 0x16).
 * searchSize : 0=8bit, 1=16bit, 2=32bit, 3=64bit
 * searchType : 0=egal, 1=range (value1..value2)
 */
public record RDebSearchLocalInput(long start, long end, long value1, long value2,
                                    int searchSize, int searchType) {
}

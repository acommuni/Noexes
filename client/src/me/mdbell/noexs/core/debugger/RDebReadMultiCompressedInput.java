package me.mdbell.noexs.core.debugger;

/**
 * Entrée de la commande 0x32 (ReadMemMultiCompressed) : toutes les zones ont la même taille
 * {@code dataSize}. Sérialisé en int dataSize + int count + count adresses u64
 * (writeRecord écrit le tableau avec son propre length).
 */
public record RDebReadMultiCompressedInput(int dataSize, long[] addresses) {
}

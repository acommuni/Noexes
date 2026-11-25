package me.mdbell.noexs.core;

/**
 * Entrée d'adresse freezée retournée par la commande 0x30 (nécessite DMNT attaché).
 *
 * @param address adresse en mémoire du processus cheaté
 * @param value   valeur freezée
 * @param width   largeur en octets (1, 2, 4 ou 8)
 */
public record FrozenAddress(long address, long value, int width) {
}

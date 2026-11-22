package me.mdbell.noexs.core.debugger;

/**
 * Résultat d'un chemin de pointeurs résolu (commande 0x1E), un record par entrée.
 * idx : index du chemin, depth : profondeur atteinte, address : adresse intermédiaire,
 * value : valeur finale résolue.
 */
public record RDebResolvePointerOutput(int idx, short depth, long address, long value) {
}

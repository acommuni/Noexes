package me.mdbell.noexs.core.debugger;

/**
 * Bornes mémoire retournées par la commande 0x19 (dump ptr) avant le flux de pointeurs.
 */
public record RDebDumpPtrOutput(long mainStart, long mainEnd, long heapStart, long heapEnd) {
}

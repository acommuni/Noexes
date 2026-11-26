package me.mdbell.noexs.core.debugger;

/** Entrée de la commande 0x31 (DisableFrozenAddress) : adresse à dé-freezer. */
public record RDebDisableFrozenAddressInput(long address) {
}

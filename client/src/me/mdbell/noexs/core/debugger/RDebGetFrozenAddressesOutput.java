package me.mdbell.noexs.core.debugger;

/**
 * Sortie de la commande 0x30 (GetFrozenAddresses) : tableau d'entrées freezées.
 * Sérialisé en int count + count entrées {address u64, value u64, width u8}.
 */
public record RDebGetFrozenAddressesOutput(RDebFrozenAddressEntry[] entries) {
}

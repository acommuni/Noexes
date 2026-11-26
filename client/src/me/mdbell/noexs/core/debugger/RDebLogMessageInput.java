package me.mdbell.noexs.core.debugger;

/**
 * Entrée de la commande 0x33 (LOG_MESSAGE) : message envoyé au serveur.
 * Sérialisé par EDebDataType.STRING en int length + octets UTF-8 (cap 2048 côté serveur).
 */
public record RDebLogMessageInput(String message) {
}

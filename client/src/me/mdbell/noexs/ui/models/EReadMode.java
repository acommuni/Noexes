package me.mdbell.noexs.ui.models;

/**
 * Mode de lecture mémoire utilisé pour le dump :
 * <ul>
 *   <li>{@link #SINGLE_COMP} — readmem 0x06, chunks 8KB compressés RLE (défaut, le plus rapide mesuré)</li>
 *   <li>{@link #MULTI_UNCOMP} — readmem multi 0x13 brut (batch de zones, sans compression)</li>
 *   <li>{@link #MULTI_COMP} — readmem multi 0x32 compressé RLE (serveur &gt;= 1.1.158, sinon repli 0x13)</li>
 * </ul>
 */
public enum EReadMode {
    SINGLE_COMP("single (comp)"),
    MULTI_UNCOMP("multi (uncomp)"),
    MULTI_COMP("multi (comp)"),
    LOCAL("local (0x16)"); // recherche côté serveur (COMMAND_SEARCH_LOCAL), pas de dump

    private final String label;

    EReadMode(String label) {
        this.label = label;
    }

    public boolean isMulti() {
        return this == MULTI_UNCOMP || this == MULTI_COMP;
    }

    public boolean isLocal() {
        return this == LOCAL;
    }

    @Override
    public String toString() {
        return label;
    }
}

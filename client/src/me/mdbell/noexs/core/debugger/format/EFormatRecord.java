package me.mdbell.noexs.core.debugger.format;

import java.util.function.Function;

import me.mdbell.noexs.core.debugger.EGeckoStatus;

public enum EFormatRecord {
    POINTER(x -> "0x" + Long.toHexString((Long) x).toUpperCase()), 
    STATUS(x -> EGeckoStatus.formatValue((Byte) x));

    // Champ pour stocker la fonction
    private final Function<Object, Object> transformer;

    private EFormatRecord(Function<Object, Object> transformer) {
        this.transformer = transformer;
    }

    public Function<Object, Object> getTransformer() {
        return transformer;
    }

}

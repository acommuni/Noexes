package me.mdbell.noexs.core.debugger;

import java.util.HashMap;
import java.util.Map;

public enum EGeckoStatus {
    Stopping((byte) 0), Running((byte) 1), Paused((byte) 2), Searching((byte) 3);

    private static Map<Byte, EGeckoStatus> STATUS_MAP = new HashMap<Byte, EGeckoStatus>();

    private byte rawValue;

    private EGeckoStatus(byte rawValue) {
        this.rawValue = rawValue;
    }

    public int getRawValue() {
        return rawValue;
    }

    static {
        for (EGeckoStatus enm : EGeckoStatus.values()) {
            STATUS_MAP.put(enm.rawValue, enm);
        }
    }

    public static String formatValue(byte status) {
        String res = "";
        EGeckoStatus egs = STATUS_MAP.get(status);
        if (egs != null) {
            res = egs.name();
        } else {
            res = "UNKONWN STATUS";
        }
        res += "(" + status + ")";
        return res;
    }
}

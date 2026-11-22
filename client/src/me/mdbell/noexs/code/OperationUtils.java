package me.mdbell.noexs.code;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.mdbell.noexs.code.opcode.model.EDataType;

public class OperationUtils {

    private static final Logger logger = LogManager.getLogger(OperationUtils.class);

    public static String padHexValue(String hexValue, EDataType dataType) {
        return padHexValue(hexValue, dataType, null);
    }

    public static String padHexValue(String hexValue, EDataType dataType, Integer forceGabarit) {

        int digit = dataType.getGabarit();
        if (forceGabarit != null) {
            digit = forceGabarit;
        }
        int valueMaxSize = dataType.getDataTypeSize();
        int currentSize = StringUtils.length(hexValue);
        int startPos = Math.max(0, currentSize - valueMaxSize);

        String cappedValue = StringUtils.substring(hexValue, startPos, startPos + valueMaxSize);

        if (!StringUtils.equals(hexValue, cappedValue)) {
            logger.warn("Truncated value : {} to {}", hexValue, cappedValue);
        }

        // TODO : manage error

        String paddedValue = StringUtils.leftPad(StringUtils.upperCase(cappedValue), digit, "0");
        int firstPartSize = digit - 8;

        String value;
        if (digit > 8) {
            value = StringUtils.substring(paddedValue, 0, firstPartSize) + " "
                    + StringUtils.substring(paddedValue, firstPartSize, firstPartSize + 8);
        } else {
            value = paddedValue;
        }

        return value;

    }
}

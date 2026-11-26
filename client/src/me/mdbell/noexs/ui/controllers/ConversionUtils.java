package me.mdbell.noexs.ui.controllers;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.mdbell.noexs.ui.models.EConversionType;
import me.mdbell.util.HexUtils;

public class ConversionUtils {

    private static final Logger logger = LogManager.getLogger(ConversionUtils.class);

    public static Long sourceToLong(EConversionType convType, String rawValue) {
        // Les 2 anciens switch (hex radix 16 / décimal radix 10) étaient identiques à la base près :
        // factorisés en une seule switch expression (Java 14+) avec radix en paramètre.
        if (StringUtils.startsWithIgnoreCase(rawValue, "0x")) {
            return parseValue(convType, StringUtils.trim(StringUtils.removeStartIgnoreCase(rawValue, "0x")), 16);
        }
        return parseValue(convType, StringUtils.trim(rawValue), 10);
    }

    private static Long parseValue(EConversionType convType, String value, int radix) {
        return switch (convType) {
            case FLT -> radix == 16
                    ? (long) Float.floatToIntBits(Float.intBitsToFloat(Integer.parseUnsignedInt(value, 16)))
                    : (long) Float.floatToIntBits(Float.parseFloat(value));
            case U8, S8 -> (long) Byte.parseByte(value, radix);
            case U16, S16 -> (long) Short.parseShort(value, radix);
            case U32 -> (long) Integer.parseUnsignedInt(value, radix);
            case S32 -> (long) Integer.parseInt(value, radix);
            case U64 -> Long.parseUnsignedLong(value, radix);
            case S64 -> Long.parseLong(value, radix);
            default -> {
                logger.error("Conversion type not supported : {}", convType);
                yield null;
            }
        };
    }

    public static String longToString(EConversionType convTypeSource, EConversionType convTypeDest, long value,
            boolean hex, boolean hexPrefix) {
        String res = null;
        if (hex) {
            int length = convTypeDest.getLength() * 2;
            String conv = null;
            switch (convTypeDest) {
                case FLT:
                    Float f = null;
                    if (convTypeSource == EConversionType.FLT) {
                        conv = Long.toHexString(value);
                    } else {
                        f = (float) value;
                        conv = Long.toHexString(Float.floatToIntBits(f));
                    }
                    break;
                case U8:
                case U16:
                case U32:
                case U64:
                case S8:
                case S16:
                case S32:
                case S64:
                case ADDR:
                    conv = Long.toHexString(value);

                    break;
                default:
                    logger.error("Destionation conversion type not supported : {}", convTypeDest);
                    break;
            }

            int end = conv.length();
            int start = Math.max(0, end - length);
            conv = StringUtils.substring(conv, start, end);
            res = HexUtils.pad('0', length, conv);

            res = StringUtils.upperCase(res);
            if (hexPrefix) {
                res = "0x" + res;
            }
        } else {
            switch (convTypeDest) {
                case FLT:
                    Float f = null;
                    if (convTypeSource == EConversionType.FLT) {
                        f = Float.intBitsToFloat((int) value);
                    } else {
                        f = (float) value;
                    }
                    res = Float.toString(f);
                    break;
                case U8:
                    res = Byte.toString((byte) value);
                    break;
                case U16:
                    res = Short.toString((short) value);
                    break;
                case U32:
                    res = Integer.toUnsignedString((int) value);
                    break;
                case ADDR:
                case U64:
                    res = Long.toUnsignedString(value);
                    break;
                case S8:
                    res = Byte.toString((byte) value);
                    break;
                case S16:
                    res = Short.toString((short) value);
                    break;
                case S32:
                    res = Integer.toString((int) value);
                    break;
                case S64:
                    res = Long.toString(value);
                    break;
                default:
                    logger.error("Destionation conversion type not supported : {}", convTypeDest);
                    break;
            }
        }

        return res;
    }

}

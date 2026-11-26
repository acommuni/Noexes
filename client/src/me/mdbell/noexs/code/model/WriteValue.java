package me.mdbell.noexs.code.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class WriteValue implements IInstruction {

    private Pointer pointer;

    private EValueType valueType;

    private String value;

    public WriteValue(Pointer pointer, EValueType valueType, String value) {
        super();
        this.pointer = pointer;
        this.valueType = valueType;
        this.value = value;
    }

    public Pointer getPointer() {
        return pointer;
    }

    public void setPointer(Pointer pointer) {
        this.pointer = pointer;
    }

    public EValueType getValueType() {
        return valueType;
    }

    public void setValueType(EValueType valueType) {
        this.valueType = valueType;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getLongValue() {
        long res = 0;
        if (StringUtils.startsWithIgnoreCase(value, "0x")) {
            String val = StringUtils.remove(value, "0x");
            res = Long.parseLong(val, 16);
        } else {
            res = switch (valueType) {
                case S8, S16, S32, S64 -> Long.parseUnsignedLong(value);
                case U8, U16, U32, U64 -> Long.parseLong(value);
                case FLT -> Float.floatToIntBits(Float.parseFloat(value));
                // DBL/PTR : ignorés comme avant (res = 0)
                default -> 0L;
            };
        }

        return res;
    }

    public String getHexValue() {
        String res = "";
        if (StringUtils.startsWithIgnoreCase(value, "0x")) {
            res = StringUtils.remove(value, "0x");
        } else {
            res = switch (valueType) {
                case S8, S16, S32, S64 -> Long.toHexString(Long.parseUnsignedLong(value));
                case U8, U16, U32, U64 -> Long.toHexString(Long.parseLong(value));
                case FLT -> Integer.toHexString(Float.floatToIntBits(Float.parseFloat(value)));
                // DBL/PTR : ignorés comme avant (res = "")
                default -> "";
            };
        }
        return res;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
    }
}

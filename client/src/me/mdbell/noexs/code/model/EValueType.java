package me.mdbell.noexs.code.model;

import org.apache.commons.lang3.StringUtils;

import me.mdbell.noexs.code.opcode.model.EOpCodeDataType;

public enum EValueType {
    U8(EOpCodeDataType.T8),
    S8(EOpCodeDataType.T8),
    U16(EOpCodeDataType.T16),
    S16(EOpCodeDataType.T16),
    U32(EOpCodeDataType.T32),
    S32(EOpCodeDataType.T32),
    U64(EOpCodeDataType.T64),
    S64(EOpCodeDataType.T64),
    FLT(EOpCodeDataType.T32),
    DBL(EOpCodeDataType.T64),
    PTR(EOpCodeDataType.ADDR);

    private EOpCodeDataType dataType;

    private EValueType(EOpCodeDataType dataType) {
        this.dataType = dataType;
    }

    public EOpCodeDataType getDataType() {
        return dataType;
    }

    public static EValueType getValueType(String valueType) {
        return EValueType.valueOf(StringUtils.upperCase(valueType));
    }
}

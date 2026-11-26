package me.mdbell.noexs.core.debugger;

import me.mdbell.noexs.core.debugger.format.AFormatField;
import me.mdbell.noexs.core.debugger.format.EFormatRecord;

/** Une entrée freezée, telle que streamée par la commande 0x30 (intercalée addr/value/width). */
public record RDebFrozenAddressEntry(@AFormatField(EFormatRecord.POINTER) long address, long value, byte width) {
}

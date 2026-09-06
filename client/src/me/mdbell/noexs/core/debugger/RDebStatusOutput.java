package me.mdbell.noexs.core.debugger;

import me.mdbell.noexs.core.debugger.format.AFormatField;
import me.mdbell.noexs.core.debugger.format.EFormatRecord;

public record RDebStatusOutput(@AFormatField(EFormatRecord.STATUS) byte status, byte major, byte minor, byte patch) {

}

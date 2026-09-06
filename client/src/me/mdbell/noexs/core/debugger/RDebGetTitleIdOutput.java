package me.mdbell.noexs.core.debugger;

import me.mdbell.noexs.core.debugger.format.AFormatField;
import me.mdbell.noexs.core.debugger.format.EFormatRecord;

public record RDebGetTitleIdOutput(@AFormatField(EFormatRecord.POINTER) long tid) {

}

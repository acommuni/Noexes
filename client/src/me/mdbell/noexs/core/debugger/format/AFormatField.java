package me.mdbell.noexs.core.debugger.format;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME) // Disponible au runtime
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT })
public @interface AFormatField {
    EFormatRecord value(); //
}

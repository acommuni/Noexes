package me.mdbell.noexs.code.reverse.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import me.mdbell.noexs.code.EOperation;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ARevOperation {
    public EOperation operation();
}
package me.mdbell.noexs.core.debugger;

import me.mdbell.noexs.core.Result;

public record RDebResult(int mod, int desc) {

    public boolean failed() {
        return mod != 0 || desc != 0;
    }

    public boolean succeeded() {
        return mod == 0 && desc == 0;
    }

    public String message() {
        return "Module:" + mod + " Code:" + desc;
    }

    @Override
    public String toString() {
        return "Result{" + "mod=" + mod + ", desc=" + desc + '}';
    }

    public static Result valueOf(int rc) {
        return new Result(module(rc), description(rc));
    }

    public static boolean failed(int rc) {
        return rc != 0;
    }

    public static boolean succeeded(int rc) {
        return rc == 0;
    }

    public static int module(int rc) {
        return rc & 0x1FF;
    }

    public static int description(int rc) {
        return (rc >> 9) & 0x1FFF;
    }
}

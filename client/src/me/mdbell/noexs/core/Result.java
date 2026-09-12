package me.mdbell.noexs.core;

import me.mdbell.noexs.core.result.EModule;

public class Result {

    private int mod;
    private EModule eModule;
    private int desc;
    // Ajouter les Enum pour les erreurs

    public Result(int mod, int desc) {
        this.mod = mod;
        this.desc = desc;
        
        if (failed()) {
            
        }
        
        this.eModule = EModule.fromValue(mod);
    }

    public int getModule() {
        return mod;
    }

    public int getDesc() {
        return desc;
    }

    public boolean failed() {
        return mod != 0 || desc != 0;
    }

    public boolean succeeded() {
        return mod == 0 && desc == 0;
    }

    public String message() {
        return "Module:" + mod + "[" + eModule + "]" + " Code:" + desc;
    }

    @Override
    public String toString() {
        return "Result{success=" + succeeded()  + ",mod=" + mod + "[" + eModule + "]" + ", desc=" + desc +  '}';
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

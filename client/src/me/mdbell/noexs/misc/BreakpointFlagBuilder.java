package me.mdbell.noexs.misc;

public class BreakpointFlagBuilder {

    private boolean enabled = true;
    private int bas = 0xF;
    private int lbn = 0;
    private EBreakpointType type = EBreakpointType.UNLINKED_INSN_ADDRESS_MATCH;

    public BreakpointFlagBuilder() {

    }

    public BreakpointFlagBuilder setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public BreakpointFlagBuilder setAddressSelect(int bas) {
        this.bas = bas;
        return this;
    }

    public BreakpointFlagBuilder setLinkedBreakpointNumber(int linked) {
        this.lbn = linked;
        return this;
    }

    public BreakpointFlagBuilder setBreakpointType(EBreakpointType t) {
        this.type = t;
        return this;
    }

    public long getFlag() {
        long res = 0;
        if (enabled) {
            res |= 0x1;
        }
        res |= bas << 5;
        res |= lbn << 16;
        res |= type.getValue() << 20;
        return res;
    }
}

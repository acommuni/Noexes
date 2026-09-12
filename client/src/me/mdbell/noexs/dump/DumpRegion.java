package me.mdbell.noexs.dump;

import me.mdbell.util.HexUtils;

public class DumpRegion {

    private long start;
    private long end;
    private long index;
    private long total = 0;

    public DumpRegion(long start, long end, long index, long total) {
        this.start = start;
        this.end = end;
        this.index = index;
        this.total = total;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public long getSize() {
        return end - start;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    @Override
    public String toString() {
        return "DumpRegion{" + "start=" + HexUtils.formatAddress(start) + ", end=" + HexUtils.formatAddress(end)
                + ", size=" + HexUtils.formatAddress(getSize()) + ", position[" + index + "/" + total + "]}";
    }
}

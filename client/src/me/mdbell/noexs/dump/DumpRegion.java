package me.mdbell.noexs.dump;

import me.mdbell.noexs.core.MemoryInfo;
import me.mdbell.util.HexUtils;

public class DumpRegion {

    private long start;
    private long end;
    private long index;
    private long total = 0;
    private MemoryInfo memoryInfo = null;

    public DumpRegion(long start, long end, long index, long total) {
        this.start = start;
        this.end = end;
        this.index = index;
        this.total = total;
    }

    public DumpRegion(long start, long end, long index, long total, MemoryInfo memoryInfo) {
        this(start, end, index, total);
        this.memoryInfo = memoryInfo;
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

    public boolean isReadable() {
        boolean res = true;
        if (this.memoryInfo != null) {
            res = memoryInfo.isReadable();
        }
        return res;
    }

    @Override
    public String toString() {
        return "DumpRegion{" + "start=" + HexUtils.formatAddress(start) + ", end=" + HexUtils.formatAddress(end)
                + ", size=" + HexUtils.formatAddress(getSize()) + ", position[" + index + "/" + total + "], readable="
                + isReadable() + "}";
    }
}

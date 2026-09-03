package me.mdbell.noexs.dump;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DumpOutputStream extends OutputStream {

    private static final Logger logger = LogManager.getLogger(DumpOutputStream.class);

    private DumpIndex curr;
    private List<DumpIndex> indices;
    private RandomAccessFile dataFile;
    private MemoryDump from;
    private boolean closed = false;

    DumpOutputStream(MemoryDump from, List<DumpIndex> indices, RandomAccessFile data) {
        this.from = from;
        this.indices = indices;
        this.dataFile = data;
    }

    public void setCurrentAddress(long addr) throws IOException {
        if (curr != null) {
            curr.size = dataFile.getFilePointer() - curr.filePos;
            indices.add(curr);
        }
        curr = new DumpIndex();
        curr.addr = addr;
        curr.filePos = dataFile.getFilePointer();
    }

    @Override
    public void write(int b) throws IOException {
        dataFile.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        dataFile.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        dataFile.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        logger.debug("Close dataFile :{}, already closed : {}", dataFile, closed);
        if (!closed) {
            if (curr != null) {
                curr.size = dataFile.getFilePointer() - curr.filePos;
                indices.add(curr);
            }
            from.writeHeader();
            dataFile = null;
            closed = true;
        }
    }
}

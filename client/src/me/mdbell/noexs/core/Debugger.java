package me.mdbell.noexs.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.mdbell.noexs.core.debugger.EDebCommand;
import me.mdbell.noexs.core.debugger.EMemoryAccess;
import me.mdbell.noexs.core.debugger.RDebAttachInput;
import me.mdbell.noexs.core.debugger.RDebCurrentPidOutput;
import me.mdbell.noexs.core.debugger.RDebGetAttachedPidOutput;
import me.mdbell.noexs.core.debugger.RDebGetPidsOutput;
import me.mdbell.noexs.core.debugger.RDebGetTitleIdInput;
import me.mdbell.noexs.core.debugger.RDebGetTitleIdOutput;
import me.mdbell.noexs.core.debugger.RDebSetBreakpointInput;
import me.mdbell.noexs.core.debugger.RDebStatusOutput;
import me.mdbell.noexs.core.utils.AutoReleaseSemaphore;
import me.mdbell.noexs.misc.BreakpointFlagBuilder;
import me.mdbell.noexs.misc.BreakpointType;
import me.mdbell.noexs.misc.WatchpointFlagBuilder;
import me.mdbell.noexs.ui.NoexsApplication;
import me.mdbell.noexs.ui.models.DataType;
import me.mdbell.util.HexUtils;

public class Debugger implements Commands, Closeable {

    private static final Logger logger = LogManager.getLogger(Debugger.class);
    private final IConnection conn;
    /** Cache LRU des MemoryInfo (64 entrées max), indexé par adresse de début. */
    private final LinkedHashMap<Long, MemoryInfo> memInfoCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, MemoryInfo> eldest) {
                    return size() > 64;
                }
            };
    private Semaphore semaphore = new Semaphore(1);
    private AutoReleaseSemaphore<ConnectionException> autoReleaseSemaphore = new AutoReleaseSemaphore(semaphore,
            ConnectionException.class);
    private int protocolVersion;

    public static final int CURRENT_PROTOCOL_VERSION = (NoexsApplication.VERSION_MAJOR << 16)
            | (NoexsApplication.VERSION_MINOR) << 8;

    public Debugger(IConnection conn) {
        this.conn = conn;
    }

    public IConnection raw() {
        return conn;
    }

    /** Exécute une action sous le sémaphore de connexion (libéré automatiquement). */
    private <T> T withLock(Supplier<T> action) {
        try (var sem = autoReleaseSemaphore.acquire()) {
            return action.get();
        }
    }

    private void withLock(Runnable action) {
        try (var sem = autoReleaseSemaphore.acquire()) {
            action.run();
        }
    }

    public DebuggerStatus getStatus() {
        return withLock(() -> {
            RDebStatusOutput statusOutput = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_STATUS);

            int status = statusOutput.status();
            int major = statusOutput.major();
            int minor = statusOutput.minor();
            int patch = statusOutput.patch();

            this.protocolVersion = (major << 16) | (minor << 8);
            Result rc = conn.readResult();
            logger.debug("COMMAND Result : rc={}", rc);
            if (rc.failed()) {
                throw new ConnectionException("This is impossible, so you've done something terribly wrong", rc);
            }
            if (protocolVersion > CURRENT_PROTOCOL_VERSION) {
                throw new ConnectionException(String.format("Unsupported protocol version:%08X", protocolVersion));
            }
            protocolVersion |= patch;
            return DebuggerStatus.forId(status);
        });
    }

    public void poke(DataType type, long addr, long value) {
        EMemoryAccess ma = EMemoryAccess.from(type);
        poke(ma.pokeCommand(), ma.newPokeInput(addr, value));
    }

    // --- Poke (factorisé) ---

    private void poke(EDebCommand cmd, Object input) {
        withLock(() -> {
            DebuggerUtils.runCommand(conn, cmd, input);
            Result rc = conn.readResult();
            logger.debug("COMMAND {} Result : rc={}", cmd, rc);
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
        });
    }

    // --- Peek (factorisé, buffer 8 bytes identique à l'original) ---

    private byte[] peekBuffer = new byte[8];

    private ByteBuffer peekN(int size, long addr) {
        return readmem(addr, size, peekBuffer).order(ByteOrder.LITTLE_ENDIAN);
    }

    public Result setWatchpoint(boolean read, boolean write, long addr) {
        return withLock(() -> {
            WatchpointFlagBuilder.MatchType t;
            if (read & write) {
                t = WatchpointFlagBuilder.MatchType.ALL;
            } else if (read) {
                t = WatchpointFlagBuilder.MatchType.LOAD;
            } else if (write) {
                t = WatchpointFlagBuilder.MatchType.STORE;
            } else {
                throw new IllegalArgumentException("No flags set for watchpoint");
            }
            int size = 4;
            int id = 0;
            int bpId = id + 4;
            int offset = (int) (addr - (addr & ~3));
            int mask = ((1 << size) - 1) << offset;

            BreakpointFlagBuilder bp = new BreakpointFlagBuilder().setEnabled(true).setAddressSelect(0xF)
                    .setBreakpointType(BreakpointType.LINKED_CONTEXT_IDR_MATCH);
            WatchpointFlagBuilder wp = new WatchpointFlagBuilder().setEnabled(true).setAccessContol(t)
                    .setAddressSelect(mask).setLinkedBreakpointNumber(bpId);
            Result rc = setBreakpoint(bpId, bp.getFlag(), 0);
            if (rc.succeeded()) {
                rc = setBreakpoint(0x10 + id, wp.getFlag(), addr);
            }
            logger.debug("COMMAND Result : rc={}", rc);
            return rc;
        });
    }

    public Result setBreakpoint(int id, long flags, long addr) {
        return withLock(() -> {
            DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_SET_BREAKPOINT,
                    new RDebSetBreakpointInput(id, addr, flags));
            return conn.readResult();
        });
    }

    public Result writemem(byte[] data, long addr) {
        return writemem(data, 0, data.length, addr);
    }

    public Result writemem(byte[] data, int off, int len, long addr) {
        return withLock(() -> {
            conn.writeCommand(COMMAND_WRITE);
            conn.writeLong(addr);
            conn.writeInt(len);
            conn.flush();
            Result r = conn.readResult();
            logger.debug("COMMAND Result : rc={}", r);
            if (r.succeeded()) {
                conn.write(data, off, len);
                conn.flush();
            } else {
                conn.readResult();
                return r;
            }
            return conn.readResult();
        });
    }

    public void readmem(MemoryInfo info, OutputStream to) throws IOException {
        if (!info.isReadable()) {
            return;
        }
        readmem(info.getAddress(), (int) info.getSize(), to);
    }

    /** Sink de réception des chunks lus — permet de factoriser le protocole READ. */
    @FunctionalInterface
    private interface ChunkConsumer {
        void accept(byte[] data, int off, int len) throws IOException;
    }

    /** Cœur unique du protocole COMMAND_READ (0x06). */
    private void readmemInternal(long addr, int size, ChunkConsumer consumer) throws IOException {
        try (var sem = autoReleaseSemaphore.acquire()) {
            conn.writeCommand(COMMAND_READ);
            conn.writeLong(addr);
            conn.writeInt(size);
            conn.flush();

            Result rc = conn.readResult();
            if (rc.failed()) {
                logger.debug("COMMAND Result : rc={}", rc);
                conn.readResult(); // trailing
                throw new ConnectionException(rc);
            }

            byte[] buffer = new byte[2048 * 4];
            int remaining = size;
            while (remaining > 0) {
                rc = conn.readResult();
                if (rc.failed()) {
                    conn.readResult(); // trailing
                    throw new ConnectionException(rc);
                }
                int len = readCompressed(buffer);
                consumer.accept(buffer, 0, len);
                remaining -= len;
            }
            conn.readResult(); // trailing
        }
    }

    /** Wrapper OutputStream (dump fichier). */
    public void readmem(long start, int size, OutputStream to) throws IOException {
        readmemInternal(start, size, to::write);
    }

    /** Wrapper byte[] (peek). */
    public ByteBuffer readmem(long addr, int size, byte[] bytes) {
        final byte[] target = (bytes == null) ? new byte[size] : bytes;
        final int[] pos = {0};
        try {
            readmemInternal(addr, size, (buf, off, len) -> {
                System.arraycopy(buf, off, target, pos[0], len);
                pos[0] += len;
            });
        } catch (IOException e) {
            throw new ConnectionException(e); // arraycopy ne lève pas IOException, sécurité
        }
        return ByteBuffer.wrap(target);
    }

    public Result resume() {
        return getResult(EDebCommand.COMMAND_CONTINUE);
    }

    public Result pause() {
        return getResult(EDebCommand.COMMAND_PAUSE);
    }

    public Result attach(long pid) {
        logger.debug("COMMAND : Attach to pid :{}", pid);
        return withLock(() -> {
            DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_ATTACH, new RDebAttachInput(pid));
            return conn.readResult();
        });
    }

    public Result detach() {
        return getResult(EDebCommand.COMMAND_DETATCH);
    }

    public MemoryInfo query(long address) {
        logger.debug("COMMAND : Query Memory  address:{}", HexUtils.formatAddress(address));
        return withLock(() -> {
            // Cherche dans le cache LRU : toute région connue contenant l'adresse
            for (MemoryInfo info : memInfoCache.values()) {
                if (info.contains(address)) {
                    return info;
                }
            }

            conn.writeCommand(COMMAND_QUERY_MEMORY);
            conn.writeLong(address);
            conn.flush();
            MemoryInfo info = readInfo();
            memInfoCache.put(info.getAddress(), info);
            logger.debug("COMMAND Result :{} ", info);
            return info;
        });
    }

    public MemoryInfo[] query(long start, int max) {
        logger.debug("COMMAND : Query Memory Multi start:{}, max:{}", start, max);
        return withLock(() -> {
            conn.writeCommand(COMMAND_QUERY_MEMORY_MULTI);
            conn.writeLong(start);
            conn.writeInt(max);
            conn.flush();

            MemoryInfo[] res = new MemoryInfo[max];
            int count;
            for (count = 0; count < max; count++) {
                MemoryInfo info = readInfo();
                res[count] = info;
                memInfoCache.put(info.getAddress(), info); // pré-remplit le cache LRU
                if (info.getType() == MemoryType.RESERVED) {
                    break;
                }
            }
            conn.readResult(); // ignored here, it gets checked in readInfo()
            logger.debug("COMMAND query : read {} memory info", count);
            return Arrays.copyOf(res, count);
        });
    }

    public long getCurrentPid() {
        return withLock(() -> {
            RDebCurrentPidOutput currentPid = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_CURRENT_PID);
            long pid = currentPid.pid();
            Result rc = conn.readResult();
            logger.debug("COMMAND Result : rc={}", rc);
            if (rc.failed()) {
                pid = 0;
            }
            logger.debug("COMMAND Result : pid={}", pid);
            return pid;
        });
    }

    public long getAttachedPid() {
        return withLock(() -> {
            RDebGetAttachedPidOutput attachedPid = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_GET_ATTACHED_PID);
            long pid = attachedPid.pid();
            Result rc = conn.readResult();
            logger.debug("COMMAND Result : rc={}", rc);
            if (rc.failed()) {
                throw new ConnectionException("This is impossible, so you've done something terribly wrong", rc);
            }
            return pid;
        });
    }

    public long[] getPids() {
        return withLock(() -> {
            RDebGetPidsOutput getPids = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_GET_PIDS);
            long[] pids = getPids.pids();
            Result rc = conn.readResult();
            logger.debug("COMMAND Result : rc={}", rc);
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
            return pids;
        });
    }

    public long getTitleId(long pid) {
        return withLock(() -> {
            RDebGetTitleIdOutput res = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_GET_TITLEID,
                    new RDebGetTitleIdInput(pid));
            long tid = res.tid();
            Result rc = conn.readResult();
            logger.debug("COMMAND Result : rc={}", rc);
            // TODO throw on failure?
            return tid;
        });
    }

    private void disconnect() {
        getResult(EDebCommand.COMMAND_DISCONNECT, "This is impossible, so you've done something terribly wrong");
    }

    /**
     * Récupère la liste des bookmarks depuis la Switch (commande 0x1B).
     * Protocole : envoie 0x1B, puis boucle { envoie cont=1, lit count, lit count bytes LZ,
     * décompresse } jusqu'à count=0, puis lit le rc final.
     *
     * @return liste des adresses (8 bytes LE chacune) contenues dans le bookmark
     */
    public List<Long> getBookmark() {
        return withLock(() -> {
            conn.writeCommand(COMMAND_GET_BOOKMARK);
            conn.flush();

            List<Long> addresses = new ArrayList<>();
            byte[] compressed = new byte[2048 * 32 * 2];   // 128KB max (taille compressée)
            byte[] decompressed = new byte[2048 * 32];      // 64KB (GECKO_BUFFER_SIZE)

            int count;
            do {
                conn.writeByte(1); // cont = 1 (continue)
                conn.flush();

                count = conn.readInt(); // taille du chunk compressé, 0 = fin
                if (count == 0) {
                    break;
                }
                if (count < 0 || count > compressed.length) {
                    throw new ConnectionException("Protocol desync: bookmark count=0x"
                            + Integer.toHexString(count));
                }
                conn.readFully(compressed, 0, count);
                int len = lzUncompress(compressed, count, decompressed);
                logger.debug("Bookmark chunk: compressed={}, decompressed={}", count, len);

                ByteBuffer bb = ByteBuffer.wrap(decompressed, 0, len).order(ByteOrder.LITTLE_ENDIAN);
                while (bb.remaining() >= 8) {
                    addresses.add(bb.getLong());
                }
            } while (true);

            Result rc = conn.readResult();
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
            logger.debug("Bookmark: {} addresses received", addresses.size());
            return addresses;
        });
    }

    /** Décodeur LZ8 (portage de lz.cpp côté serveur). */
    private static int lzUncompress(byte[] in, int insize, byte[] out) {
        int inpos = 0, outpos = 0;
        while (inpos < insize) {
            int marker = in[inpos++] & 0xFF;
            int front = marker >>> 4;   // high nibble
            int back = marker & 0x0F;   // low nibble
            if (front == 8) {
                System.arraycopy(in, inpos, out, outpos, 8);
            } else {
                long mask = -1L << (8 * front); // 0xFFFF... << (8*front)
                long inVal = readLongLE(in, inpos);
                long backVal = readLongLE(out, outpos - (back + 1) * 8);
                writeLongLE(out, outpos, (inVal & ~mask) | (backVal & mask));
            }
            inpos += front;
            outpos += 8;
        }
        return outpos;
    }

    private static long readLongLE(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static void writeLongLE(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (8 * i));
        }
    }

    public long getCurrentTitleId() {
        long pid = getCurrentPid();
        if (pid == 0) {
            return 0;
        }
        return getTitleId(pid);
    }

    public boolean attached() {
        return getAttachedPid() != 0;
    }

    public boolean connected() {
        return conn.connected();
    }

    @Override
    public void close() throws IOException {
        if (connected()) {
            detach();
            disconnect();
        }
        conn.close();
    }

    private static final int MAX_CHUNK_SIZE = 0x10000; // 64KB — marge de sécurité (serveur max = 8KB)

    private int readCompressed(byte[] buffer) {
        int compressedFlag = conn.readByte();
        int decompressedLen = conn.readInt();

        // Détection de désynchronisation du protocole
        if (compressedFlag != 0 && compressedFlag != 1) {
            throw new ConnectionException("Protocol desync: invalid compressedFlag=" + compressedFlag);
        }
        if (decompressedLen < 0 || decompressedLen > MAX_CHUNK_SIZE) {
            throw new ConnectionException("Protocol desync: decompressedLen=0x"
                    + Integer.toHexString(decompressedLen));
        }

        if (compressedFlag == 0) {
            conn.readFully(buffer, 0, decompressedLen);
        } else {
            int compressedLen = conn.readInt();
            if (compressedLen < 0 || compressedLen > MAX_CHUNK_SIZE) {
                throw new ConnectionException("Protocol desync: compressedLen=0x"
                        + Integer.toHexString(compressedLen));
            }
            byte[] compressed = new byte[compressedLen]; // alloué à la volée, libéré par le GC après usage
            conn.readFully(compressed, 0, compressedLen);
            int pos = 0;
            for (int i = 0; i < compressedLen; i += 2) {
                byte value = compressed[i];
                int count = compressed[i + 1] & 0xFF;
                Arrays.fill(buffer, pos, pos + count, value);
                pos += count;
            }
        }
        return decompressedLen;
    }

    private MemoryInfo readInfo() {
        long addr = conn.readLong();
        long size = conn.readLong();
        int type = conn.readInt();
        int perm = conn.readInt();
        Result rc = conn.readResult();
        if (rc.failed()) {
            throw new ConnectionException(rc);
        }
        return new MemoryInfo(addr, size, type, perm);
    }

    private Result getResult(EDebCommand cmd) {
        return withLock(() -> {
            DebuggerUtils.runCommand(conn, cmd);
            return conn.readResult();
        });
    }

    private Result getResult(EDebCommand cmd, String failureReason) {
        Result res = withLock(() -> {
            DebuggerUtils.runCommand(conn, cmd);
            return conn.readResult();
        });
        if (res.failed()) {
            throw new ConnectionException(failureReason, res);
        }
        logger.debug("COMMAND Result : rc={}", res);
        return res;
    }

    public long peek(DataType type, long addr) {
        EMemoryAccess ma = EMemoryAccess.from(type);
        ByteBuffer buf = peekN(ma.size(), addr);
        return ma.extractPeek(buf);
    }
}
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
import me.mdbell.noexs.core.debugger.RDebDisableFrozenAddressInput;
import me.mdbell.noexs.core.debugger.RDebFrozenAddressEntry;
import me.mdbell.noexs.core.debugger.RDebFreezeAddressInput;
import me.mdbell.noexs.core.debugger.RDebFreezeAddressOutput;
import me.mdbell.noexs.core.debugger.RDebGetAttachedPidOutput;
import me.mdbell.noexs.core.debugger.RDebGetFrozenAddressesOutput;
import me.mdbell.noexs.core.debugger.RDebGetPidsOutput;
import me.mdbell.noexs.core.debugger.RDebGetTitleIdInput;
import me.mdbell.noexs.core.debugger.RDebGetTitleIdOutput;
import me.mdbell.noexs.core.debugger.RDebLogMessageInput;
import me.mdbell.noexs.core.debugger.RDebReadMultiCompressedInput;
import me.mdbell.noexs.core.debugger.RDebResolvePointerOutput;
import me.mdbell.noexs.core.debugger.RDebSearchLocalInput;
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

    /** Version minimale du serveur supportant les extensions 0x30/0x31/0x32 : 1.1.158 (0x1019E). */
    public static final int PROTOCOL_VERSION_JNOEXS_EXT = 0x1019E;

    /** Version minimale du serveur supportant la commande 0x33 (log client -> serveur) : 1.1.160 (0x101A0). */
    public static final int PROTOCOL_VERSION_LOG_MESSAGE = 0x101A0;

    /**
     * Version minimale du serveur supportant le format "record" des extensions 0x30/0x31/0x32
     * (pas de rc intermédiaire, count en u32, ordre record pour 0x32) : 1.1.161 (0x101A1).
     * En dessous, le client retombe sur le protocole manuel 1.1.158-160.
     */
    public static final int PROTOCOL_VERSION_RECORD_EXT = 0x101A1;

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

            // & 0xFF : les champs du record sont des bytes SIGNÉS — patch 158 = 0x9E = -98 en signé,
            // ce qui corrompait protocolVersion en négatif et faisait croire que 0x30+ n'étaient pas supportés.
            int status = statusOutput.status() & 0xFF;
            int major = statusOutput.major() & 0xFF;
            int minor = statusOutput.minor() & 0xFF;
            int patch = statusOutput.patch() & 0xFF;

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
            try {
                for (count = 0; count < max; count++) {
                    MemoryInfo info = readInfo();
                    res[count] = info;
                    memInfoCache.put(info.getAddress(), info); // pré-remplit le cache LRU
                    if (info.getType() == MemoryType.RESERVED) {
                        break;
                    }
                }
            } catch (ConnectionException e) {
                conn.readResult(); // trailing (écrit par le dispatcher même si un item a échoué) — sinon desync
                throw e;
            }
            conn.readResult(); // trailing, ignoré ici (le rc par item est vérifié dans readInfo())
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

    // ==================== Nouvelles commandes 0x13-0x1E ====================

    /** Résultat d'un dump ptr (0x19) : bornes mémoire + paires (from,to) de pointeurs. */
    public record DumpPtrResult(long mainStart, long mainEnd, long heapStart, long heapEnd,
                                List<long[]> pointers) {
        /** Chaque entrée pointers[i] = {fromAddress, toAddress}. */
    }

    /**
     * Lit un flux de chunks LZ compressés (protocole commun à 0x16, 0x17, 0x19, 0x1B).
     * Boucle : envoie cont=1, lit count, lit count bytes, décompresse, jusqu'à count=0.
     * Le consumer reçoit chaque chunk décompressé.
     */
    /**
     * 0x16 — Recherche locale côté SERVEUR (COMMAND_SEARCH_LOCAL) sur [start, end].
     * Le serveur streame des chunks LZ de paires (from u64, to u64 = valeur trouvée), 16 o/pair ;
     * le client envoie cont=1 entre chaque chunk ; le flux se termine par count=0, puis trailing rc.
     * searchSize : 0=8bit, 1=16bit, 2=32bit, 3=64bit. searchType : 0=EQ(value1), 1=RANGE(value1..value2).
     *
     * @return les paires [adresse, valeur] trouvées
     */
    public List<long[]> searchLocal(long start, long end, long value1, long value2, int searchSize, int searchType) {
        return withLock(() -> {
            DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_SEARCH_LOCAL,
                    new RDebSearchLocalInput(start, end, value1, value2, searchSize, searchType));

            List<long[]> results = new ArrayList<>();
            readLzStream(null, bb -> {
                while (bb.remaining() >= 16) {
                    results.add(new long[]{bb.getLong(), bb.getLong()});
                }
            });

            Result rc = conn.readResult(); // trailing
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
            return results;
        });
    }

    private void readLzStream(java.util.function.IntConsumer chunkSizeConsumer,
                              ThrowingConsumer<ByteBuffer> dataConsumer) {
        byte[] compressed = new byte[2048 * 32 * 2]; // 128KB max
        byte[] decompressed = new byte[2048 * 32];    // 64KB
        while (true) {
            conn.writeByte(1); // cont = 1
            conn.flush();
            int count = conn.readInt();
            if (count == 0) break;
            if (count < 0 || count > compressed.length) {
                throw new ConnectionException("Protocol desync: LZ count=0x" + Integer.toHexString(count));
            }
            conn.readFully(compressed, 0, count);
            int len = lzUncompress(compressed, count, decompressed);
            if (chunkSizeConsumer != null) chunkSizeConsumer.accept(len);
            if (dataConsumer != null) {
                dataConsumer.accept(ByteBuffer.wrap(decompressed, 0, len).order(ByteOrder.LITTLE_ENDIAN));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t); // les lambdas ne doivent lancer que des RuntimeException (ConnectionException)
    }

    /**
     * Lecture batch de plusieurs zones en un seul round-trip.
     * Utilise la commande **0x32 compressée** si le serveur la supporte (>= 1.1.158),
     * sinon retombe sur 0x13 brut. Retourne toutes les données concaténées dans un seul
     * buffer avec les offsets de chaque zone.
     */
    public MultiReadResult readmemMulti(List<ZoneToDump> zones) {
        if (isJnoexsExtSupported()) return readmemMultiCompressed(zones); // 0x32 par défaut
        return withLock(() -> {
            int totalSize = zones.stream().mapToInt(ZoneToDump::dataSize).sum();
            byte[] all = new byte[totalSize];

            conn.writeCommand(COMMAND_READ_MULTI);
            conn.writeInt(zones.size());
            conn.writeInt(zones.isEmpty() ? 0 : zones.get(0).dataSize()); // data_size uniforme (protocole)
            for (ZoneToDump z : zones) conn.writeLong(z.address());
            conn.flush();

            Result rc = conn.readResult();
            if (rc.failed()) {
                conn.readResult(); // trailing (écrit par le dispatcher même en cas d'échec) — sinon desync
                throw new ConnectionException(rc);
            }

            List<MultiReadResult.ZoneRead> zoneReads = new ArrayList<>();
            int offset = 0;
            for (ZoneToDump z : zones) {
                rc = conn.readResult();
                if (rc.failed()) {
                    conn.readResult(); // trailing (écrit par le dispatcher même en cas d'échec) — sinon desync
                    throw new ConnectionException(rc);
                }
                conn.readFully(all, offset, z.dataSize());
                zoneReads.add(new MultiReadResult.ZoneRead(z.address(), offset, z.dataSize(),
                        ByteBuffer.wrap(all, offset, z.dataSize()).order(ByteOrder.LITTLE_ENDIAN).slice()));
                offset += z.dataSize();
            }
            conn.readResult(); // trailing
            return new MultiReadResult(zoneReads, totalSize, all);
        });
    }

    /**
     * Variante stream de readmemMulti : écrit les données directement dans {@code out}
     * au fur et à mesure, sans buffer global en RAM. Toutes les zones doivent avoir le même dataSize.
     * Utilise la commande **0x32 compressée** si le serveur la supporte (>= 1.1.158), sinon 0x13 brut.
     *
     * @return nombre total de bytes écrits
     */
    public int readmemMultiToStream(List<ZoneToDump> zones, OutputStream out) {
        if (isJnoexsExtSupported()) return readmemMultiCompressedToStream(zones, out); // 0x32 par défaut
        return readmemMultiToStreamRaw(zones, out); // 0x13 brut
    }

    /**
     * 0x13 brut — Variante stream de readmemMulti en clair (pas de compression RLE),
     * utilisable même si le serveur supporte 0x32 (mode "multi (uncomp)").
     *
     * @return nombre total de bytes écrits
     */
    public int readmemMultiToStreamRaw(List<ZoneToDump> zones, OutputStream out) {
        if (zones.isEmpty()) return 0;
        return withLock(() -> {
            int dataSize = zones.get(0).dataSize();
            conn.writeCommand(COMMAND_READ_MULTI);
            conn.writeInt(zones.size());
            conn.writeInt(dataSize);
            for (ZoneToDump z : zones) conn.writeLong(z.address());
            conn.flush();

            Result rc = conn.readResult();
            if (rc.failed()) {
                conn.readResult(); // trailing (écrit par le dispatcher même en cas d'échec) — sinon desync
                throw new ConnectionException(rc);
            }

            byte[] buf = new byte[dataSize];
            int total = 0;
            for (ZoneToDump z : zones) {
                rc = conn.readResult();
                if (rc.failed()) {
                    conn.readResult(); // trailing (écrit par le dispatcher même en cas d'échec) — sinon desync
                    throw new ConnectionException(rc);
                }
                conn.readFully(buf, 0, z.dataSize());
                try {
                    out.write(buf, 0, z.dataSize());
                } catch (IOException e) {
                    throw new ConnectionException(e); // wrappe : withLock(Supplier) n'accepte pas les checked
                }
                total += z.dataSize();
            }
            conn.readResult(); // trailing
            return total;
        });
    }

    /** @return true si le serveur connecté supporte les extensions JNoexs (0x30/0x31/0x32, serveur >= 1.1.158). */
    public boolean isJnoexsExtSupported() {
        return protocolVersion >= PROTOCOL_VERSION_JNOEXS_EXT;
    }

    /** @return true si le serveur supporte la commande 0x33 (log client -> serveur, >= 1.1.160). */
    public boolean isLogMessageSupported() {
        return protocolVersion >= PROTOCOL_VERSION_LOG_MESSAGE;
    }

    /** @return true si le serveur supporte le format record des extensions 0x30/0x31/0x32 (>= 1.1.161). */
    public boolean isRecordExtSupported() {
        return protocolVersion >= PROTOCOL_VERSION_RECORD_EXT;
    }

    /** @return la version du protocole négociée avec le serveur (ex: 0x1019E pour 1.1.158). */
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * 0x33 — Envoie un message de log au serveur (affiché dans GeckoLog.txt avec horodatage).
     * Record en entrée {@link RDebLogMessageInput} (String sérialisée en int len + octets UTF-8),
     * puis trailing rc. Ne rien faire si le serveur est trop vieux (>= 1.1.160 requis).
     */
    public void sendLogMessage(String msg) {
        if (!isLogMessageSupported()) {
            return;
        }
        withLock(() -> {
            // cap 2048 octets UTF-8 côté client aussi : le serveur ne lit que 2048 octets,
            // envoyer plus laisserait des octets dans le flux = desync avec le trailing rc
            byte[] b = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final String capped = b.length > 2048
                    ? new String(Arrays.copyOf(b, 2048), java.nio.charset.StandardCharsets.UTF_8)
                    : msg;
            DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_LOG_MESSAGE, new RDebLogMessageInput(capped));
            Result rc = conn.readResult(); // trailing
            logger.debug("COMMAND {} Result : rc={}", EDebCommand.COMMAND_LOG_MESSAGE, rc);
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
        });
    }

    /**
     * 0x32 — Variante compressée (RLE) de readmemMultiToStream, supportée serveur >= 1.1.158.
     * Même contrat que {@link #readmemMultiToStream} : écrit les données décompressées zone par
     * zone dans {@code out}. Chaque zone est reçue par sous-chunks (rc + bloc compressé via
     * {@link #readCompressed}); en cas d'échec d'une zone, le trailing est consommé avant de lever
     * l'exception (sinon desync), et l'appelant peut reprendre zone par zone comme pour 0x13.
     *
     * @return nombre total de bytes décompressés écrits
     */
    public int readmemMultiCompressedToStream(List<ZoneToDump> zones, OutputStream out) {
        if (zones.isEmpty()) return 0;
        return withLock(() -> {
            int dataSize = zones.get(0).dataSize();
            if (isRecordExtSupported()) {
                // Serveur >= 1.1.161 : entrée record (int dataSize + long[] addresses)
                long[] addresses = zones.stream().mapToLong(ZoneToDump::address).toArray();
                DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_READ_MULTI_COMPRESSED,
                        new RDebReadMultiCompressedInput(dataSize, addresses));
            } else {
                // Serveur 1.1.158..160 : protocole manuel (size, dataSize, adresses)
                conn.writeCommand(COMMAND_READ_MULTI_COMPRESSED);
                conn.writeInt(zones.size());
                conn.writeInt(dataSize);
                for (ZoneToDump z : zones) conn.writeLong(z.address());
                conn.flush();
            }

            Result rc = conn.readResult(); // rc intermédiaire (écrit par le handler après l'entrée)
            if (rc.failed()) {
                conn.readResult(); // trailing (écrit par le dispatcher même en cas d'échec) — sinon desync
                throw new ConnectionException(rc);
            }

            // sous-chunks serveur ≤ GECKO_BUFFER_SIZE2 (8KB) ; marge si dataSize < 8KB
            byte[] buf = new byte[Math.max(dataSize, 0x2000)];
            int total = 0;
            for (ZoneToDump z : zones) {
                int received = 0;
                while (received < z.dataSize()) {
                    rc = conn.readResult();
                    if (rc.failed()) {
                        conn.readResult(); // trailing — sinon desync
                        throw new ConnectionException(rc);
                    }
                    int len = readCompressed(buf);
                    if (len <= 0) {
                        conn.readResult(); // trailing — sinon desync
                        throw new ConnectionException("Protocol desync: empty compressed chunk");
                    }
                    try {
                        out.write(buf, 0, len);
                    } catch (IOException e) {
                        throw new ConnectionException(e); // wrappe : withLock(Supplier) n'accepte pas les checked
                    }
                    received += len;
                    total += len;
                }
            }
            conn.readResult(); // trailing
            return total;
        });
    }

    /**
     * 0x32 — Variante buffer de readmemMultiCompressedToStream : remplit un seul {@code byte[]}
     * avec les zones concaténées et retourne les offsets de chaque zone (comme readmemMulti).
     * Le verrou est pris par readmemMultiCompressedToStream (ne pas wrapper dans withLock ici).
     */
    public MultiReadResult readmemMultiCompressed(List<ZoneToDump> zones) {
        int totalSize = zones.stream().mapToInt(ZoneToDump::dataSize).sum();
        byte[] all = new byte[totalSize];
        int[] pos = {0};
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                all[pos[0]++] = (byte) b;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                System.arraycopy(b, off, all, pos[0], len);
                pos[0] += len;
            }
        };
        readmemMultiCompressedToStream(zones, out);

        List<MultiReadResult.ZoneRead> zoneReads = new ArrayList<>();
        int offset = 0;
        for (ZoneToDump z : zones) {
            zoneReads.add(new MultiReadResult.ZoneRead(z.address(), offset, z.dataSize(),
                    ByteBuffer.wrap(all, offset, z.dataSize()).order(ByteOrder.LITTLE_ENDIAN).slice()));
            offset += z.dataSize();
        }
        return new MultiReadResult(zoneReads, totalSize, all);
    }

    /**
     * 0x30 — Liste les adresses freezées du processus (nécessite DMNT attaché, commande 0x1A).
     * Serveur >= 1.1.161 : record en sortie {@link RDebGetFrozenAddressesOutput}
     * (int count + count entrées {address u64, value u64, width u8}), puis trailing rc.
     * Serveur 1.1.158..160 : protocole manuel (rc, count u64, entrées, trailing).
     */
    public List<FrozenAddress> getFrozenAddresses() {
        return withLock(() -> {
            if (isRecordExtSupported()) {
                RDebGetFrozenAddressesOutput out = DebuggerUtils.runCommand(conn,
                        EDebCommand.COMMAND_GET_FROZEN_ADDRESSES);
                Result rc = conn.readResult(); // trailing
                logger.debug("COMMAND {} Result : rc={}", EDebCommand.COMMAND_GET_FROZEN_ADDRESSES, rc);
                if (rc.failed()) {
                    throw new ConnectionException(rc);
                }
                RDebFrozenAddressEntry[] entries = out.entries();
                List<FrozenAddress> list = new ArrayList<>(entries.length);
                for (RDebFrozenAddressEntry e : entries) {
                    list.add(new FrozenAddress(e.address(), e.value(), e.width() & 0xFF));
                }
                return list;
            }
            // --- protocole manuel 1.1.158..160 ---
            conn.writeCommand(COMMAND_GET_FROZEN_ADDRESSES);
            conn.flush();

            Result rc = conn.readResult();
            if (rc.failed()) {
                conn.readResult(); // trailing — sinon desync
                throw new ConnectionException(rc);
            }
            long count = conn.readLong();
            if (count < 0 || count > 1_000_000) {
                conn.readResult(); // trailing — sinon desync
                throw new ConnectionException("Protocol desync: frozen count=0x" + Long.toHexString(count));
            }
            List<FrozenAddress> list = new ArrayList<>((int) Math.min(count, 4096));
            for (long i = 0; i < count; i++) {
                long address = conn.readLong();
                long value = conn.readLong();
                int width = conn.readByte() & 0xFF;
                list.add(new FrozenAddress(address, value, width));
            }
            rc = conn.readResult(); // trailing
            if (rc.failed()) {
                throw new ConnectionException(rc);
            }
            return list;
        });
    }

    /**
     * 0x31 — Supprime le freeze d'une adresse (nécessite DMNT attaché, commande 0x1A).
     * Serveur >= 1.1.161 : record en entrée {@link RDebDisableFrozenAddressInput}, puis trailing rc.
     * Serveur 1.1.158..160 : address (u64) manuel, rc intermédiaire, trailing.
     */
    public void unfreezeAddress(long address) {
        withLock(() -> {
            if (isRecordExtSupported()) {
                DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_DISABLE_FROZEN_ADDRESS,
                        new RDebDisableFrozenAddressInput(address));
                Result rc = conn.readResult(); // trailing
                logger.debug("COMMAND {} Result : rc={}", EDebCommand.COMMAND_DISABLE_FROZEN_ADDRESS, rc);
                if (rc.failed()) {
                    throw new ConnectionException(rc);
                }
                return;
            }
            // --- protocole manuel 1.1.158..160 ---
            conn.writeCommand(COMMAND_DISABLE_FROZEN_ADDRESS);
            conn.writeLong(address);
            conn.flush();

            Result rc = conn.readResult();
            if (rc.failed()) {
                conn.readResult(); // trailing — sinon desync
                throw new ConnectionException(rc);
            }
            conn.readResult(); // trailing
        });
    }

    /** 0x15 — Freeze une adresse en mémoire (via DMNT). Retourne la valeur freezée. */
    public long freezeAddress(long addr, long width) {
        RDebFreezeAddressOutput out = withLock(() -> {
            RDebFreezeAddressOutput o = DebuggerUtils.runCommand(conn, EDebCommand.COMMAND_FREEZE_ADDRESS,
                    new RDebFreezeAddressInput(addr, width));
            Result rc = conn.readResult();
            if (rc.failed()) throw new ConnectionException(rc);
            return o;
        });
        return out.value();
    }

    /**
     * 0x19 — Dump des pointeurs (main + heap). Retourne les bornes et toutes les paires
     * (from, to) trouvées. Le flux de paires est lu en LZ comme getBookmark.
     */
    public DumpPtrResult dumpPtr() {
        return withLock(() -> {
            conn.writeCommand(COMMAND_DUMP_PTR);
            conn.flush();

            // 32 bytes de bornes : mainStart, mainEnd, heapStart, heapEnd (8B LE chacune)
            long mainStart = conn.readLong();
            long mainEnd = conn.readLong();
            long heapStart = conn.readLong();
            long heapEnd = conn.readLong();

            List<long[]> pointers = new ArrayList<>();
            readLzStream(null, bb -> {
                while (bb.remaining() >= 16) {
                    long from = bb.getLong();
                    long to = bb.getLong();
                    pointers.add(new long[]{from, to});
                }
            });

            Result rc = conn.readResult();
            if (rc.failed()) throw new ConnectionException(rc);
            logger.debug("DumpPtr: main=[{},{}], heap=[{},{}], {} pointers",
                    Long.toHexString(mainStart), Long.toHexString(mainEnd),
                    Long.toHexString(heapStart), Long.toHexString(heapEnd), pointers.size());
            return new DumpPtrResult(mainStart, mainEnd, heapStart, heapEnd, pointers);
        });
    }

    /**
     * 0x1E — Résout un batch de chemins de pointeurs.
     * Chaque chemin = {depth (s16, >0=main, <0=heap), offsets...}.
     * Retourne pour chaque chemin : {idx, depthAtteint, address, value}.
     */
    public List<RDebResolvePointerOutput> resolvePointers(short[][] paths) {
        return withLock(() -> {
            conn.writeCommand(COMMAND_RESOLVE_POINTERS);
            conn.writeInt(paths.length);
            for (short[] path : paths) {
                conn.writeShort(path[0]); // depth (s16)
                for (int i = 1; i < path.length; i++) {
                    conn.writeInt(path[i] & 0xFFFF); // offset u32 (écrit comme int LE)
                }
            }
            conn.flush();

            List<RDebResolvePointerOutput> results = new ArrayList<>();
            for (int i = 0; i < paths.length; i++) {
                int idx = conn.readInt();
                short depth = conn.readShort();
                long address = conn.readLong();
                long value = conn.readLong();
                results.add(new RDebResolvePointerOutput(idx, depth, address, value));
            }
            Result rc = conn.readResult();
            if (rc.failed()) throw new ConnectionException(rc);
            return results;
        });
    }

    /** 0x1C — Envoie un fichier bookmark vers la Switch (EdiZon DirectTransfer.bmk). */
    public void putBookmark(byte[] data) {
        withLock(() -> {
            conn.writeCommand(COMMAND_PUT_BOOKMARK);
            conn.flush();
            Result ack = conn.readResult();
            if (ack.failed()) throw new ConnectionException(ack);
            conn.writeInt(data.length);
            conn.write(data, 0, data.length);
            conn.flush();
            Result rc = conn.readResult();
            if (rc.failed()) throw new ConnectionException(rc);
        });
    }

    /** 0x18 — Detach du process DMNT. */
    public Result detachDmnt() {
        return getResult(EDebCommand.COMMAND_DETACH_DMNT);
    }

    /** 0x1A — Attach forcé au process DMNT. */
    public Result attachDmnt() {
        return getResult(EDebCommand.COMMAND_ATTACH_DMNT);
    }

    /** 0x1D — Resume du process DMNT. */
    public Result dmntResume() {
        return getResult(EDebCommand.COMMAND_DMNT_RESUME);
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
package me.mdbell.noexs.ui.services;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.concurrent.Service;
import javafx.concurrent.Task;
import me.mdbell.noexs.core.ConnectionException;
import me.mdbell.noexs.core.Debugger;
import me.mdbell.noexs.core.DebuggerStatus;
import me.mdbell.noexs.core.MemoryInfo;
import me.mdbell.noexs.core.ZoneToDump;
import me.mdbell.noexs.dump.DumpIndex;
import me.mdbell.noexs.dump.DumpOutputStream;
import me.mdbell.noexs.dump.DumpRegion;
import me.mdbell.noexs.dump.DumpRegionFactory;
import me.mdbell.noexs.dump.DumpRegionSupplier;
import me.mdbell.noexs.dump.MemoryDump;
import me.mdbell.noexs.io.MappedList;
import me.mdbell.noexs.misc.DumpAddressList;
import me.mdbell.noexs.ui.NoexesFiles;
import me.mdbell.noexs.ui.models.ConditionType;
import me.mdbell.noexs.ui.models.DataType;
import me.mdbell.noexs.ui.models.ReadMode;
import me.mdbell.noexs.ui.models.SearchType;
import me.mdbell.util.NetUtils;
import me.mdbell.util.Rolling;
import me.mdbell.util.TimeUtils;

public class MemorySearchService extends Service<SearchResult> {

    private static final Logger logger = LogManager.getLogger(MemorySearchService.class);

    private DumpRegionSupplier supplier;
    private Debugger conn;

    private DataProvider provider;

    private DataType dataType;

    private SearchType type;
    private ConditionType compareType;

    private long knownValue;

    private SearchResult prevResult;

    private String fileDumpSuffix;

    /** Mode de lecture mémoire pour le dump (single comp / multi uncomp / multi comp). Défaut : single. */
    private ReadMode readMode = ReadMode.SINGLE_COMP;

    public void clear() {
        logger.debug("Clear search");
        supplier = null;
        conn = null;
        provider = null;
        type = null;
        compareType = null;
        knownValue = 0;
        if (prevResult != null) {
            try {
                prevResult.close();
            } catch (IOException e) {
                logger.error("Unexpected error", e);
            }
        }
        prevResult = null;
    }

    public void setDataType(DataType type) {
        switch (type) {
            case BYTE:
                provider = b -> b.get() & 0xFF;
                break;
            case SHORT:
                provider = b -> b.getShort() & 0xFFFF;
                break;
            case INT:
                provider = b -> b.getInt() & 0xFFFFFFFFL;
                break;
            case LONG:
                provider = ByteBuffer::getLong;
                break;
            default:
                throw new UnsupportedOperationException("Invalid/Unsupported dataType:" + type);
        }
        this.dataType = type;
    }

    public void setFileDumpSuffix(String fileDumpSuffix) {
        this.fileDumpSuffix = fileDumpSuffix;
    }

    public void setReadMode(ReadMode readMode) {
        this.readMode = readMode == null ? ReadMode.SINGLE_COMP : readMode;
    }

    public void setType(SearchType t) {
        this.type = t;
    }

    public void setCompareType(ConditionType type) {
        this.compareType = type;
    }

    public void setKnownValue(long value) {
        this.knownValue = value;
    }

    public void setPrevResult(SearchResult result) {
        this.prevResult = result;
    }

    @Override
    protected Task<SearchResult> createTask() {
        return new SearchTask(fileDumpSuffix);
    }

    private List<Long> createList(File where) {
        try {
            // return MappedList.createLongList(new
            // RandomAccessFile(NoexesFiles.createTempFile("addrs"), "rw"));
            return MappedList.createLongList(where, "rw");
        } catch (Throwable e) {
            logger.error("Error on list creation", e);
            return new ArrayList<>();
        }
    }

    private void search(SearchResult result, ByteBuffer b, long baseAddress, DataProvider provider) throws IOException {
        while (b.hasRemaining()) {
            long addr = baseAddress + b.position();
            long current = provider.get(b);
            long prevValue = result.getPrev() == null ? -1 : result.getPrev(addr);
            if (condition(current, prevValue)) {
                result.addresses.add(addr);
                if (addr < result.start) {
                    result.start = addr;
                }
                if (addr > result.end) {
                    result.end = addr;
                }
            }
        }
    }

    private boolean condition(long value, long prev) {
        switch (type) {
            case PREVIOUS:
                return compare(value, prev);
            case KNOWN:
                return compare(value, knownValue);
            case DIFFERENT:
                long diff = value - prev;
                long absDiff = (diff < 0) ? -diff : diff;
                return compare(absDiff, knownValue);
            default:
                throw new UnsupportedOperationException("Unsupported condition type:" + type);
        }
    }

    private boolean compare(long value, long other) {
        switch (compareType) {
            case EQUALS:
                return value == other;
            case NOT_EQUAL:
                return value != other;
            case LESS_THAN:
                return value < other;
            case LESS_THAN_OR_EQUAL:
                return value <= other;
            case GREATER_THAN:
                return value > other;
            case GREATER_OR_EQUAL:
                return value >= other;
            default:
                throw new UnsupportedOperationException("Unsupported comparison:" + compareType);
        }
    }

    public void setConnection(Debugger conn) {
        this.conn = conn;
    }

    public void setSupplier(DumpRegionSupplier supplier) {
        this.supplier = supplier;
    }

    interface DataProvider {
        long get(ByteBuffer from);
    }

    private class SearchTask extends Task<SearchResult> {

        /**
         * Envoie un batch de zones via readmemMulti (0x32 compressé si serveur >= 1.1.158 et mode comp,
         * sinon 0x13 brut) et écrit directement dans le dump.
         * Les zones d'un batch peuvent être NON contiguës (batches partagés entre régions, groupés
         * par taille) : l'adresse courante est donc posée PAR ZONE (via ZoneAddressOut), et non une
         * seule fois au début du batch — sinon l'index du dump fusionnerait des zones non contiguës.
         * @return nombre de bytes écrits
         */
        private int flushBatch(List<ZoneToDump> batch, DumpOutputStream doutRaw, BufferedOutputStream dout) throws IOException {
            if (batch.isEmpty()) return 0;
            int dataSize = batch.get(0).dataSize();
            logger.debug("Flush batch multi : {} zones x {} o = {} o, first addr={}", batch.size(), dataSize,
                    (long) batch.size() * dataSize, Long.toHexString(batch.get(0).address()));
            CountingOut counter = new CountingOut(dout);
            ZoneAddressOut out = new ZoneAddressOut(doutRaw, counter, batch, dataSize);
            try {
                // Contrat de flux identique quelle que soit la variante : la reprise zone par zone
                // après échec fonctionne pareil (compteur en octets décompressés).
                int written;
                if (readMode == ReadMode.MULTI_UNCOMP) {
                    written = conn.readmemMultiToStreamRaw(batch, out); // 0x13 brut
                } else if (conn.isJnoexsExtSupported()) {
                    written = conn.readmemMultiCompressedToStream(batch, out); // 0x32 compressé (serveur >= 1.1.158)
                } else {
                    written = conn.readmemMultiToStreamRaw(batch, out); // vieux serveur : repli 0x13 brut
                }
                dout.flush();
                return written;
            } catch (ConnectionException e) {
                // Le batch multi a échoué en cours de route (ex: une zone chevauche une page non mappée).
                // readmemMultiToStream écrit zone par zone : on ne relit que les zones pas encore écrites.
                int zonesDone = (int) (counter.count / dataSize);
                logger.warn("Batch multi read failed after {}/{} zones ({}) => per-zone fallback", zonesDone, batch.size(), e.getMessage());
                int written = zonesDone * dataSize;
                for (int i = zonesDone; i < batch.size(); i++) {
                    ZoneToDump z = batch.get(i);
                    written += dumpZoneResilient(z.address(), z.dataSize(), doutRaw, dout);
                }
                return written;
            }
        }

        /**
         * Flush tous les batches multi en attente (groupés par taille de fragment).
         * @return nombre total de bytes écrits
         */
        private int flushAllBatches(Map<Integer, List<ZoneToDump>> batches, DumpOutputStream doutRaw, BufferedOutputStream dout) throws IOException {
            if (batches == null) return 0;
            int total = 0;
            for (List<ZoneToDump> batch : batches.values()) {
                if (!batch.isEmpty()) {
                    total += flushBatch(batch, doutRaw, dout);
                    batch.clear();
                }
            }
            return total;
        }

        /**
         * OutputStream qui pose l'adresse courante du dump (setCurrentAddress) à chaque frontière
         * de zone, juste avant d'écrire le premier octet de la zone. Nécessaire car les zones d'un
         * batch multi peuvent être non contiguës (batch partagé entre régions) : l'index du dump
         * (DumpIndex = run contigu addr->filePos) serait faux sinon. Si une zone échoue avant d'avoir
         * écrit quoi que ce soit, aucune adresse n'est posée pour elle (pas d'index vide).
         */
        private static class ZoneAddressOut extends java.io.OutputStream {
            private final DumpOutputStream doutRaw;
            private final CountingOut counter;
            private final List<ZoneToDump> zones;
            private final int dataSize;

            ZoneAddressOut(DumpOutputStream doutRaw, CountingOut counter, List<ZoneToDump> zones, int dataSize) {
                this.doutRaw = doutRaw;
                this.counter = counter;
                this.zones = zones;
                this.dataSize = dataSize;
            }

            private void beforeWrite() throws IOException {
                long c = counter.count;
                if (dataSize > 0 && c % dataSize == 0) {
                    int zi = (int) (c / dataSize);
                    if (zi < zones.size()) {
                        doutRaw.setCurrentAddress(zones.get(zi).address());
                    }
                }
            }

            @Override public void write(int b) throws IOException { beforeWrite(); counter.write(b); }
            @Override public void write(byte[] b) throws IOException { beforeWrite(); counter.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { beforeWrite(); counter.write(b, off, len); }
            @Override public void flush() throws IOException { counter.flush(); }
        }

        /** OutputStream déléguant qui compte les octets écrits (pour savoir où reprendre après un échec partiel). */
        private static class CountingOut extends java.io.OutputStream {
            final java.io.OutputStream delegate;
            long count = 0;
            CountingOut(java.io.OutputStream delegate) { this.delegate = delegate; }
            @Override public void write(int b) throws IOException { delegate.write(b); count++; }
            @Override public void write(byte[] b) throws IOException { delegate.write(b); count += b.length; }
            @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); count += len; }
            @Override public void flush() throws IOException { delegate.flush(); }
        }

        /**
         * Lit une zone via readmem (0x06). Si la lecture échoue (page non mappée, Kernel desc=106...),
         * la zone est ignorée : si aucun octet n'a été reçu, l'index courant est carrément supprimé
         * (rien d'écrit dans le dump) ; si la lecture a échoué en cours de route, on conserve les
         * octets déjà reçus (index partiel) et on ignore le reste. Aucun zéro n'est écrit, comme ça
         * une recherche de la valeur 0 ne donne pas de faux positifs dans ces zones.
         * La recherche parcourt le dump par DumpIndex (addr -> filePos), donc un trou est sans danger.
         * @return nombre de bytes "traités" (toujours len, pour la progression)
         */
        private int dumpZoneResilient(long addr, int len, DumpOutputStream doutRaw, BufferedOutputStream dout) throws IOException {
            CountingOut counter = new CountingOut(dout);
            try {
                doutRaw.setCurrentAddress(addr);
                conn.readmem(addr, len, counter);
                dout.flush();
            } catch (ConnectionException e) {
                if (counter.count == 0) {
                    doutRaw.discardCurrentIndex(); // zone illisible ignorée, rien d'écrit
                    logger.warn("Read failed at addr:{} size:{} ({}) => zone skipped", String.format("%#x", addr), len, e.getMessage());
                } else {
                    logger.warn("Read failed at addr:{} size:{} after {} bytes ({}) => keeping partial data, rest skipped", String.format("%#x", addr), len, counter.count, e.getMessage());
                }
            }
            return len;
        }


        private static final int DUMP_FRAGMENT_SIZE = 50_000_000;

        // Le mode de lecture (single/multi, comp/uncomp) vient du champ externe readMode (choisi dans l'UI).
        // Mesuré : 0x06 single comp ≈ 10-11 Mio/s ; 0x13 multi brut ≈ 4 Mio/s ; 0x32 multi comp ≈ 4.4 Mio/s.
        /** Taille d'un fragment plein dans un batch multi (doit être ≤ 64KB, limite serveur GECKO_BUFFER_SIZE).
         *  Les fragments plus petits (fin de région, petites zones dispersées) sont aussi batchés,
         *  groupés par taille (le protocole 0x13/0x32 exige un data_size uniforme par batch). */
        private static final int BATCH_FRAGMENT = 32 * 1024;
        /** Nombre de zones par appel readmemMulti (BATCH_FRAGMENT × BATCH_SIZE = 2MB par round-trip). */
        private static final int BATCH_SIZE = 64;
        // private static final int DUMP_BUFFER_SIZE = 10000;
        private static final int DUMP_BUFFER_SIZE = 10000;
        SearchResult res;
        private long curr = 0, total, prevAmt;
        private long lastUpdate;
        Rolling avg = new Rolling(10);

        String fileDumpSuffix;

        public SearchTask(String fileDumpSuffix) {
            super();
            this.fileDumpSuffix = fileDumpSuffix;
        }

        @Override
        protected SearchResult call() throws Exception {
            LocalDateTime time = LocalDateTime.now();
            String fileDumpSuffixFinal = fileDumpSuffix;
            boolean fullSearch = prevResult == null;
            logger.debug("Do serach, fullsearch : {}", fullSearch);

            if (fullSearch) {
                fileDumpSuffixFinal = StringUtils.appendIfMissing(fileDumpSuffixFinal, "full");
            }
            res = new SearchResult(time, fileDumpSuffixFinal);
            res.type = type;
            res.dataType = dataType;
            res.setPrev(prevResult);
            long tid = conn.getCurrentTitleId();
            res.addresses = createList(NoexesFiles.createTempFile(time, tid, fileDumpSuffixFinal, "addrs"));
            res.compareType = compareType;
            res.knownValue = knownValue;

            if (readMode.isLocal()) {
                localSearch(tid);
            } else if (fullSearch) {
                fullSearch();
            } else {
                refineSearch();
            }

            res.save();

            return res;
        }

        /**
         * Mode local (0x16) : la recherche est faite côté SERVEUR (COMMAND_SEARCH_LOCAL) sur la
         * plage du supplier — aucun dump n'est créé (res.curr reste null). Le serveur ne supporte
         * que EQ (value1) et RANGE (value1..value2) : on exige donc SearchType.KNOWN + EQUALS.
         */
        private void localSearch(long tid) throws IOException {
            if (type != SearchType.KNOWN || compareType != ConditionType.EQUALS) {
                throw new UnsupportedOperationException(
                        "Mode local (0x16) : seul KNOWN + EQUALS est supporté côté serveur (EQ/RANGE)");
            }
            long start = supplier.getStart();
            long end = supplier.getEnd();
            res.start = start;
            res.end = end;
            res.getLocation(tid); // chemin de sauvegarde (.srch/.addrs) — le dump n'est PAS créé en mode local
            int searchSize = switch (dataType) {
                case BYTE -> 0;
                case SHORT -> 1;
                case INT -> 2;
                case LONG -> 3;
                default -> throw new UnsupportedOperationException("dataType non supporté en local : " + dataType);
            };
            logger.info("Local search (0x16) : [{}, {}] value={} type={} => Mode lecture mémoire : local (0x16)",
                    Long.toHexString(start), Long.toHexString(end), knownValue, dataType);
            List<long[]> pairs = conn.searchLocal(start, end, knownValue, 0, searchSize, 0 /* EQ */);
            for (long[] p : pairs) {
                res.addresses.add(p[0]);
            }
            logger.info("Local search (0x16) terminée : {} résultats", pairs.size());
            updateProgress(1, 1); // force 100% (pas de dump en mode local)
            updateMessage("Done!");
        }

        private void update(long size) {
            curr += size;
            long currentTS = System.currentTimeMillis();
            long deltaTS = currentTS - lastUpdate;
            if (deltaTS > 500) {
                avg.add(curr - prevAmt, deltaTS);
                lastUpdate = currentTS;
                prevAmt = curr;
            }

            double trueAverage = avg.getAverage();
            long remaining = total - curr;
            double eta = 0;
            if (trueAverage > 0) {
                eta = remaining / trueAverage * 1000;
            }

            updateMessage(String.format("Refining... (%s/%s) ETA: %s", curr, total, TimeUtils.formatTime((long) eta)));
            updateProgress(curr, total);
        }

        private void refineSearch() throws IOException {
            updateMessage("Computing regions...");
            DumpRegionSupplier supplier = computeRegions(prevResult);
            res.curr = createDump(res, supplier, false);
            total = prevResult.curr.getSize();

            if (prevResult.addresses.size() == 0) {
                return;
            }

            Iterator<Long> addrs = prevResult.addresses.iterator();
            Long addr = addrs.next();
            for (DumpIndex idx : res.curr.getIndices()) {
                if (!addrs.hasNext() || isCancelled()) {
                    break;
                }
                ByteBuffer buffer = res.curr.getBuffer(idx);
                while (buffer.hasRemaining() && !isCancelled()) {
                    long l = idx.getAddress() + buffer.position();
                    long value = provider.get(buffer);
                    if (l == addr) {
                        long prev = res.type.requiresPrevious() ? res.getPrev(l) : -1;
                        if (condition(value, prev)) {
                            res.addresses.add(l);
                            addAddress(addr);
                        }
                        addr = addrs.next();
                    } else
                        while (l > addr && addrs.hasNext()) {
                            addr = addrs.next();
                        }
                }
                update(idx.getSize());
            }
        }

        private void fullSearch() throws Exception {
            res.curr = createDump(res, supplier, true);

            if (isCancelled()) {
                return;
            }

            if (type == SearchType.UNKNOWN) {
                res.addresses = new DumpAddressList(res.curr, res.dataType.getSize());
                res.regions = res.curr.getIndicesAsRegions();
                res.start = supplier.getStart();
                res.end = supplier.getEnd();
                return;
            }

            List<DumpIndex> indices = res.curr.getIndices();
            long size = res.curr.getSize();
            long start = supplier.getStart();
            int done = 0;
            int total = indices.size();
            for (DumpIndex idx : indices) {
                if (isCancelled() || idx.getAddress() >= supplier.getEnd() || idx.getEndAddress() <= start) {
                    continue;
                }
                updateMessage(String.format("Searching... %d/%d", done, total));
                ByteBuffer b = res.curr.getBuffer(idx);
                long addr = idx.getAddress();
                if (addr < start) {
                    b.position((int) (start - addr));
                }
                search(res, b, addr, provider);
                done++;
                updateProgress(done, total); // progression basée sur le nb de régions traitées, pas les bytes
            }
            updateProgress(total, total); // 100%
            logger.debug("Search=>Size:{},Nb Region:{}", size, indices.size());
            return;
        }

        private DumpRegionSupplier computeRegions(SearchResult prev) {
            if (prev.regions != null) {
                return DumpRegionSupplier.createSupplier(prev.getStart(), prev.getEnd(), prev.regions,
                        ((long) prev.size()) * prev.dataType.getSize());
            }
            MemoryInfo[] infos = conn.query(prev.getStart(), 10000);
            List<Long> addresses = prev.getAddresses();
            List<DumpRegion> regions = new ArrayList<>();
            DumpRegionFactory factory = null;
            long size = 0;
            long start = Long.MAX_VALUE;
            long end = 0;
            MemoryInfo info = null;
            for (int i = 0; i < addresses.size() && !isCancelled(); i++) {
                updateProgress(i, addresses.size());
                long addr = addresses.get(i);
                if (addr < start) {
                    start = addr;
                }
                if (addr > end) {
                    end = addr;
                }
                if (factory == null) {
                    factory = DumpRegionFactory.create().setStart(addr).setLength(dataType.getSize()).setIndex(i);
                    info = getInfo(infos, addr);
                } else {
                    long delta = addr - factory.getEnd();
                    if (delta <= 0x10000 && info.contains(addr)) {
                        factory.setEnd(addr + dataType.getSize());
                    } else {
                        size += factory.getLength();
                        regions.add(factory.build());
                        factory = DumpRegionFactory.create().setStart(addr).setLength(dataType.getSize()).setIndex(i);
                        info = getInfo(infos, addr);
                    }
                }
            }
            long nbRegions = regions.size();
            if (factory != null) {
                size += factory.getLength();
                regions.add(factory.build());
                for (DumpRegion dr : regions) {
                    dr.setTotal(nbRegions);
                }
            }
            updateMessage(nbRegions + " regions computed.");
            logger.debug("Regions computed : {}", nbRegions);
            return DumpRegionSupplier.createSupplier(start, end, regions, size);
        }

        private MemoryInfo getInfo(MemoryInfo[] from, long addr) {
            for (MemoryInfo info : from) {
                if (info.contains(addr)) {
                    return info;
                }
            }
            return null;
        }

        MemoryDump createDump(SearchResult res, DumpRegionSupplier supplier, boolean showDumpInfo) throws IOException {
            logger.info("Create dump for search : {}", res);

            if (NoexesFiles.getTempDir().getFreeSpace() < supplier.getRealSize()) {
                throw new IOException("Not enough free space for dump!");
            }
            long startOfDump = System.currentTimeMillis();

            DebuggerStatus currentStatus = conn.getStatus();
            boolean resume = currentStatus == DebuggerStatus.RUNNING;
            logger.debug("Current status : {}, Resume after creating the dump : {}", currentStatus, resume);

            // pause the game
            conn.pause();

            // Taille totale = somme des vraies tailles des régions LISIBLES (pas end-start de la plage,
            // qui inclut les trous non mappés) => progression/ETA justes.
            long totalSize = supplier.getRealSize();

            long unreadTotalSize = 0;
            // long lastUpdate = 0;
            long lastUpdate = System.currentTimeMillis();
            long prevRead = 0;
            Rolling avg = new Rolling(10);
            long read = 0;
            long realRead = 0;
            MemoryDump dump;
            DumpOutputStream doutRaw;
            try {
                long tid = conn.getCurrentTitleId();
                File location = res.getLocation(tid);
                logger.info("Create dump : {} for tid : {} with supplier : {} => Mode lecture mémoire : {}",
                        location, tid, supplier.getDescription(), readMode);
                dump = new MemoryDump(location);
                dump.setTid(tid);
                dump.getInfos().addAll(Arrays.asList(conn.query(0, DUMP_BUFFER_SIZE)));
                doutRaw = dump.openStream();

            } catch (IOException e) {
                logger.error("Error while creating dump file", e);
                return null;
            }
            try (BufferedOutputStream dout = new BufferedOutputStream(doutRaw, 1024 * 1024)) {
                // Batches multi partagés entre les régions, groupés par taille de fragment :
                // le protocole 0x13/0x32 exige un data_size uniforme par batch, donc on groupe
                // les zones de même taille — petites zones < 32 Ko comprises, et plus seulement
                // les fragments pleins de 32 Ko d'une même région.
                // TreeMap à clés DÉCROISSANTES : au flush, les plus grosses zones sont vidées en premier.
                Map<Integer, List<ZoneToDump>> batches = readMode.isMulti()
                        ? new TreeMap<>((a, b) -> Integer.compare(b, a)) : null;
                List<DumpRegion> regions = new ArrayList<>();
                DumpRegion rr;
                while (!isCancelled() && (rr = supplier.get()) != null) {
                    regions.add(rr);
                }
                // Tri par taille DÉCROISSANTE uniquement en mode multi (utile pour vider les gros
                // batches en premier) ; en single on garde l'ordre des adresses (streaming séquentiel).
                if (readMode.isMulti()) {
                    regions.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));
                }
                for (DumpRegion r : regions) {
                    if (isCancelled()) {
                        break;
                    }

                    long size = r.getSize();
                    long addr = r.getStart();
                    if (!r.isReadable()) {
                        // Région non lisible : comptée à part (unreadTotalSize), mais PAS dans read,
                        // qui ne doit compter que les octets des régions lisibles (totalSize = getRealSize).
                        unreadTotalSize += size;
                        if (showDumpInfo) {
                            logger.debug("Not Dumping region (not readable) : {} =>size:{},addr:{}", r, size, addr);
                        }
                        double trueAverage = avg.getAverage();
                        long remaining = totalSize - read;
                        double eta = 0;
                        if (trueAverage > 0) {
                            eta = remaining / trueAverage * 1000;
                        }

                        updateProgress(read, totalSize);
                        long elapsed = System.currentTimeMillis() - startOfDump;
                        updateMessage(String.format("Dumping - DL: %s/s T: %s R: %s ETA: %s ELAPSED: %s",
                                NetUtils.formatSize((long) (trueAverage)), NetUtils.formatSize(totalSize),
                                NetUtils.formatSize(remaining), TimeUtils.formatTime((long) (eta)),
                                TimeUtils.formatTime(elapsed)));

                        continue;
                    }
                    long regionStartTS = System.currentTimeMillis();
                    // Mode hybride : batch multi (0x13) uniquement pour les grandes régions,
                    // les petites passent en readmem 0x06 simple (compressé, 1 round-trip de toute façon)
                    // Mode multi uniquement pour les PETITES zones (<= BATCH_FRAGMENT = 32 Ko) :
                    // elles sont batchées ensemble (même taille => data_size uniforme), lues en même
                    // temps, 1 round-trip pour BATCH_SIZE zones. Les grosses régions restent en single
                    // (0x06 streame en continu sans round-trip par chunk, plus rapide sur du contigu).
                    boolean useMulti = readMode.isMulti() && size <= BATCH_FRAGMENT;

                    while (size > 0 && !isCancelled()) {
                        long currentTS = System.currentTimeMillis();
                        long deltaTS = currentTS - lastUpdate;
                        if (deltaTS > 500) {
                            avg.add(read - prevRead, deltaTS);
                            prevRead = read;
                            lastUpdate = currentTS;
                        }

                        if (useMulti) {
                            // Tout fragment (plein 32 Ko ou partiel < 32 Ko) est accumulé dans le batch
                            // de SA taille, partagé entre les régions (batches map). size/addr avancés
                            // UNE SEULE FOIS ici, à l'ajout (le flush ne les re-avance pas, sinon double
                            // comptage : région tronquée + zones suivantes à de mauvaises adresses).
                            int frag = (int) Math.min(size, BATCH_FRAGMENT);
                            List<ZoneToDump> batch = batches.computeIfAbsent(frag, k -> new ArrayList<>(BATCH_SIZE));
                            batch.add(new ZoneToDump(addr, frag));
                            size -= frag;
                            addr += frag;
                            if (batch.size() >= BATCH_SIZE) {
                                int done = flushBatch(batch, doutRaw, dout);
                                read += done;
                                realRead += done;
                                batch.clear();
                            }
                        } else {
                            // === ANCIENNE MÉTHODE (readmem 0x06 par fragment) ===
                            // Flush d'abord les batches multi en attente pour conserver l'ordre d'écriture.
                            int flushed = flushAllBatches(batches, doutRaw, dout);
                            read += flushed;
                            realRead += flushed;
                            int len = (int) Math.min(size, DUMP_FRAGMENT_SIZE);
                            dumpZoneResilient(addr, len, doutRaw, dout);
                            size -= len;
                            addr += len;
                            read += len;
                            realRead += len;
                        }

                        double trueAverage = avg.getAverage();
                        long remaining = totalSize - read;
                        double eta = 0;
                        if (trueAverage > 0) {
                            eta = remaining / trueAverage * 1000;
                        }

                        updateProgress(read, totalSize);
                        long elapsed = System.currentTimeMillis() - startOfDump;
                        updateMessage(String.format("Dumping - DL: %s/s T: %s R: %s ETA: %s ELAPSED: %s",
                                NetUtils.formatSize((long) (trueAverage)), NetUtils.formatSize(totalSize),
                                NetUtils.formatSize(remaining), TimeUtils.formatTime((long) eta),
                                TimeUtils.formatTime(elapsed)));

                    }
                    if (showDumpInfo) {
                        long regionDuration = System.currentTimeMillis() - regionStartTS;
                        String mode = !useMulti ? "single(comp)"
                                : readMode == ReadMode.MULTI_UNCOMP ? "multi(uncomp)"
                                : conn.isJnoexsExtSupported() ? "multi(comp)" : "multi(uncomp, fallback 0x13)";
                        logger.debug("Dumping region : {} =>size:{},addr:{} duration:{}ms mode:{}", r, r.getSize(), r.getStart(), regionDuration, mode);
                    }
                }
                // Flush les derniers batches multi en attente (groupés par taille)
                int flushed = flushAllBatches(batches, doutRaw, dout);
                read += flushed;
                realRead += flushed;
            }

            // Force 100% — totalSize = getRealSize() (régions lisibles uniquement) ; read peut rester
            // légèrement en dessous si des zones lisibles ont été skippées (pages non mappées mid-zone).
            updateProgress(totalSize, totalSize);
            updateMessage("Dump finished");

            long stopOfDump = System.currentTimeMillis();

            long duration = (stopOfDump - startOfDump) / 1000;

            float trougput = 0.0f;
            if (duration > 0) {
                trougput = (float) realRead / (duration * 1024.0f * 1024.0f); // cast float AVANT division
            }

            logger.debug("Dump finished (Mode : {}, Real Read:{}, Unread:{}, Duration : {} s, Troughtput : {} Mb/s, Resume : {} )",
                    readMode, realRead, unreadTotalSize, duration, trougput, resume);
            if (resume) {
                conn.resume();
            }

            return isCancelled() ? null : dump;
        }

        private void addAddress(long addr) {
            if (addr < res.start) {
                res.start = addr;
            }
            if (addr > res.end) {
                res.end = addr;
            }
        }

    }
}

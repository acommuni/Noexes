package me.mdbell.noexs.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import me.mdbell.noexs.core.debugger.RDebDisableFrozenAddressInput;
import me.mdbell.noexs.core.debugger.RDebFrozenAddressEntry;
import me.mdbell.noexs.core.debugger.RDebGetFrozenAddressesOutput;
import me.mdbell.noexs.core.debugger.RDebLogMessageInput;
import me.mdbell.noexs.core.debugger.RDebReadMultiCompressedInput;

/**
 * Vérifie la sérialisation record (writeRecord/readRecord) utilisée par les commandes 0x30-0x33 :
 * petits entiers en little-endian, tableaux = int length + éléments, tableaux de records récursifs.
 */
public class TestRecordSerde {

    /** IConnection en mémoire, little-endian comme le vrai protocole. */
    static class MemConn implements IConnection {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in;

        void feed(byte[] data) {
            in = new ByteArrayInputStream(data);
        }

        byte[] written() {
            return out.toByteArray();
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public void write(byte[] data, int off, int len) {
            out.write(data, off, len);
        }

        @Override
        public void writeByte(int i) {
            out.write(i & 0xFF);
        }

        @Override
        public int readByte() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void testLogMessageInput() {
        MemConn c = new MemConn();
        DebuggerUtils.writeRecord(c, RDebLogMessageInput.class, new RDebLogMessageInput("AB"));
        // int len=2 (LE) + 'A' + 'B'
        assertArrayEquals(new byte[] {2, 0, 0, 0, 'A', 'B'}, c.written());

        // round-trip String avec un caractère multi-octets (é = 0xC3 0xA9 en UTF-8)
        MemConn c2 = new MemConn();
        DebuggerUtils.writeRecord(c2, RDebLogMessageInput.class, new RDebLogMessageInput("é"));
        assertArrayEquals(new byte[] {2, 0, 0, 0, (byte) 0xC3, (byte) 0xA9}, c2.written());
        MemConn c3 = new MemConn();
        c3.feed(c2.written());
        RDebLogMessageInput back = DebuggerUtils.readRecord(c3, RDebLogMessageInput.class);
        assertEquals("é", back.message());
    }

    @Test
    public void testDisableFrozenInput() {
        MemConn c = new MemConn();
        DebuggerUtils.writeRecord(c, RDebDisableFrozenAddressInput.class, new RDebDisableFrozenAddressInput(0x1122334455667788L));
        byte[] w = c.written();
        assertEquals(8, w.length);
        // little-endian : 88 77 66 55 44 33 22 11
        assertArrayEquals(new byte[] {(byte) 0x88, (byte) 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11}, w);
    }

    @Test
    public void testReadMultiCompressedInput() {
        MemConn c = new MemConn();
        DebuggerUtils.writeRecord(c, RDebReadMultiCompressedInput.class,
                new RDebReadMultiCompressedInput(0x2000, new long[] {0x1000L, 0x2000L}));
        byte[] w = c.written();
        // int dataSize=0x2000, int count=2, long 0x1000, long 0x2000 => 4+4+8+8 = 24 octets
        assertEquals(24, w.length);
        assertEquals(0x2000, (w[0] & 0xFF) | (w[1] & 0xFF) << 8);
        assertEquals(2, (w[4] & 0xFF) | (w[5] & 0xFF) << 8);
    }

    @Test
    public void testArrayCountCapped() {
        // count corrompu (desync) : 0x7FFFFFFF > MAX_RECORD_ARRAY_LENGTH -> exception AVANT allocation
        MemConn c = new MemConn();
        c.feed(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F});
        org.junit.jupiter.api.Assertions.assertThrows(ConnectionException.class,
                () -> DebuggerUtils.readRecord(c, RDebGetFrozenAddressesOutput.class));
    }

    @Test
    public void testGetFrozenAddressesOutputRoundTrip() {
        // Simule la trame serveur : count(u32)=2 puis entrées intercalées {addr u64, value u64, width u8}
        MemConn server = new MemConn();
        DebuggerUtils.writeRecord(server, RDebGetFrozenAddressesOutput.class, new RDebGetFrozenAddressesOutput(
                new RDebFrozenAddressEntry[] {new RDebFrozenAddressEntry(0x1000L, 0x42L, (byte) 4),
                        new RDebFrozenAddressEntry(0x2000L, 0x99L, (byte) 8)}));
        MemConn client = new MemConn();
        client.feed(server.written());
        RDebGetFrozenAddressesOutput out = DebuggerUtils.readRecord(client, RDebGetFrozenAddressesOutput.class);
        assertEquals(2, out.entries().length);
        assertEquals(0x1000L, out.entries()[0].address());
        assertEquals(0x42L, out.entries()[0].value());
        assertEquals(4, out.entries()[0].width());
        assertEquals(0x2000L, out.entries()[1].address());
        assertEquals(0x99L, out.entries()[1].value());
        assertEquals(8, out.entries()[1].width());
    }
}

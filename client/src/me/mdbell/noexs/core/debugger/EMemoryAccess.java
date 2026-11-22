package me.mdbell.noexs.core.debugger;

import me.mdbell.noexs.ui.models.DataType;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Mapping entre un {@link DataType} (UI) et les commandes/extracteurs
 * du protocole de debuggage (poke + peek).
 */
public enum EMemoryAccess {
    BYTE (DataType.BYTE,  EDebCommand.COMMAND_POKE8,
          (a, v) -> new RDebPoke8Input (a, (byte) v.intValue()),
          buf -> buf.get() & 0xFFL),
    SHORT(DataType.SHORT, EDebCommand.COMMAND_POKE16,
          (a, v) -> new RDebPoke16Input(a, (short) v.intValue()),
          buf -> buf.getShort() & 0xFFFFL),
    INT  (DataType.INT,   EDebCommand.COMMAND_POKE32,
          (a, v) -> new RDebPoke32Input(a, v.intValue()),
          buf -> buf.getInt() & 0xFFFFFFFFL),
    LONG (DataType.LONG,  EDebCommand.COMMAND_POKE64,
          (a, v) -> new RDebPoke64Input(a, v),
          ByteBuffer::getLong);

    private static final Map<DataType, EMemoryAccess> BY_DATA_TYPE = new EnumMap<>(DataType.class);

    static {
        for (EMemoryAccess t : values()) {
            BY_DATA_TYPE.put(t.dataType, t);
        }
    }

    private final DataType dataType;
    private final EDebCommand pokeCommand;
    private final BiFunction<Long, Long, Object> pokeInputFactory;
    private final Function<ByteBuffer, Long> peekExtractor;

    EMemoryAccess(DataType dt, EDebCommand cmd,
                  BiFunction<Long, Long, Object> pokeFactory,
                  Function<ByteBuffer, Long> peekExtractor) {
        this.dataType = dt;
        this.pokeCommand = cmd;
        this.pokeInputFactory = pokeFactory;
        this.peekExtractor = peekExtractor;
    }

    public DataType dataType()                 { return dataType; }
    public int size()                          { return dataType.getSize(); }
    public EDebCommand pokeCommand()           { return pokeCommand; }
    public Object newPokeInput(long addr, long value) { return pokeInputFactory.apply(addr, value); }
    public long extractPeek(ByteBuffer buf)    { return peekExtractor.apply(buf); }

    /** Conversion O(1) via map statique. */
    public static EMemoryAccess from(DataType dt) {
        EMemoryAccess ma = BY_DATA_TYPE.get(dt);
        if (ma == null) {
            throw new IllegalArgumentException("No EMemoryAccess mapping for DataType." + dt);
        }
        return ma;
    }
}

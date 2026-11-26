package me.mdbell.noexs.core;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.mdbell.noexs.core.debugger.EDebCommand;
import me.mdbell.noexs.core.debugger.EDebDataType;
import me.mdbell.noexs.core.debugger.format.AFormatField;
import me.mdbell.noexs.core.debugger.format.EFormatRecord;

public class DebuggerUtils {

    private static final Logger logger = LogManager.getLogger(DebuggerUtils.class);

    /**
     * Borne de sanité pour un tableau lu dans un record de SORTIE. Le mécanisme record ne s'applique
     * qu'à des structures de taille raisonnable (ex: liste des adresses freezées 0x30) — jamais aux
     * gros volumes de données (readMem 0x06/0x13/0x32 restent en flux manuel). Si le count lu dépasse
     * cette borne, c'est quasi certainement un desync de protocole : on lève une exception AVANT
     * d'allouer le tableau (sinon OOM sur un count corrompu de plusieurs milliards).
     * Les tableaux en ENTRÉE (writeRecord) ne sont pas bornés ici : leur taille est contrôlée par le
     * client (ex: batch 0x32 = 64 zones max).
     */
    public static final int MAX_RECORD_ARRAY_LENGTH = 1_000_000;

    public static <T extends Record> T readRecord(IConnection connection, Class<T> recordCls) {
        RecordComponent[] recordComponents = recordCls.getRecordComponents();
        List<Object> values = new ArrayList<>();
        for (RecordComponent rc : recordComponents) {
            Class<?> type = rc.getType();
            Object value = null;
            EDebDataType dt = null;
            if (type.isArray()) {
                Class<?> componentType = type.componentType();
                int count = connection.readInt();
                if (count < 0 || count > MAX_RECORD_ARRAY_LENGTH) {
                    throw new ConnectionException("Protocol desync: record array '" + rc.getName() + "' count="
                            + count + " exceeds MAX_RECORD_ARRAY_LENGTH=" + MAX_RECORD_ARRAY_LENGTH);
                }
                value = Array.newInstance(componentType, count);
                if (Record.class.isAssignableFrom(componentType)) {
                    // tableau de records : lecture récursive (ex: RDebFrozenAddressEntry[])
                    dt = null;
                    @SuppressWarnings("unchecked")
                    Class<? extends Record> recordType = (Class<? extends Record>) componentType;
                    for (int i = 0; i < count; i++) {
                        Array.set(value, i, readRecord(connection, recordType));
                    }
                } else {
                    dt = EDebDataType.getDatatype(componentType);
                    for (int i = 0; i < count; i++) {
                        Object arrayValue = dt.getReadValueMethod().apply(connection);
                        Array.set(value, i, arrayValue);
                    }
                }
            } else {
                dt = EDebDataType.getDatatype(type);
                value = dt.getReadValueMethod().apply(connection);

            }
            values.add(value);

            Object formatedValue = value;
            AFormatField aff = rc.getAnnotation(AFormatField.class);
            if (aff != null) {
                EFormatRecord efr = aff.value();
                formatedValue = efr.getTransformer().apply(value);
            } else {
                formatedValue = formatForLog(type, value);
            }

            logger.debug("Field Read : {}->{}:{}={}", rc.getName(), rc.getType(), dt, formatedValue);
        }

        T res = null;
        try {
            res = ConstructorUtils.invokeConstructor(recordCls, values.toArray());
        } catch (Throwable e) {
            logger.error("Error while contruct record", e);
        }
        return res;
    }

    public static <T extends Record> void writeRecord(IConnection connection, Class<T> recordCls, Object record) {
        RecordComponent[] recordComponents = recordCls.getRecordComponents();
        try {
            for (RecordComponent rc : recordComponents) {
                Class<?> type = rc.getType();
                Object fieldValue = rc.getAccessor().invoke(record);
                if (type.isArray()) {
                    // tableau : int length + éléments (récursif si éléments sont des records)
                    Class<?> componentType = type.componentType();
                    int length = fieldValue == null ? 0 : Array.getLength(fieldValue);
                    connection.writeInt(length);
                    for (int i = 0; i < length; i++) {
                        Object element = Array.get(fieldValue, i);
                        if (Record.class.isAssignableFrom(componentType)) {
                            @SuppressWarnings("unchecked")
                            Class<? extends Record> recordType = (Class<? extends Record>) componentType;
                            writeRecord(connection, recordType, element);
                        } else {
                            EDebDataType dt = EDebDataType.getDatatype(componentType);
                            dt.getWriteValueMethod().accept(connection, element);
                        }
                    }
                    // tableau loggé en UNE ligne dissectée (byte[] -> chaîne UTF-8, long[] -> [0x.., ...])
                    logger.debug("Field Write : {}->{}={}", rc.getName(), type, formatForLog(type, fieldValue));
                } else {
                    EDebDataType dt = EDebDataType.getDatatype(type);
                    dt.getWriteValueMethod().accept(connection, fieldValue);
                    logger.debug("Field Write : {}->{}:{}={}", rc.getName(), type, dt, formatForLog(type, fieldValue));
                }
            }
        } catch (Throwable e) {
            logger.error("Error while writing value", e);
        }
    }

    /**
     * Formate une valeur de champ pour le log de protocole (dissection lisible, pas la valeur brute) :
     * long -> 0x… (hex), byte -> non signé, long[] -> [0x.., 0x..] tronqué à 8 éléments,
     * byte[] -> chaîne UTF-8 entre guillemets, autres tableaux de records -> deepToString.
     */
    private static Object formatForLog(Class<?> type, Object value) {
        if (value == null) {
            return null;
        }
        if (type == byte.class) {
            return ((Byte) value) & 0xFFL;
        }
        if (type == long.class) {
            return "0x" + Long.toHexString((Long) value).toUpperCase();
        }
        if (type == long[].class) {
            long[] a = (long[]) value;
            int n = Math.min(a.length, 8);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append("0x").append(Long.toHexString(a[i]).toUpperCase());
            }
            if (a.length > n) {
                sb.append(", ... +").append(a.length - n);
            }
            return sb.append(']').toString();
        }
        if (type == byte[].class) {
            return "\"" + new String((byte[]) value, StandardCharsets.UTF_8) + "\"";
        }
        if (type == String.class) {
            // chaîne (ex: version du protocole envoyée dans 0x33 LOG_MESSAGE) : entre guillemets
            return "\"" + value + "\"";
        }
        if (type.isArray() && !type.componentType().isPrimitive()) {
            return java.util.Arrays.deepToString((Object[]) value);
        }
        return value;
    }

    public static <T extends Record> T runCommand(IConnection connection, EDebCommand command, Object input) {
        logger.info("Running command : {} [0x{}]", command, Long.toHexString(command.getCode()).toUpperCase());
        connection.writeCommand(command.getCode());
        if (command.getInputRecord() != null) {
            DebuggerUtils.writeRecord(connection, command.getInputRecord(), input);
        }
        connection.flush();
        T res = null;
        if (command.getOutputRecord() != null) {
            res = (T) DebuggerUtils.readRecord(connection, command.getOutputRecord());
        }
        return res;
    }

    public static <T extends Record> T runCommand(IConnection connection, EDebCommand command) {
        return runCommand(connection, command, null);
    }

}

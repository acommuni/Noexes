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
                if (type == byte.class) {
                    formatedValue = ((Byte) value) & 0xFFL;
                } else if (type == byte[].class && value != null) {
                    // byte[] : loggé comme une chaîne UTF-8 (ex: message de 0x33), pas octet par octet
                    formatedValue = "\"" + new String((byte[]) value, StandardCharsets.UTF_8) + "\"";
                }
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
                    boolean logAsString = componentType == byte.class; // byte[] loggé comme chaîne UTF-8
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
                        if (!logAsString) {
                            logger.debug("Field Write : {}[{}]={}", rc.getName(), i, element);
                        }
                    }
                    if (logAsString) {
                        logger.debug("Field Write : {}->byte[{}]=\"{}\"", rc.getName(), length,
                                fieldValue == null ? "" : new String((byte[]) fieldValue, StandardCharsets.UTF_8));
                    }
                } else {
                    EDebDataType dt = EDebDataType.getDatatype(type);
                    dt.getWriteValueMethod().accept(connection, fieldValue);
                    logger.debug("Field Write : {}->{}:{}={}", rc.getName(), type, dt, fieldValue);
                }
            }
        } catch (Throwable e) {
            logger.error("Error while writing value", e);
        }
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

package me.mdbell.noexs.core.debugger;

public enum EDebCommand {

    COMMAND_STATUS(0x01, null, RDebStatusOutput.class),
    COMMAND_POKE8(0x02, RDebPoke8Input.class, null),
    COMMAND_POKE16(0x03, RDebPoke16Input.class, null),
    COMMAND_POKE32(0x04, RDebPoke32Input.class, null),
    COMMAND_POKE64(0x05, RDebPoke64Input.class, null),
    // int COMMAND_READ = 0x06;
    // int COMMAND_WRITE = 0x07;
    COMMAND_CONTINUE(0x08),
    COMMAND_PAUSE(0x09),
    COMMAND_ATTACH(0x0A, RDebAttachInput.class, null),
    COMMAND_DETATCH(0x0B),
    // int COMMAND_QUERY_MEMORY = 0x0C;
    // int COMMAND_QUERY_MEMORY_MULTI = 0x0D;
    COMMAND_CURRENT_PID(0x0E, null, RDebCurrentPidOutput.class),
    COMMAND_GET_ATTACHED_PID(0x0F, null, RDebGetAttachedPidOutput.class),
    COMMAND_GET_PIDS(0x10, null, RDebGetPidsOutput.class),
    COMMAND_GET_TITLEID(0x11, RDebGetTitleIdInput.class, RDebGetTitleIdOutput.class),
    COMMAND_DISCONNECT(0x12),
    COMMAND_READ_MULTI(0x13),          // protocole manuel (liste d'adresses)
    COMMAND_SET_BREAKPOINT(0x14, RDebSetBreakpointInput.class, null),
    COMMAND_FREEZE_ADDRESS(0x15, RDebFreezeAddressInput.class, RDebFreezeAddressOutput.class),
    COMMAND_SEARCH_LOCAL(0x16, RDebSearchLocalInput.class, null), // output = flux LZ manuel
    COMMAND_FETCH_RESULT(0x17),        // protocole manuel
    COMMAND_DETACH_DMNT(0x18),
    COMMAND_DUMP_PTR(0x19, null, RDebDumpPtrOutput.class), // puis flux LZ de paires (from,to)
    COMMAND_ATTACH_DMNT(0x1A),
    COMMAND_GET_BOOKMARK(0x1B),        // protocole manuel (flux LZ)
    COMMAND_PUT_BOOKMARK(0x1C),        // protocole manuel (envoi fichier)
    COMMAND_DMNT_RESUME(0x1D),
    COMMAND_RESOLVE_POINTERS(0x1E);    // input/output manuels (batch de chemins)

    private int code;

    private Class<? extends Record> inputRecord;
    private Class<? extends Record> outputRecord;

    private EDebCommand(int code) {
        this(code, null, null);
    }

    private EDebCommand(int code, Class<? extends Record> inputRecord, Class<? extends Record> outputRecord) {
        this.code = code;
        this.inputRecord = inputRecord;
        this.outputRecord = outputRecord;
    }

    public int getCode() {
        return code;
    }

    public Class<? extends Record> getInputRecord() {
        return inputRecord;
    }

    public Class<? extends Record> getOutputRecord() {
        return outputRecord;
    }

}

package me.mdbell.noexs.core.result;

//Énumération des erreurs libnx nvidia
public enum ELibnxNvidiaError implements ValueEnum<ELibnxNvidiaError> {
    Unknown(1),
    NotImplemented(2),
    NotSupported(3),
    NotInitialized(4),
    BadParameter(5),
    Timeout(6),
    InsufficientMemory(7),
    ReadOnlyAttribute(8),
    InvalidState(9),
    InvalidAddress(10),
    InvalidSize(11),
    BadValue(12),
    AlreadyAllocated(13),
    Busy(14),
    ResourceError(15),
    CountMismatch(16),
    SharedMemoryTooSmall(0x1000),
    FileOperationFailed(0x30003),
    IoctlFailed(0x3000F);

    private final int value;

    ELibnxNvidiaError(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }

    public static ELibnxNvidiaError fromValue(int value) {
        return ValueEnum.fromValue(ELibnxNvidiaError.class, value);
    }

}

package me.mdbell.noexs.core.result;

//Énumération des erreurs libnx binder
public enum ELibnxBinderError implements ValueEnum<ELibnxBinderError> {
    Unknown(1),
    NoMemory(2),
    InvalidOperation(3),
    BadValue(4),
    BadType(5),
    NameNotFound(6),
    PermissionDenied(7),
    NoInit(8),
    AlreadyExists(9),
    DeadObject(10),
    FailedTransaction(11),
    BadIndex(12),
    NotEnoughData(13),
    WouldBlock(14),
    TimedOut(15),
    UnknownTransaction(16),
    FdsNotAllowed(17);

    private final int value;

    ELibnxBinderError(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }

    public static ELibnxBinderError fromValue(int value) {
        return ValueEnum.fromValue(ELibnxBinderError.class, value);
    }
}
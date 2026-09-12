package me.mdbell.noexs.core.result;

//Énumération des erreurs du noyau
public enum EKernelError implements ValueEnum<EKernelError> {
    OutOfSessions(7),
    InvalidCapabilityDescriptor(14),
    NotImplemented(33),
    ThreadTerminating(59),
    OutOfDebugEvents(70),
    InvalidSize(101),
    InvalidAddress(102),
    ResourceExhausted(103),
    OutOfMemory(104),
    OutOfHandles(105),
    InvalidMemoryState(106),
    InvalidMemoryPermissions(108),
    InvalidMemoryRange(110),
    InvalidPriority(112),
    InvalidCoreId(113),
    InvalidHandle(114),
    InvalidUserBuffer(115),
    InvalidCombination(116),
    TimedOut(117),
    Cancelled(118),
    OutOfRange(119),
    InvalidEnumValue(120),
    NotFound(121),
    AlreadyExists(122),
    ConnectionClosed(123),
    UnhandledUserInterrupt(124),
    InvalidState(125),
    ReservedValue(126),
    InvalidHwBreakpoint(127),
    FatalUserException(128),
    OwnedByAnotherProcess(129),
    ConnectionRefused(131),
    OutOfResource(132),
    IpcMapFailed(259),
    IpcCmdbufTooSmall(260),
    NotDebugged(520);

    private final int value;

    EKernelError(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }

    public static EKernelError fromValue(int value) {
        return ValueEnum.fromValue(EKernelError.class, value);
    }
}
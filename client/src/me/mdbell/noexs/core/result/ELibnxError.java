package me.mdbell.noexs.core.result;

//Énumération des erreurs libnx
public enum ELibnxError implements ValueEnum<ELibnxError> {
    BadReloc(1),
    OutOfMemory(2),
    AlreadyMapped(3),
    BadGetInfo_Stack(4),
    BadGetInfo_Heap(5),
    BadQueryMemory(6),
    AlreadyInitialized(7),
    NotInitialized(8),
    NotFound(9),
    IoError(10),
    BadInput(11),
    BadReent(12),
    BufferProducerError(13),
    HandleTooEarly(14),
    HeapAllocFailed(15),
    TooManyOverrides(16),
    ParcelError(17),
    BadGfxInit(18),
    BadGfxEventWait(19),
    BadGfxQueueBuffer(20),
    BadGfxDequeueBuffer(21),
    AppletCmdidNotFound(22),
    BadAppletReceiveMessage(23),
    BadAppletNotifyRunning(24),
    BadAppletGetCurrentFocusState(25),
    BadAppletGetOperationMode(26),
    BadAppletGetPerformanceMode(27),
    BadUsbCommsRead(28),
    BadUsbCommsWrite(29),
    InitFail_SM(30),
    InitFail_AM(31),
    InitFail_HID(32),
    InitFail_FS(33),
    BadGetInfo_Rng(34),
    JitUnavailable(35),
    WeirdKernel(36),
    IncompatSysVer(37),
    InitFail_Time(38),
    TooManyDevOpTabs(39),
    DomainMessageUnknownType(40),
    DomainMessageTooManyObjectIds(41),
    AppletFailedToInitialize(42),
    ApmFailedToInitialize(43),
    NvinfoFailedToInitialize(44),
    NvbufFailedToInitialize(45),
    LibAppletBadExit(46),
    InvalidCmifOutHeader(47),
    ShouldNotHappen(48),
    Timeout(49);

    private final int value;

    ELibnxError(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return value;
    }

    public static ELibnxError fromValue(int value) {
        return ValueEnum.fromValue(ELibnxError.class, value);
    }
}
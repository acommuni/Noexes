#include <switch.h>
#include <string.h>
#include "errors.h"
#include <stdio.h>
#include "gecko.h"
#include "dmntcht.h"

#define ASSERT_EXIT(x) if (R_FAILED(x)) std::exit(1)
#define ASSERT_FATAL(x) if (Result res = x; R_FAILED(res)) fatalThrow(res)

extern "C" {
	extern u32 __start__;

	u32 __nx_applet_type = AppletType_None;

	//#define INNER_HEAP_SIZE 0x834000 // Arbitrary heap size. 
	#define INNER_HEAP_SIZE 0x41A000 // Reduced heap size. 
	size_t nx_inner_heap_size = INNER_HEAP_SIZE;
	char   nx_inner_heap[INNER_HEAP_SIZE];

	void __libnx_initheap(void);
	void __appInit(void);
	void __appExit(void);
}

static Gecko::Context g_Context;
static FILE* g_debugFile = NULL;

void __libnx_initheap(void) {
	void*  addr = nx_inner_heap;
	size_t size = nx_inner_heap_size;

	/* Newlib */
	extern char* fake_heap_start;
	extern char* fake_heap_end;

	fake_heap_start = (char*)addr;
	fake_heap_end   = (char*)addr + size;
}


// See : https://github.com/switchbrew/libnx/blob/master/nx/source/runtime/init.c#L112
void __appInit(void) {
	
    
    Result rc;

    rc = timeInitialize();
    if(R_FAILED(rc)) {
        printf("Failed to init time services, error code %d\n", rc);
    }

    
    rc = smInitialize();
	if (R_FAILED(rc)) {
		fatalThrow(MAKERESULT(Module_Libnx, LibnxError_InitFail_SM));
	}

    if (hosversionGet() == 0) {
        rc = setsysInitialize();
        if (R_SUCCEEDED(rc)) {
            SetSysFirmwareVersion fw;
            rc = setsysGetFirmwareVersion(&fw);
            if (R_SUCCEEDED(rc))
                hosversionSet(MAKEHOSVERSION(fw.major, fw.minor, fw.micro));
            setsysExit();
        }
    }

    rc = ldrDmntInitialize();
	if (R_FAILED(rc)) {
		fatalThrow(MAKERESULT(Module_Libnx, LibnxError_AlreadyInitialized));
	}

	rc = pmdmntInitialize();
	if (R_FAILED(rc)) {
		fatalThrow(MAKERESULT(Module_Libnx, LibnxError_NotInitialized));
	}

	rc= pminfoInitialize();
	if (R_FAILED(rc)) {
		fatalThrow(rc);
	}
    
	
	// rc = nsdevInitialize();
	// if (R_FAILED(rc)) {
	// 	// fatalThrow(MAKERESULT(Module_Libnx, LibnxError_AlreadyInitialized));
	// }
	rc = socketInitialize(socketGetDefaultInitConfig());
	if (R_FAILED(rc)) {
		fatalThrow(MAKERESULT(Module_TCPGecko, TCPGeckoError_initfail));
	}
	
    rc = fsInitialize();
    if (R_FAILED(rc)) {
        fatalThrow(MAKERESULT(Module_Libnx, LibnxError_InitFail_FS));
    }
    
    rc = fsdevMountSdmc();
    if (R_FAILED(rc)) {
        fatalThrow(rc); // maybe set a variable like noSd or something? It doesn't HAVE to log.
    }

    // rc = dmntchtInitialize();
    // if (R_FAILED(rc)) {
    //     fatalThrow(rc); // maybe set a variable like noSd or something? It doesn't HAVE to log.
    // }
}

void __appExit(void) {
	/* Cleanup services. */
    fsdevUnmountAll();
    fsExit();
    pminfoExit();
    socketExit();
    pmdmntExit();
    ldrDmntExit();
    // nsdevExit();
    dmntchtExit();
    timeExit();
	smExit();
}

void logString(const char* str, int len){
    if(g_debugFile){
        fprintf(g_debugFile, str);
        fflush(g_debugFile);
    }
}

void formatLogTimestamp(char* out, unsigned int outSize){
    if (outSize == 0) return;
    out[0] = 0;
    u64 posixNs = 0;
    // PosixTime = nanosecondes depuis l'epoch. Horloge reseau d'abord (0 si pas de synchro
    // internet), puis horloge user (0 si l'heure de la Switch n'a jamais ete reglee).
    if (R_FAILED(timeGetCurrentTime(TimeType_NetworkSystemClock, &posixNs)) || posixNs == 0) {
        posixNs = 0;
        timeGetCurrentTime(TimeType_UserSystemClock, &posixNs);
    }
    if (posixNs > 0) {
        time_t secs = (time_t)(posixNs / 1000000000ULL); // ns -> s
        tm* lt = localtime(&secs);
        if (lt) {
            strftime(out, outSize, " %Y-%m-%d %H:%M:%S", lt);
            return;
        }
    }
    // Horloge non reglee : uptime depuis le boot (svcGetSystemTick = ticks a 19.2 MHz)
    u64 ms = svcGetSystemTick() / 19200ULL;
    snprintf(out, outSize, " [up %llu:%02llu:%02llu.%03llu]",
             (unsigned long long)(ms / 3600000ULL),
             (unsigned long long)((ms / 60000ULL) % 60ULL),
             (unsigned long long)((ms / 1000ULL) % 60ULL),
             (unsigned long long)(ms % 1000ULL));
}

static int net_main(Gecko::Context& ctx) {
    int cmd;		
	cmd = ctx.conn.read();
    if(cmd < 0){
        return -1;
    }
    Result rc = cmd_decode(ctx, cmd);
    if(rc != MAKERESULT(Module_TCPGecko, TCPGeckoError_iofail)){
        return 1;
    }
    return 0;
}

static Result _eventCallback(Gecko::DebugEvent event){
    switch(event.event_type){
        case 0:
        {
            Gecko::ProcessAttachData pad = event.data.proc_attach;
            printf("ProcessAttachEvent(title_id:%08lx, pid:%08lx, name:'%s', mmu_flags:%d)\r\n", pad.title_id, pad.pid, pad.name, pad.mmu_flags);
        }
        break;
        case 1:
        {
            Gecko::ThreadAttachData tad = event.data.thread_attach;
            printf("ThreadAttachEvent(thread_id:%08lx, tls_pointer:%08lx, entry_point:%08lx)\r\n", tad.thread_id, tad.tls_pointer, tad.entry_point);
        }
        break;
        case 4: 
        {
            Gecko::ExceptionData texcept = event.data.exception;
            printf("ExceptionEvent(fault_reg:%16lx, type:%16lx, per_exception:%16lx)\r\n",texcept.fault_reg, texcept.type, texcept.per_exception);
        } break;
        default:
        printf("UnknownEvent(type:%d Data:", event.event_type);
        for(u32 i = 0; i < DEBUG_DATA_SIZE; i++){
            printf("%02X", event.data.raw[i]);
        }
        printf(")\r\n");
    }
    return 0;
}


void threadFuncNetworkLoop(void* arg)
{
    printf("[NetworkLoop] Starting network loop Thread\n");
    
    for (int i=0;i<10;i++) {
        printf("[NetworkLoop] Loop : %d => Status=%d\n", i, g_Context.status);
        svcSleepThread(1000000000);
    }

    void  *ptr = &g_Context;    
    printf("[NetworkLoop] Pointer on context %p\n", (void*)ptr);

    printf("[NetworkLoop] Exit network loop Thread\n");
}


int main(int argc, char **argv)
{
    g_debugFile = fopen("/atmosphere/logs/GeckoLog.txt", "a");//w or a
    g_Context.dbg.addEventCallback(_eventCallback);

    printf("[Main] Starting Gecko gateway\n");

    // time(NULL) peu fiable sur sysmodule : utiliser formatLogTimestamp
    // (horloge systeme ns->s, fallback uptime via svcGetSystemTick)
    char bootTs[40] { 0 };
    formatLogTimestamp(bootTs, sizeof(bootTs));
    printf(">> boot%s\n", bootTs);


    Thread threadNetwork;
    Result rcTN;
    rcTN = threadCreate(&threadNetwork, threadFuncNetworkLoop, NULL, NULL, 0x10000, 0x2C, -2);

    if (R_SUCCEEDED(rcTN))
    {
        printf("[Main] Network loop Thread created\n");
        Result rcTNS = threadStart(&threadNetwork);
        if (R_SUCCEEDED(rcTNS))
        {   
            printf("[Main] Network loop Thread created\n");
        }
    }

    void *ptr = &g_Context;    
    printf("[Main] Pointer on context %p\n", (void*)ptr);

    while(appletMainLoop() && !g_Context.exit){
        g_Context.reset();
        if(g_Context.conn.connect()){
            g_Context.status = Gecko::Status::Running;
            while(appletMainLoop() && !g_Context.exit && g_Context.status && net_main(g_Context) > 0) {
                g_Context.dbg.flushEvents();
                svcSleepThread(0);
            }
        }
    }

    printf("[Main] Stoping Gecko gateway\n");

    if(g_debugFile){
        fclose(g_debugFile);
        g_debugFile = NULL;
    }
    g_Context.reset();
    g_Context.exit = true;
	return 0;
}

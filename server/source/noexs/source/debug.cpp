#include "debug.h"
#include "errors.h"


#define RETURN_NOT_ATTACHED() {       \
    Result rc = attached();         \
    if(R_FAILED(rc)){               \
        return rc;                  \
    }                               \
}


// Fonction qui convertit DebugEventInfo en Gecko::DebugEvent (via un pointeur)
void convertToGeckoDebugEvent(const DebugEventInfo* src, Gecko::DebugEvent* dest) {
    if (!dest) return; // Vérifie que le pointeur n'est pas null

    dest->event_type = src->type; 
    dest->flags = src->flags;
    dest->thread_id = src->thread_id;

    // Traiter l'union info en fonction du type
    switch (src->type) {
        case  DebugEventType_CreateProcess: {
            dest->data.proc_attach.title_id = src->info.create_process.program_id;
            dest->data.proc_attach.pid = src->info.create_process.process_id;
            std::memcpy(dest->data.proc_attach.name, src->info.create_process.name, 0xC);
            dest->data.proc_attach.mmu_flags = src->info.create_process.flags;
            // TODO : dest->info.create_process.user_exception_context_address = src.info.create_process.user_exception_context_address;
            break;
        }

        case DebugEventType_CreateThread: {
            dest->data.thread_attach.thread_id = src->info.create_thread.thread_id;
            dest->data.thread_attach.tls_pointer = (u64)src->info.create_thread.tls_address;
            dest->data.thread_attach.entry_point = (u64)src->info.create_thread.entrypoint;
            break;
        }

        case DebugEventType_ExitProcess: {
            dest->data.exit.type = src->info.exit_process.reason;
            break;
        }

        case DebugEventType_ExitThread: {
            dest->data.exit.type  = src->info.exit_thread.reason;
            break;
        }

        case DebugEventType_Exception: {
            dest->data.exception.type = src->info.exception.type;
            dest->data.exception.fault_reg = (u64)src->info.exception.address;
            //TODO : champs sup

            // Traiter l'union specific
           /*switch (src->info.exception.type) {


                /*
                case DebugException_UndefinedInstruction: {
                    dest->info.exception.specific.undefined_instruction.insn =
                        src.info.exception.specific.undefined_instruction.insn;
                    break;
                }
                case DebugException_DataAbort: {
                    dest->info.exception.specific.data_abort.address =
                        src.info.exception.specific.data_abort.address;
                    break;
                }
                case DebugException_AlignmentFault: {
                    dest->info.exception.specific.alignment_fault.address =
                        src.info.exception.specific.alignment_fault.address;
                    break;
                }/*
                case DebugException_BreakPoint: {
                    dest->data.exception.type =
                        src->info.exception.specific.break_point.type;
                    dest->data.exception.fault_reg =
                        src->info.exception.specific.break_point.address;
                    break;
                }     
                case DebugException_UserBreak: {
                    dest->info.exception.specific.user_break.break_reason =
                        src.info.exception.specific.user_break.break_reason;
                    dest->info.exception.specific.user_break.address =
                        src.info.exception.specific.user_break.address;
                    dest->info.exception.specific.user_break.size =
                        src.info.exception.specific.user_break.size;
                    break;
                }
                case DebugException_DebuggerBreak: {
                    std::memcpy(
                        dest->info.exception.specific.debugger_break.active_thread_ids,
                        src.info.exception.specific.debugger_break.active_thread_ids,
                        sizeof(u64) * 4
                    );
                    break;
                }
                case DebugException_UndefinedSystemCall: {
                    dest->info.exception.specific.undefined_system_call.id =
                        src.info.exception.specific.undefined_system_call.id;
                    break;
                }
                default: {
                    dest->data.exception.per_exception = src.info.exception.specific.raw;
                    break;
                }
            }*/
            break;
        }

        default: {
            // Cas par défaut : ne rien faire ou gérer une erreur
            break;
        }
    }
}

void Gecko::Debugger::addEventCallback(std::function<Result(Gecko::DebugEvent&)> callback){
    callbacks.push_back(callback);
}

Result Gecko::Debugger::fireEvent(Gecko::DebugEvent& event){
    Result rc = 0;
    std::list<std::function<Result(Gecko::DebugEvent&)>>::iterator it;
    for (it=callbacks.begin(); it!=callbacks.end() && R_SUCCEEDED(rc); ++it) {
        rc = (*it)(event);
    }
    return rc;
}





Result Gecko::Debugger::flushEvents(){
    RETURN_NOT_ATTACHED();
    Result rc = 0;
    do{
        Gecko::DebugEvent event;
        // u8* => DebugEventInfo*
        DebugEventInfo debugEvent;
		rc = svcGetDebugEvent((DebugEventInfo*)&debugEvent, handle);
        if(R_SUCCEEDED(rc)){
            convertToGeckoDebugEvent(&debugEvent,&event);
            fireEvent(event);
        }
	}while(R_SUCCEEDED(rc));
    return rc;
}

u64 Gecko::Debugger::attachedPid(){
    return pid;
}

Result Gecko::Debugger::attach(u64 pid){
    Result rc = attached();
    if(R_SUCCEEDED(rc)){
        return MAKERESULT(Module_TCPGecko, TCPGeckoError_already_attached);
    }
    rc = svcDebugActiveProcess(&handle, pid);
    if(R_SUCCEEDED(rc)){
        this->pid = pid;
    } else 
    {handle = 0; pid =0;}
    return rc;
}
//fake attached and detatch 
Result Gecko::Debugger::assign(u64 pid){ 
    Result rc = 0;
    handle = pid;
    this->pid = pid;
    return rc;
}

Result Gecko::Debugger::attached(){
    if(!handle){
        return MAKERESULT(Module_TCPGecko, TCPGeckoError_not_attached);
    }
    return 0;
}

Result Gecko::Debugger::detatch(){
    RETURN_NOT_ATTACHED();
    Result rc = svcCloseHandle(handle);
    if(R_SUCCEEDED(rc)){
        pid = 0;
        handle = 0;
    }
    return rc;
}

Result Gecko::Debugger::resume(){
    RETURN_NOT_ATTACHED();
    flushEvents();
    return svcContinueDebugEvent(handle, 4 | 2 | 1, 0, 0);
}

Result Gecko::Debugger::pause(){
    RETURN_NOT_ATTACHED();
    return svcBreakDebugProcess(handle);
}

Result Gecko::Debugger::query(MemoryInfo* to, u64 addr){
    RETURN_NOT_ATTACHED();
    u32 pageinfo; // ignored
    return svcQueryDebugProcessMemory(to, &pageinfo, handle, addr);
}

Result Gecko::Debugger::listPids(u64* pids, s32* count, u32 max){
    return svcGetProcessList(count, pids, max);
}

Result Gecko::Debugger::readMem(void *buffer, u64 addr, u64 size){
    RETURN_NOT_ATTACHED();
    return svcReadDebugProcessMemory(buffer, handle, addr, size);
}

Result Gecko::Debugger::writeMem(void *buffer, u64 addr, u64 size){
    RETURN_NOT_ATTACHED();
    return svcWriteDebugProcessMemory(handle, buffer, addr, size);
}

Result Gecko::Debugger::setBreakpoint(u32 id, u64 flag, u64 value){
    if(value == 0){
        value = handle;
    }
    return svcSetHardwareBreakpoint(id, flag, value);
}

Gecko::Debugger::~Debugger(){
    Result rc = attached();
    if(R_SUCCEEDED(rc)){
        rc = detatch();
    }
}
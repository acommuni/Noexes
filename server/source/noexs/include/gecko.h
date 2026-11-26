/**
 * @file gecko.h
 * @brief Switch useful tools.
 * @copyright your dad
 */
#pragma once

#include <switch.h>

#include <time.h>
#include <sys/time.h>

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>

#include <sys/stat.h> // Added for stat

#include <cstring> // Added for std::memcmp
#include <string>
#include <vector>


#include "debug.h"
#include "net.h"

#define VER_MAJOR (1)
#define VER_MINOR (1)
#define VER_PATCH (161)

#define GECKO_BUFFER_SIZE (2048 * 32)
#define GECKO_BUFFER_SIZE2 (2048 * 4)

namespace Gecko{
    
    enum Status{
        Stopping,
        Running,
        Paused,
        Searching
    };

    struct Context{
        Connection conn;
        Status status; // current gecko state
        Debugger dbg;
        bool exit;
        u8 buffer[GECKO_BUFFER_SIZE]; // use this buffer for reading/writing chunks of data
        void reset();
};

};

void logString(const char* str, int len);

#define printf(fmt, ...) {                                                                          \
    char formattedString[2048];                                                                     \
    snprintf(formattedString, 2048,fmt, ##__VA_ARGS__);                                             \
    struct timeval tv;                                                                              \
    gettimeofday(&tv, NULL);                                                                        \
    time_t current_time = tv.tv_sec;                                                                \
    struct tm *time_info = localtime(&current_time);                                                \
    char timebuffer[100];                                                                           \
    strftime(timebuffer, sizeof(timebuffer), "%Y-%m-%d %H:%M:%S", time_info);                       \
    char logTimeBuffer[32] { 0 };                                                                   \
    u64 currentTime;                                                                                \
    timeGetCurrentTime(TimeType_UserSystemClock, &currentTime);                                  \
    tm* localTime = std::localtime((std::time_t*)(&currentTime));                                   \
    std::strftime(logTimeBuffer, sizeof(logTimeBuffer), " %Y-%m-%d %H:%M:%S", localTime);                        \
    char buffer[2176];                                                                              \
    logString(buffer, snprintf(buffer, 2176,"[%s] %s", logTimeBuffer, formattedString));            \
}

//logString(buffer, snprintf(buffer, 2176,"[%s.%03ld] %s", timebuffer,tv.tv_usec / 1000, formattedString)); 

#define simpprintf(fmt, ...) {                                          \
    char buffer[2048];                                              \
    logString(buffer, snprintf(buffer, 2048,fmt, ##__VA_ARGS__));   \
}

Result cmd_decode(Gecko::Context& ctx, int cmd);
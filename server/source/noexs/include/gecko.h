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

/* Ecrit dans out l'horodatage pour les logs : " YYYY-MM-DD HH:MM:SS" (horloge systeme,
   TimeType_NetworkSystemClock d'abord puis UserSystemClock), ou " [up H:MM:SS.mmm]" (uptime
   via svcGetSystemTick, tick 19.2 MHz) si l'heure de la console n'est pas reglee.
   Note : timeGetCurrentTime renvoie un PosixTime en NANOSECONDES (diviser par 1e9 pour
   obtenir des secondes — l'ancien code castait les ns directement en time_t). */
void formatLogTimestamp(char* out, unsigned int outSize);

#define printf(fmt, ...) {                                                                          \
    char formattedString[2048];                                                                     \
    snprintf(formattedString, 2048,fmt, ##__VA_ARGS__);                                             \
    char logTimeBuffer[40] { 0 };                                                                   \
    formatLogTimestamp(logTimeBuffer, sizeof(logTimeBuffer));                                      \
    char buffer[2176];                                                                              \
    logString(buffer, snprintf(buffer, 2176,"[%s] %s", logTimeBuffer, formattedString));            \
}

#define simpprintf(fmt, ...) {                                          \
    char buffer[2048];                                              \
    logString(buffer, snprintf(buffer, 2048,fmt, ##__VA_ARGS__));   \
}

Result cmd_decode(Gecko::Context& ctx, int cmd);
/* Fallback for hosts without Linux headers (macOS). It is only reached through -idirafter, so a
 * Linux host keeps its real header. The value is never interpreted: the host test stubs ioctl. */
#ifndef ZDTUN_HOST_SHIM_SOCKIOS_H
#define ZDTUN_HOST_SHIM_SOCKIOS_H

#ifndef SIOCOUTQ
#define SIOCOUTQ 0x5411
#endif

#endif

/*
 * liboadx-nodump.so — preloaded into the guest's sftp-server, and only there.
 *
 * OpenSSH's SFTP server makes itself undumpable before serving a byte
 * (sftp-server.c: platform_disable_tracing(1), "strict"), so that SFTP access
 * cannot read /proc/self/mem. Under proot that call cannot succeed: proot
 * swallows prctl(PR_SET_DUMPABLE) on purpose — an undumpable tracee breaks
 * proot's own access to its memory (proot 0.15, src/syscall/enter.c) — but
 * leaves the result register holding whatever the no-op syscall returned, so
 * the server sees a non-zero result with errno 0, exits 255 with "unable to
 * make the process undumpable: Success", and scp, sftp and every SFTP client
 * fail at their first request.
 *
 * This answers that one call with the 0 proot meant to give, and passes every
 * other prctl through untouched. It protects nothing less than before: under
 * proot the process was never going to be undumpable.
 *
 * Built with no libc at link time (see build.sh); dlsym is resolved from the
 * glibc already in the process when the guest's loader maps this.
 */

#define PR_SET_DUMPABLE 4
#define RTLD_NEXT ((void *) -1L)

extern void *dlsym(void *handle, const char *symbol);

typedef int (*prctl_fn)(int, unsigned long, unsigned long, unsigned long, unsigned long);

__attribute__((visibility("default")))
int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5)
{
    static prctl_fn real;
    if (option == PR_SET_DUMPABLE)
        return 0;
    if (!real)
        real = (prctl_fn) dlsym(RTLD_NEXT, "prctl");
    return real(option, a2, a3, a4, a5);
}

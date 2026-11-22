/* Observe Chrome 158's LaunchProcess execvp boundary. The original libc call,
 * arguments, return value and errno are preserved. The observer ends at exec. */
extern long write(int, const void *, unsigned long);
extern void *dlsym(void *, const char *);
extern int unsetenv(const char *);
extern int *__errno_location(void);
extern void _exit(int);

static int (*libc_execvp)(const char *, char *const []);

__attribute__((constructor)) static void record_browser_load(void) {
    static const char marker[] = "chrome158 browser preload entered\n";
    libc_execvp = dlsym((void *)-1L /* glibc RTLD_NEXT */, "execvp");
    if (!libc_execvp || unsetenv("LD_PRELOAD") || write(2, marker, sizeof(marker) - 1) < 0)
        _exit(125);
}

int execvp(const char *path, char *const argv[]) {
    static const char prefix[] = "chrome158 execvp ";
    unsigned long size = 0;
    int saved_errno = *__errno_location();
    while (size < 1024 && path[size]) ++size;
    write(2, prefix, sizeof(prefix) - 1);
    write(2, path, size);
    write(2, "\n", 1);
    *__errno_location() = saved_errno;
    return libc_execvp(path, argv);
}

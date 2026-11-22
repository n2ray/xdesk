#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/capability.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long long monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

JNIEXPORT jint JNICALL Java_io_github_n2ray_xdesk_probe_ChromeLaunchTest_listenPrivate(
        JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return -ENOMEM;
    int length = snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", name);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (length < 0 || (size_t)length >= sizeof(addr.sun_path)) return -ENAMETOOLONG;
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    // The parent directory is unique to this launch and owned by the app.
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) || chmod(addr.sun_path, 0600) || listen(fd, 1)) {
        int error = errno;
        close(fd);
        return -error;
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_io_github_n2ray_xdesk_probe_ChromeLaunchTest_acceptHelper(
        JNIEnv *env, jclass cls, jint listener) {
    (void)env; (void)cls;
    int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
    if (fd < 0) return -errno;
    struct ucred peer;
    socklen_t size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != getuid()) {
        close(fd);
        return -EACCES;
    }
    return fd;
}

JNIEXPORT jint JNICALL Java_io_github_n2ray_xdesk_probe_ProbeService_runChromeZygote(
        JNIEnv *env, jclass cls, jstring directory, jint request, jint owner) {
    (void)cls;
    struct ucred peer;
    socklen_t size = sizeof(peer);
    if (getsockopt(request, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != (uid_t)owner)
        return -EACCES;
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct caps[2] = {0};
    if (syscall(__NR_capget, &header, caps)) return -errno;
    if (!(caps[0].effective & (1u << CAP_SYS_CHROOT))) return -EPERM;

    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!dir) return -ENOMEM;
    char runtime[4096];
    int len = snprintf(runtime, sizeof(runtime), "%s/libburrow_chrome_fs.so", dir);
    char *const args[] = {runtime, "-r", (char *)dir, "-b", "/proc", "-b", "/dev/null",
        "-w", "/", "--", "/usr/bin/python3", "-I", "-S", "-B", "/opt/xdesk-chrome/start-zygote.py", NULL};
    char *const vars[] = {"LC_ALL=C", "PATH=/usr/bin:/bin", NULL};
    pid_t child = -1;
    posix_spawn_file_actions_t actions;
    int error = posix_spawn_file_actions_init(&actions);
    if (!error) {
        error = posix_spawn_file_actions_adddup2(&actions, request, STDIN_FILENO);
        if (!error && (len < 0 || (size_t)len >= sizeof(runtime))) error = ENAMETOOLONG;
        if (!error) error = posix_spawn(&child, runtime, &actions, NULL, args, vars);
        posix_spawn_file_actions_destroy(&actions);
    }
    (*env)->ReleaseStringUTFChars(env, directory, dir);
    if (error) return -error;

    long long deadline = monotonic_ms() + 42000;
    int status;
    for (;;) {
        pid_t waited = waitpid(child, &status, WNOHANG);
        if (waited == child) return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
        if (waited < 0 && errno != EINTR) return -errno;
        if (monotonic_ms() >= deadline) break;
        usleep(50000);
    }
    kill(child, SIGTERM);  // Burrow's supervisor owns the guest tree.
    for (int i = 0; i < 40; ++i) {
        if (waitpid(child, &status, WNOHANG) == child) return -ETIMEDOUT;
        usleep(50000);
    }
    kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    return -ETIMEDOUT;
}

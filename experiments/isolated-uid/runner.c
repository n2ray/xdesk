#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

/* Only the passed read-only file becomes stdin. The guest has to read it
 * without reopening /proc/self/fd/N. stdout/stderr form the result channel. */
JNIEXPORT jstring JNICALL Java_io_github_n2ray_xdesk_probe_ProbeService_runBurrow(
        JNIEnv *env, jclass cls, jstring directory, jint marker, jstring owner_path) {
    (void)cls;
    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!dir) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, owner_path, NULL);
    if (!path) { (*env)->ReleaseStringUTFChars(env, directory, dir); return NULL; }
    char burrow[4096], guest_bind[4096], file_bind[4096], output[4096] = "";
    snprintf(burrow, sizeof(burrow), "%s/libburrow.so", dir);
    snprintf(guest_bind, sizeof(guest_bind), "%s/libubuntu_busybox.so:/busybox", dir);
    snprintf(file_bind, sizeof(file_bind), "%s:/owner-marker", path);
    char *args[] = {burrow, "-r", (char *)dir, "-b", guest_bind, "-b", file_bind,
        "-b", "/proc/self/attr/current:/domain", "-w", "/", "--", "/busybox", "sh", "-c",
        "IFS= read -r token || exit 20; printf 'via_fd=%s\\n' \"$token\"; "
        "if IFS= read -r other < /owner-marker; then printf 'via_path=%s\\n' \"$other\"; "
        "else echo via_path=DENIED; fi; /busybox cat /domain", NULL};
    char *environment[] = {"LC_ALL=C", "PATH=/", NULL};
    int channel[2];
    if (pipe2(channel, O_CLOEXEC) < 0) {
        snprintf(output, sizeof(output), "pipe: %s", strerror(errno));
        goto done;
    }
    posix_spawn_file_actions_t actions;
    int error = posix_spawn_file_actions_init(&actions);
    pid_t pid = -1;
    if (!error) {
        error = posix_spawn_file_actions_adddup2(&actions, marker, STDIN_FILENO);
        if (!error) error = posix_spawn_file_actions_adddup2(&actions, channel[1], STDOUT_FILENO);
        if (!error) error = posix_spawn_file_actions_adddup2(&actions, channel[1], STDERR_FILENO);
        if (!error) error = posix_spawn(&pid, burrow, &actions, NULL, args, environment);
        posix_spawn_file_actions_destroy(&actions);
    }
    close(channel[1]);
    if (error) {
        close(channel[0]);
        snprintf(output, sizeof(output), "spawn: %s (%d)", strerror(error), error);
        goto done;
    }
    size_t used = 0;
    long long deadline = now_ms() + 8000;
    int status = 0;
    int finished = 0;
    while (now_ms() < deadline && used < sizeof(output) - 96) {
        struct pollfd pfd = {.fd = channel[0], .events = POLLIN};
        int ready = poll(&pfd, 1, 100);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) break;
        if (ready) {
            ssize_t n = read(channel[0], output + used, sizeof(output) - 96 - used);
            if (n > 0) { /* SELinux labels may end in NUL. */
                for (ssize_t i = 0; i < n; i++) if (!output[used+i]) output[used+i] = '\n';
                used += n;
            } else if (!n) {
                if (waitpid(pid, &status, WNOHANG) == pid) { finished = 1; break; }
                usleep(10000);
            } else if (errno != EINTR) break;
        }
    }
    close(channel[0]);
    if (!finished) {
        kill(pid, SIGTERM); /* Burrow supervisor cleans up the guest. */
        for (int i = 0; i < 50; ++i) {
            if (waitpid(pid, &status, WNOHANG) == pid) { finished = 1; break; }
            usleep(10000);
        }
        if (!finished) { kill(pid, SIGKILL); while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {} }
        snprintf(output + used, sizeof(output) - used, "\nprobe_timeout_or_io_error");
    } else if (WIFEXITED(status)) {
        snprintf(output + used, sizeof(output) - used, "\nguest_exit=%d", WEXITSTATUS(status));
    } else {
        snprintf(output + used, sizeof(output) - used, "\nguest_signal=%d", WTERMSIG(status));
    }
done:
    (*env)->ReleaseStringUTFChars(env, owner_path, path);
    (*env)->ReleaseStringUTFChars(env, directory, dir);
    return (*env)->NewStringUTF(env, output);
}

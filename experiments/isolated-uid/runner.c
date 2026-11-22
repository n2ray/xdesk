#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

/* Only the kernel-supplied credentials for the expected one-byte record count. */
static int receive_credentials(int fd, int flags, char record, struct ucred *sender) {
    char byte;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(*sender))]; } control;
    struct iovec iov = { .iov_base = &byte, .iov_len = 1 };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes,
                          .msg_controllen = sizeof(control.bytes) };
    ssize_t n = recvmsg(fd, &msg, flags);
    struct cmsghdr *c = n == 1 ? CMSG_FIRSTHDR(&msg) : NULL;
    if (!c || byte != record || (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC))
            || c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_CREDENTIALS
            || c->cmsg_len != CMSG_LEN(sizeof(*sender))) return 0;
    memcpy(sender, CMSG_DATA(c), sizeof(*sender));
    return sender->pid > 1;
}

JNIEXPORT jlong JNICALL Java_io_github_n2ray_xdesk_probe_HandshakeTest_registration(
        JNIEnv *env, jclass cls, jint fd, jbyte record) {
    (void)env;
    (void)cls;
    struct ucred sender;
    if (!receive_credentials(fd, 0, record, &sender)) return -1;
    return ((jlong)sender.pid << 32) | sender.uid;
}

static void socket_result(char *out, size_t size, int witness, pid_t supervisor, int status) {
    struct ucred sender;
    if (!receive_credentials(witness, MSG_DONTWAIT, 'R', &sender)) {
        snprintf(out, size, "\nsocket_exit=%d\nmissing_android_credentials", status);
        return;
    }
    int gone = sender.pid > 0 && kill(sender.pid, 0) < 0 && errno == ESRCH;
    int reaped = waitpid(supervisor, NULL, WNOHANG) < 0 && errno == ECHILD;
    snprintf(out, size, "\nsocket_exit=%d\nandroid_pid_uid=%d:%u\nlauncher_uid=%u\n"
            "supervisor_pid=%d\nguest_gone=%s\nsupervisor_wait_again=%s",
            status, sender.pid, sender.uid, getuid(), supervisor, gone ? "ESRCH" : "unexpected",
            reaped ? "ECHILD" : "unexpected");
}

/* stdin is the read-only marker or the granted socket endpoint. stdout and
 * stderr report observations independently of the socket under test. */
static jstring run_probe(
        JNIEnv *env, jstring directory, jint marker, jstring owner_path, jboolean dynamic, jboolean rootfs, jint socket_role, int registration, const char *expected) {
    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!dir) return NULL;
    const char *path = owner_path ? (*env)->GetStringUTFChars(env, owner_path, NULL) : "";
    if (!path) { (*env)->ReleaseStringUTFChars(env, directory, dir); return NULL; }
    char burrow[4096], guest_bind[4096], file_bind[4096], output[4096] = "";
    snprintf(burrow, sizeof(burrow), "%s/libburrow.so", dir);
    snprintf(guest_bind, sizeof(guest_bind), "%s/%s", dir,
             dynamic ? "libubuntu_dash.so:/dash" : "libubuntu_busybox.so:/busybox");
    snprintf(file_bind, sizeof(file_bind), "%s:/owner-marker", path);
    char lib_bind[4096], ld_bind[4096], libc_bind[4096];
    snprintf(lib_bind, sizeof(lib_bind), "%s:/lib", dir);
    snprintf(ld_bind, sizeof(ld_bind), "%s/libubuntu_ld.so:/lib/ld-linux-aarch64.so.1", dir);
    snprintf(libc_bind, sizeof(libc_bind), "%s/libubuntu_libc.so:/lib/libc.so.6", dir);
    const char *marker_script =
        "IFS= read -r token || exit 20; printf 'via_fd=%s\\n' \"$token\"; "
        "if IFS= read -r other < /owner-marker; then printf 'via_path=%s\\n' \"$other\"; "
        "else echo via_path=DENIED; fi; ";
    const char *image_checks =
        "cd -P /etc/xdesk || exit 34; "
        "entries=$(/usr/bin/ls -1A .) && [ \"$entries\" = image ] || exit 35; "
        "echo xdesk_entries=$entries; "
        "where=$(/usr/bin/pwd -P) && [ \"$where\" = /etc/xdesk ] || exit 36; "
        "IFS= read -r relative < image && [ \"$relative\" = 'ubuntu-26.04-r1 2026-11-22' ] || exit 37; "
        "echo cwd=$where relative_image=$relative; "
        "cd -P .. && [ -L os-release ] || exit 38; "
        "link=$(/usr/bin/readlink os-release) && [ \"$link\" = ../usr/lib/os-release ] || exit 39; "
        ". ./os-release && [ \"$VERSION_ID\" = 26.04 ] || exit 40; echo relative_link=$link; "
        "link=$(/usr/bin/readlink localtime) && [ \"$link\" = /usr/share/zoneinfo/Etc/UTC ] "
        "&& /usr/bin/cmp -s localtime \"$link\" || exit 41; echo absolute_link=$link; "
        "cd -P /bin && [ \"$(/usr/bin/pwd -P)\" = /usr/bin ] || exit 42; "
        "set -- *; [ \"$#\" = 671 ] || exit 43; echo bin_entries=$#; "
        "[ \"$(./sh -c '/usr/bin/pwd -P')\" = /usr/bin ] || exit 44; echo exec_cwd=/usr/bin; "
        "cd / || exit 45; "
        "IFS= read -r image < /etc/xdesk/image || exit 30; echo rootfs_image=$image; "
        ". /etc/os-release || exit 31; echo rootfs_version=$VERSION_ID; "
        "if ( : >> /etc/xdesk/image ); then exit 32; fi; echo image_write=DENIED; "
        "if ( : > /etc/xdesk/.isolated-uid-write-test ); then exit 33; fi; echo image_create=DENIED; ";
    char script[4096];
    snprintf(script, sizeof(script), "%s%s%s", rootfs ? image_checks : "", marker_script, dynamic ?
        "domain=; IFS= read -r domain < /domain || :; printf '%s\\n' \"$domain\"; "
        "while read -r range perms offset dev ino path; do "
        "case $perms in *x*) if [ \"$ino\" != 0 ]; then "
        "printf 'exec_map=%s %s %s %s\\n' \"$dev\" \"$ino\" \"$perms\" \"$path\"; fi;; esac; "
        "done < /self-maps" : "/busybox cat /domain");
    char *static_args[] = {burrow, "-r", (char *)dir, "-b", guest_bind, "-b", file_bind,
        "-b", "/proc/self/attr/current:/domain", "-w", "/", "--", "/busybox", "sh", "-c", script, NULL};
    char *dyn_args[] = {burrow, "-r", (char *)dir, "-b", guest_bind, "-b", file_bind,
        "-b", "/proc/self/attr/current:/domain", "-b", "/proc/self/maps:/self-maps",
        "-b", lib_bind, "-b", ld_bind, "-b", libc_bind, "-w", "/", "--", "/dash", "-c", script, NULL};
    char *root_args[] = {burrow, "-r", (char *)dir, "-b", file_bind,
        "-b", "/proc/self/attr/current:/domain", "-b", "/proc/self/maps:/self-maps",
        "-w", "/", "--", "/bin/dash", "-c", script, NULL};
    char **args = rootfs ? root_args : dynamic ? dyn_args : static_args;
    char *environment[] = {"LC_ALL=C", "PATH=/", dynamic && !rootfs ? "LD_LIBRARY_PATH=/lib" : NULL, NULL};
    char script_bind[4096];
    snprintf(script_bind, sizeof(script_bind), "%s/libsocket_peer.so:/socket-peer.py", dir);
    char *socket_args[] = {burrow, "-r", (char *)dir, "-b", file_bind, "-b", script_bind,
        "-w", "/", "--", "/usr/bin/python3", "-I", "-S", "-B", "/socket-peer.py",
        socket_role == 1 ? "ordinary" : "isolated", NULL};
    char handshake_bind[4096];
    snprintf(handshake_bind, sizeof(handshake_bind), "%s/libzygote_probe.so:/handshake.py", dir);
    char fork_bind[4096];
    snprintf(fork_bind, sizeof(fork_bind), "%s/librenderer_fork.so:/renderer-fork.py", dir);
    char mojo_bind[4096];
    char *handshake_args[24] = {burrow, "-r", (char *)dir, "-b", handshake_bind, "-b", fork_bind};
    size_t next_arg = 7;
    if (expected && strncmp(expected, "mojo:", 5) == 0) {
        snprintf(mojo_bind, sizeof(mojo_bind), "%s/libmojo_guest.so:/mojo-guest", dir);
        handshake_args[next_arg++] = "-b";
        handshake_args[next_arg++] = mojo_bind;
    }
    char *guest_args[] = {"-w", "/", "--", "/usr/bin/python3", "-I", "-S", "-B", "/handshake.py",
        registration >= 0 ? "send" : "receive", (char *)expected, NULL};
    memcpy(handshake_args + next_arg, guest_args, sizeof(guest_args));
    int witness[2] = {-1, -1};
    if (socket_role) {
        int enabled = 1;
        if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, witness) < 0 ||
                setsockopt(witness[0], SOL_SOCKET, SO_PASSCRED, &enabled, sizeof(enabled)) < 0) {
            snprintf(output, sizeof(output), "observer: %s", strerror(errno));
            goto done;
        }
        args = socket_role == 3 ? handshake_args : socket_args;
    }
    size_t used = 0;
    if (dynamic) {
        const char *names[] = {"libubuntu_dash.so", "libubuntu_ld.so", "libubuntu_libc.so"};
        for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
            char installed[4096];
            struct stat st;
            snprintf(installed, sizeof(installed), "%s/%s", dir, names[i]);
            if (stat(installed, &st) < 0) {
                snprintf(output, sizeof(output), "stat %s: %s", names[i], strerror(errno));
                goto done;
            }
            used += snprintf(output + used, sizeof(output) - used, "installed=%s %02x:%02x %llu\n",
                    names[i], (unsigned)major(st.st_dev), (unsigned)minor(st.st_dev),
                    (unsigned long long)st.st_ino);
        }
    }
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
        if (!error && socket_role) error = posix_spawn_file_actions_adddup2(&actions, witness[1], 3);
        if (!error && registration >= 0) error = posix_spawn_file_actions_adddup2(&actions, registration, 4);
        if (!error) error = posix_spawn(&pid, burrow, &actions, NULL, args, environment);
        posix_spawn_file_actions_destroy(&actions);
    }
    close(channel[1]);
    if (error) {
        close(channel[0]);
        snprintf(output, sizeof(output), "spawn: %s (%d)", strerror(error), error);
        goto done;
    }
    long long deadline = now_ms() + (socket_role ? 15000 : 8000);
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
        if (socket_role)
            socket_result(output + used, sizeof(output) - used, witness[0], pid, WEXITSTATUS(status));
        else
            snprintf(output + used, sizeof(output) - used, "\nguest_exit=%d", WEXITSTATUS(status));
    } else {
        snprintf(output + used, sizeof(output) - used, "\nguest_signal=%d", WTERMSIG(status));
    }
done:
    if (witness[0] >= 0) close(witness[0]);
    if (witness[1] >= 0) close(witness[1]);
    if (owner_path) (*env)->ReleaseStringUTFChars(env, owner_path, path);
    (*env)->ReleaseStringUTFChars(env, directory, dir);
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jstring JNICALL Java_io_github_n2ray_xdesk_probe_ProbeService_runBurrow(
        JNIEnv *env, jclass cls, jstring directory, jint marker, jstring owner_path,
        jboolean dynamic, jboolean rootfs, jint socket_role) {
    (void)cls;
    return run_probe(env, directory, marker, owner_path, dynamic, rootfs, socket_role, -1, NULL);
}

JNIEXPORT jstring JNICALL Java_io_github_n2ray_xdesk_probe_ProbeService_runHandshake(
        JNIEnv *env, jclass cls, jstring directory, jint endpoint, jint registration, jstring policy) {
    (void)cls;
    const char *expected = policy ? (*env)->GetStringUTFChars(env, policy, NULL) : NULL;
    if (policy && !expected) return NULL;
    jstring result = run_probe(env, directory, endpoint, NULL, JNI_FALSE, JNI_TRUE, 3, registration, expected);
    if (policy) (*env)->ReleaseStringUTFChars(env, policy, expected);
    return result;
}

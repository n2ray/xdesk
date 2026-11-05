// Read a mouse and keyboard from evdev. Ctrl+Alt+M toggles an exclusive grab; while grabbed, events go to stdout as text.
// Runs as the adb shell user (input group). Grabs end by themselves when the process exits. Exits after 30 minutes.
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define MAXD 8
static struct pollfd fds[MAXD];
static int n = 0, grabbed = 0, ctrl = 0, alt = 0, pending = 0;

static void set_grab(int on) {
    for (int i = 0; i < n; i++) if (ioctl(fds[i].fd, EVIOCGRAB, on) < 0) fprintf(stderr, "grab %d failed\n", i);
    grabbed = on;
    fprintf(stderr, "grabbed=%d\n", on);
    printf("G %d 0\n", on); fflush(stdout);
}

int main(int argc, char **argv) {
    const char *want = argc > 1 ? argv[1] : "Logitech";
    DIR *d = opendir("/dev/input");
    if (!d) { perror("opendir"); return 1; }
    struct dirent *e;
    while ((e = readdir(d)) && n < MAXD) {
        if (strncmp(e->d_name, "event", 5)) continue;
        char p[64], name[128] = "";
        snprintf(p, sizeof p, "/dev/input/%s", e->d_name);
        int fd = open(p, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        ioctl(fd, EVIOCGNAME(sizeof name), name);
        if (!strstr(name, want)) { close(fd); continue; }
        fprintf(stderr, "using %s: %s\n", p, name);
        fds[n].fd = fd; fds[n].events = POLLIN; n++;
    }
    closedir(d);
    if (!n) { fprintf(stderr, "no device matches %s\n", want); return 1; }
    time_t end = time(NULL) + 30 * 60;
    while (time(NULL) < end) {
        if (poll(fds, n, 1000) <= 0) continue;
        for (int i = 0; i < n; i++) {
            if (!(fds[i].revents & POLLIN)) continue;
            struct input_event ev;
            while (read(fds[i].fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
                if (ev.type == EV_KEY) {
                    if (ev.code == KEY_LEFTCTRL || ev.code == KEY_RIGHTCTRL) ctrl = ev.value != 0;
                    if (ev.code == KEY_LEFTALT || ev.code == KEY_RIGHTALT) alt = ev.value != 0;
                    if (ev.code == KEY_M && ev.value == 1 && ctrl && alt) {
                        if (grabbed) { printf("R 0 0\n"); fflush(stdout); }
                        set_grab(!grabbed);
                        pending = grabbed;
                        continue;
                    }
                }
                if (pending) {
                    /* Keep the hotkey's own Ctrl and Alt away from the remote, then clear them once both are up. */
                    if (ev.type == EV_KEY && (ev.code == KEY_LEFTCTRL || ev.code == KEY_RIGHTCTRL || ev.code == KEY_LEFTALT || ev.code == KEY_RIGHTALT || ev.code == KEY_M)) {
                        if (!ctrl && !alt) { pending = 0; printf("C 0 0\n"); fflush(stdout); }
                        continue;
                    }
                }
                if (grabbed && (ev.type == EV_KEY || ev.type == EV_REL || ev.type == EV_SYN)) {
                    if (printf("%d %d %d\n", ev.type, ev.code, ev.value) < 0) return 0;
                    if (ev.type == EV_SYN && fflush(stdout)) return 0;
                }
            }
        }
    }
    return 0;
}

// Host regression test: cc -std=c11 frame-permit.c -o frame-permit && ./frame-permit
#define _DEFAULT_SOURCE
#include "../src/main/cpp/lorie/frame-permit.h"
#include <assert.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

static void signal_peer(int fd) {
    char tick = 1;
    assert(write(fd, &tick, 1) == 1);
}

static void wait_peer(int fd) {
    char tick;
    assert(read(fd, &tick, 1) == 1);
}

int main(void) {
    volatile uint8_t *spent = mmap(NULL, 1, PROT_READ | PROT_WRITE,
                                   MAP_SHARED | MAP_ANON, -1, 0);
    assert(spent != MAP_FAILED);
    int drawing[2], granted[2];
    assert(!pipe(drawing) && !pipe(granted));
    pid_t renderer = fork();
    assert(renderer >= 0);
    if (renderer == 0) {
        close(drawing[0]);
        close(granted[1]);
        for (int i = 0; i < 1000; i++) {
            assert(lorieClaimFrame(spent));
            assert(!lorieClaimFrame(spent));
            signal_peer(drawing[1]);  // Previous frame still rendering.
            wait_peer(granted[0]);   // Two ticks arrive before the fence completes.
            assert(lorieFrameAvailable(spent));
            assert(lorieClaimFrame(spent));
            assert(!lorieClaimFrame(spent));  // Draw once, without replaying stale ticks.
            signal_peer(drawing[1]);
            wait_peer(granted[0]);
        }
        _exit(0);
    }
    close(drawing[1]);
    close(granted[0]);
    for (int i = 0; i < 1000; i++) {
        wait_peer(drawing[0]);
        lorieGrantFrame(spent);
        lorieGrantFrame(spent);
        signal_peer(granted[1]);
        wait_peer(drawing[0]);
        lorieGrantFrame(spent);
        signal_peer(granted[1]);
    }
    int status;
    assert(waitpid(renderer, &status, 0) == renderer);
    return !(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

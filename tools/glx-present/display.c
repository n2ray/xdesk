#define _GNU_SOURCE
#include <android/hardware_buffer.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#define CHECKPLANE(x)                                                          \
  do {                                                                         \
    if (!(x)) {                                                                \
      fprintf(stderr, "failed at %d: %s (errno %d)\n", __LINE__, #x, errno);   \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
// Match the CPU plane mapping to an open descriptor by device and inode. Reject
// anonymous staging, split planes, short allocations and ambiguous layouts.
static int plane_fd(void *pixels, size_t span, uint32_t *offset) {
  uintptr_t p = (uintptr_t)pixels, lo, hi;
  unsigned major_no, minor_no;
  uint64_t map_offset, ino;
  char line[1024];
  FILE *maps = fopen("/proc/self/maps", "re");
  CHECKPLANE(maps);
  while (fgets(line, sizeof line, maps)) {
    if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %*s %" SCNx64 " %x:%x %" SCNu64,
               &lo, &hi, &map_offset, &major_no, &minor_no, &ino) != 6 ||
        !ino || p < lo || p >= hi || span > hi - p || map_offset > UINT32_MAX ||
        p - lo > UINT32_MAX - map_offset)
      continue;
    *offset = map_offset + (p - lo);
    DIR *fds = opendir("/proc/self/fd");
    CHECKPLANE(fds);
    struct dirent *ent;
    int fd = -1;
    while ((ent = readdir(fds))) {
      char *end;
      long n = strtol(ent->d_name, &end, 10);
      struct stat st;
      if (*end || n < 0 || fstat(n, &st) || st.st_ino != ino ||
          major(st.st_dev) != major_no || minor(st.st_dev) != minor_no ||
          st.st_size < 0 || (uint64_t)st.st_size < span + *offset)
        continue;
      fd = fcntl(n, F_DUPFD_CLOEXEC, 3);
      if (fd >= 0)
        break;
    }
    closedir(fds);
    fclose(maps);
    return fd;
  }
  fclose(maps);
  return -1;
}
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <android/surface_control.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "Damage3D", __VA_ARGS__)
static int readall(int fd, void *p, size_t n) {
  return recv(fd, p, n, MSG_WAITALL) == (ssize_t)n;
}
#include <linux/sync_file.h>
#include <poll.h>
#include <sys/ioctl.h>
struct tick {
  int seq, fd;
  int64_t latch;
};
static int reports[2];
static void *collect(void *dummy) {
  (void)dummy;
  struct tick v;
  while (read(reports[0], &v, sizeof(v)) == sizeof(v)) {
    uint64_t stamp = 0;
    if (v.fd >= 0) {
      struct pollfd p = {v.fd, POLLIN, 0};
      struct sync_file_info info = {0};
      if (poll(&p, 1, 200) > 0 && !ioctl(v.fd, SYNC_IOC_FILE_INFO, &info) &&
          info.num_fences > 0 && info.num_fences <= 32) {
        struct sync_fence_info f[32] = {0};
        info.sync_fence_info = (uintptr_t)f;
        if (!ioctl(v.fd, SYNC_IOC_FILE_INFO, &info))
          for (unsigned j = 0; j < info.num_fences; j++)
            if (f[j].status == 1 && f[j].timestamp_ns > stamp)
              stamp = f[j].timestamp_ns;
      }
      close(v.fd);
    }
    LOG("tick %d %lld %llu", v.seq, (long long)v.latch,
        (unsigned long long)stamp);
  }
  return NULL;
}
static void moved(void *id, ASurfaceTransactionStats *stats) {
  struct tick v = {(int)(uintptr_t)id,
                   ASurfaceTransactionStats_getPresentFenceFd(stats),
                   ASurfaceTransactionStats_getLatchTime(stats)};
  if (write(reports[1], &v, sizeof(v)) != sizeof(v) && v.fd >= 0)
    close(v.fd);
}

#define KEY "c9bd08b56536d8ceba2f849787dd103b"
#include <dlfcn.h>
#include <stdatomic.h>
#include <time.h>
struct slot {
  AHardwareBuffer *buf;
  atomic_int ready;
  int fence;
};
static void released(void *ptr, int fence) {
  struct slot *s = ptr;
  s->fence = fence;
  atomic_store(&s->ready, 1);
}
static int64_t now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (int64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static int put(int s, void *v, int n, int fd) {
  struct iovec io = {v, n};
  char anc[CMSG_SPACE(sizeof(int))] = {0};
  struct msghdr m = {.msg_iov = &io, .msg_iovlen = 1};
  if (fd >= 0) {
    m.msg_control = anc;
    m.msg_controllen = sizeof(anc);
    struct cmsghdr *c = CMSG_FIRSTHDR(&m);
    c->cmsg_level = SOL_SOCKET;
    c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
  }
  return sendmsg(s, &m, MSG_NOSIGNAL) == n;
}
static int get(int s, void *v, int n, int *fd) {
  struct iovec io = {v, n};
  char anc[CMSG_SPACE(sizeof(int))] = {0};
  struct msghdr m = {.msg_iov = &io,
                     .msg_iovlen = 1,
                     .msg_control = anc,
                     .msg_controllen = sizeof(anc)};
  if (recvmsg(s, &m, MSG_CMSG_CLOEXEC) != n ||
      (m.msg_flags & (MSG_TRUNC | MSG_CTRUNC)))
    return 0;
  *fd = -1;
  struct cmsghdr *c = CMSG_FIRSTHDR(&m);
  if (c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS &&
      c->cmsg_len == CMSG_LEN(sizeof(int)))
    memcpy(fd, CMSG_DATA(c), sizeof(int));
  return 1;
}
#define TRY(x)                                                                 \
  do {                                                                         \
    if (!(x)) {                                                                \
      LOG("FAIL line=%d errno=%d %s", __LINE__, errno, #x);                    \
      goto end;                                                                \
    }                                                                          \
  } while (0)
static void *worker(void *arg) {
  ANativeWindow *win = arg;
  ASurfaceControl *layer =
      ASurfaceControl_createFromWindow(win, "Ubuntu GPU shared cube");
  void (*set_buffer)(ASurfaceTransaction *, ASurfaceControl *,
                     AHardwareBuffer *, int, void *, void (*)(void *, int)) =
      (void *)dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setBufferWithRelease");
  pthread_t waiter;
  int fd = -1;
  TRY(set_buffer);
  TRY(layer && !pipe(reports) && !pthread_create(&waiter, NULL, collect, NULL));
  pthread_detach(waiter);
  fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
  TRY(fd >= 0);
  struct sockaddr_un a = {.sun_family = AF_UNIX};
  strcpy(a.sun_path, "/data/data/io.github.n2ray.xdesk.x11/files/linux/rootfs/"
                     "tmp/xdesk-inner-cube.sock");
  int rc = -1;
  for (int i = 0; i < 220 && rc; i++) {
    rc = connect(fd, (void *)&a,
                 offsetof(struct sockaddr_un, sun_path) + strlen(a.sun_path) +
                     1);
    if (rc)
      usleep(100000);
  }
  TRY(!rc);
  struct ucred peer;
  socklen_t peerbytes = sizeof(peer);
  TRY(!getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peerbytes) &&
      peer.uid == getuid());
  struct timeval tv = {.tv_sec = 24};
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  char auth[sizeof(KEY)];
  TRY(readall(fd, auth, sizeof(KEY) - 1) &&
      !memcmp(auth, KEY, sizeof(KEY) - 1));
  for (int phase = 1; phase <= 4; phase++) {
    int w = phase <= 2 ? 640 : 2560, h = phase <= 2 ? 480 : 1600,
        copy = phase % 2 == 1;
    struct slot *slots = calloc(3, sizeof(*slots));
    TRY(slots);
    int head[] = {phase, w, h, copy};
    TRY(put(fd, head, sizeof(head), -1));
    unsigned char *scratch = copy ? malloc(65536) : NULL;
    TRY(!copy || scratch);
    int pitch[3];
    for (int k = 0; k < 3; k++) {
      AHardwareBuffer_Desc d = {.width = w,
                                .height = h,
                                .layers = 1,
                                .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                                .usage =
                                    AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                                    AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                    AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
                                    AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                                    AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN};
      TRY(!AHardwareBuffer_allocate(&d, &slots[k].buf));
      void *px;
      int32_t bpp, row;
      uint32_t offset;
      TRY(!AHardwareBuffer_lockAndGetInfo(slots[k].buf,
                                          AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                                          -1, NULL, &px, &bpp, &row));
      TRY(bpp == 4 && row >= 4 * w);
      int plane = plane_fd(px, (size_t)row * (h - 1) + 4 * w, &offset);
      TRY(!AHardwareBuffer_unlock(slots[k].buf, NULL) && plane >= 0);
      uint32_t meta[] = {w, h, 0x34324241, row, offset, 0, 0};
      TRY(put(fd, meta, sizeof(meta), plane));
      close(plane);
      pitch[k] = row;
      LOG("layout phase=%d slot=%d width=%d height=%d pitch=%d offset=%u",
          phase, k, w, h, row, offset);
      slots[k].ready = 1;
      slots[k].fence = -1;
    }
    int seq = 0;
    int64_t start = now(), next = start;
    while (now() - start < 10000000000LL) {
      int k = seq % 3;
      int64_t deadline = now() + 2000000000;
      while (!atomic_load(&slots[k].ready) && now() < deadline)
        usleep(250);
      TRY(atomic_load(&slots[k].ready));
      slots[k].ready = 0;
      if (slots[k].fence >= 0) {
        struct pollfd f = {slots[k].fence, POLLIN, 0};
        TRY(poll(&f, 1, 2000) > 0);
        close(slots[k].fence);
        slots[k].fence = -1;
      }
      int msg[] = {k, seq};
      TRY(put(fd, msg, sizeof(msg), -1));
      char ack;
      int acquire;
      TRY(get(fd, &ack, 1, &acquire) && ack == (copy ? 'C' : 'F'));
      if (copy) {
        TRY(acquire == -1);
        void *dst;
        TRY(!AHardwareBuffer_lock(slots[k].buf,
                                  AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                                  NULL, &dst));
        int n = w * h * 4;
        for (int off = 0; off < n;) {
          int bytes = n - off > 65536 ? 65536 : n - off;
          if (pitch[k] == w * 4) {
            TRY(readall(fd, (char *)dst + off, bytes));
          } else {
            TRY(readall(fd, scratch, bytes));
            int consumed = 0;
            while (consumed < bytes) {
              int position = off + consumed, row = position / (w * 4),
                  col = position % (w * 4);
              int part = w * 4 - col;
              if (part > bytes - consumed)
                part = bytes - consumed;
              memcpy((char *)dst + (size_t)row * pitch[k] + col,
                     scratch + consumed, part);
              consumed += part;
            }
          }
          off += bytes;
        }
        TRY(!AHardwareBuffer_unlock(slots[k].buf, &acquire));
      } else
        TRY(acquire >= 0);
      ASurfaceTransaction *tx = ASurfaceTransaction_create();
      ARect src = {0, 0, w, h}, dst = {30, 110, 990, 110 + 960 * h / w};
      ASurfaceTransaction_setGeometry(tx, layer, &src, &dst, 0);
      ASurfaceTransaction_setVisibility(tx, layer,
                                        ASURFACE_TRANSACTION_VISIBILITY_SHOW);
      set_buffer(tx, layer, slots[k].buf, acquire, &slots[k], released);
      ASurfaceTransaction_setOnComplete(
          tx, (void *)(uintptr_t)(phase * 10000 + seq), moved);
      ASurfaceTransaction_apply(tx);
      ASurfaceTransaction_delete(tx);
      seq++;
      next += 16666667;
      if (next < now())
        next = now();
      int64_t left = next - now();
      if (left > 1000)
        usleep(left / 1000);
    }
    int last[] = {-1, seq};
    TRY(put(fd, last, sizeof(last), -1));
    LOG("phase %d submits %d duration_ns %lld", phase, seq,
        (long long)(now() - start));
    // SurfaceControl retains submitted buffers. Keep callback contexts valid
    // until process exit.
    free(scratch);
    for (int k = 0; k < 3; k++)
      AHardwareBuffer_release(slots[k].buf);
  }
  LOG("PASS");
end:
  if (fd >= 0)
    close(fd);
  if (layer)
    ASurfaceControl_release(layer);
  ANativeWindow_release(win);
  return NULL;
}
static void draw(ANativeActivity *a, ANativeWindow *w) {
  (void)a;
  ANativeWindow_acquire(w);
  pthread_t t;
  if (!pthread_create(&t, NULL, worker, w))
    pthread_detach(t);
  else
    ANativeWindow_release(w);
}
void ANativeActivity_onCreate(ANativeActivity *a, void *state, size_t n) {
  (void)state;
  (void)n;
  a->callbacks->onNativeWindowCreated = draw;
}

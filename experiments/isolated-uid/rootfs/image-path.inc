/* Included by path.c only in the experiment build. The generated table and
 * every backing file are part of the installed, signed APK. */
enum image_kind { IMG_DIR, IMG_LINK, IMG_FILE };
struct image_node { const char *path; enum image_kind kind; const char *value; const char *backing; unsigned width; };
static const struct image_node image_nodes[] = {
#include "image-nodes.inc"
};

static const struct image_node *image_find(const char *path) {
    long lo = 0, hi = sizeof(image_nodes) / sizeof(image_nodes[0]);
    while (lo < hi) {
        long mid = lo + (hi - lo) / 2;
        const unsigned char *a = (const unsigned char *)path;
        const unsigned char *b = (const unsigned char *)image_nodes[mid].path;
        while (*a && *a == *b) { ++a; ++b; }
        if (*a == *b) return &image_nodes[mid];
        if (*a < *b) hi = mid;
        else lo = mid + 1;
    }
    return 0;
}

#define IMAGE_COUNT (sizeof(image_nodes) / sizeof(image_nodes[0]))

static long image_host(const char *path, char *host) {
    const struct image_node *node = image_find(path);
    if (!node) return -ENOENT;
    long n = join(host, cfg.root, cfg.root_length, "/");
    return n < 0 ? n : join(host, host, n, node->backing);
}

/* Metadata objects have unique installer-owned names. File descriptors can
 * thus identify directories across dup, fork and exec without an fd registry. */
static const struct image_node *metadata(const char *host) {
    if (!starts_with(host, cfg.root) || !starts_with(host + cfg.root_length, "/libnode")) return 0;
    const char *name = host + cfg.root_length + 1;
    const char *digits = name + 7;
    unsigned long id = parse_unsigned(&digits);
    if (id >= IMAGE_COUNT || !same(name, image_nodes[id].backing)) return 0;
    return &image_nodes[id];
}

static const struct image_node *fd_metadata(long fd) {
    char link[48] = "/proc/self/fd/", host[PATH_MAX];
    if (fd < 0) return 0;
    format_unsigned(link + 14, (unsigned long)fd);
    long n = sys(NR_readlinkat, AT_FDCWD, link, host, sizeof(host)-1);
    if (n < 0) return 0;
    host[n] = 0;
    return metadata(host);
}

const char *image_cwd(void) {
    unsigned long id = __atomic_load_n(&cfg.image_cwd, __ATOMIC_RELAXED);
    return id && id <= IMAGE_COUNT ? image_nodes[id-1].path : 0;
}

long image_chdir(const char *host, long fd) {
    const struct image_node *node = host ? metadata(host) : fd_metadata(fd);
    if (node && node->kind != IMG_DIR) return -ENOTDIR;
    long r = node ? sys(NR_chdir, cfg.root, 0, 0, 0) :
             host ? sys(NR_chdir, host, 0, 0, 0) : sys(NR_fchdir, fd, 0, 0, 0);
    if (r == 0) __atomic_store_n(&cfg.image_cwd, node ? (unsigned long)(node-image_nodes)+1 : 0, __ATOMIC_RELAXED);
    return r;
}

int image_type(const char *host, long fd, int mode) {
    const struct image_node *node = host ? metadata(host) : fd_metadata(fd);
    if (!node) return mode;
    return (mode & ~S_IFMT) | (node->kind == IMG_DIR ? S_IFDIR : S_IFLNK);
}

long image_readlink(const char *host, long fd, char *buf, long size) {
    const struct image_node *node = host ? metadata(host) : fd_metadata(fd);
    if (!node || node->kind != IMG_LINK) return -ENOSYS;
    long n = length_of(node->value);
    if (n > size) n = size;
    memcpy(buf, node->value, n);
    return n;
}

long image_open(const char *host, long flags) {
    const struct image_node *node = metadata(host);
    if (!node) return -ENOSYS;
    if (node->kind == IMG_LINK && !(flags & O_PATH)) return -ELOOP;
    if (node->kind != IMG_DIR && (flags & O_DIRECTORY)) return -ENOTDIR;
    if (!(flags & O_PATH) && (flags & (3 | O_CREAT | 01000 /* O_TRUNC */))) return -EISDIR;
    return sys(NR_openat, AT_FDCWD, host, flags & ~O_DIRECTORY, 0);
}

long image_getdents(long fd, char *buf, unsigned long size) {
    const struct image_node *node = fd_metadata(fd);
    if (!node || node->kind != IMG_DIR) return -ENOSYS;
    if (size < node->width) return -EINVAL;
    long n = sys(NR_read, fd, buf, size - size % node->width, 0);
    if (n < 0) return n;
    if (n % node->width) return -EINVAL;
    for (long pos = 0; pos < n; pos += node->width) {
        uint64_t id;
        memcpy(&id, buf + pos, sizeof(id));
        if (id >= IMAGE_COUNT) return -EINVAL;
        char host[PATH_MAX];
        struct kstat st;
        long r = image_host(image_nodes[id].path, host);
        if (r >= 0) r = sys(NR_newfstatat, AT_FDCWD, host, &st, 0);
        if (r < 0) return r;
        memcpy(buf + pos, &st.ino, sizeof(st.ino));
    }
    return n;
}

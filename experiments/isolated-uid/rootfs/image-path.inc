/* Included by path.c only in the experiment build. The generated table and
 * every backing file are part of the installed, signed APK. */
enum image_kind { IMG_DIR, IMG_LINK, IMG_FILE };
struct image_node { const char *path; enum image_kind kind; const char *value; };
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

static long image_host(const char *path, char *host) {
    const struct image_node *node = image_find(path);
    if (!node) return -ENOENT;
    if (node->kind == IMG_LINK) return -ELOOP;
    long n = copy(host, PATH_MAX, cfg.root);
    if (node->kind == IMG_DIR || n < 0) return n;
    return join(host, host, n, "/") < 0 ? -ENAMETOOLONG : join(host, host, n + 1, node->value);
}

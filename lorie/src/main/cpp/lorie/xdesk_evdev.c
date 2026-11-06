// Raw input device access for XDesk's injector process (com.termux.x11.Evdev, in lorie-app).
// Nothing else in this module uses it. Only works as the shell user, which is in the input group.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define CAP_MOUSE 1
#define CAP_KEYBOARD 2
#define MAX_DEVICES 32
#define STRIDE 4
#define TYPE_GONE (-1)
#define LONG_BITS (sizeof(unsigned long) * 8)
#define BIT_WORDS(max) ((max) / LONG_BITS + 1)

static int has_bit(const unsigned long *bits, int bit) {
    return (bits[bit / LONG_BITS] >> (bit % LONG_BITS)) & 1;
}

JNIEXPORT jint JNICALL Java_com_termux_x11_Evdev_open(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -ENOMEM;
    int fd = open(p, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd < 0 ? -err : fd;
}

JNIEXPORT void JNICALL Java_com_termux_x11_Evdev_close(JNIEnv *env, jclass cls, jint fd) {
    if (fd >= 0) close(fd);
}

// Found by what the device can do, never by name. Relative axes plus a left button is a mouse, letter keys are a
// keyboard. Touch screens, the phone's side buttons and sensors match neither, and the checks on the bus and on
// direct touch keep out built-in or software devices that claim more than they have.
JNIEXPORT jint JNICALL Java_com_termux_x11_Evdev_capabilities(JNIEnv *env, jclass cls, jint fd) {
    unsigned long ev[BIT_WORDS(EV_MAX)], keys[BIT_WORDS(KEY_MAX)], rel[BIT_WORDS(REL_MAX)], abs_axes[BIT_WORDS(ABS_MAX)], props[BIT_WORDS(INPUT_PROP_MAX)];
    struct input_id id;
    int caps = 0;

    memset(ev, 0, sizeof ev);
    memset(keys, 0, sizeof keys);
    memset(rel, 0, sizeof rel);
    memset(abs_axes, 0, sizeof abs_axes);
    memset(props, 0, sizeof props);
    if (ioctl(fd, EVIOCGBIT(0, sizeof ev), ev) < 0) return 0;
    if (ioctl(fd, EVIOCGID, &id) == 0) {
        switch (id.bustype) {
        case BUS_HOST:
        case BUS_I2C:
        case BUS_SPI:
        case BUS_VIRTUAL:
            return 0;
        }
    }
    if (ioctl(fd, EVIOCGPROP(sizeof props), props) >= 0 && has_bit(props, INPUT_PROP_DIRECT)) return 0;
    if (has_bit(ev, EV_ABS)) {
        ioctl(fd, EVIOCGBIT(EV_ABS, sizeof abs_axes), abs_axes);
        if (has_bit(abs_axes, ABS_MT_POSITION_X)) return 0;
    }
    if (has_bit(ev, EV_KEY)) ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keys), keys);
    if (has_bit(ev, EV_REL)) ioctl(fd, EVIOCGBIT(EV_REL, sizeof rel), rel);
    if (has_bit(ev, EV_REL) && has_bit(rel, REL_X) && has_bit(rel, REL_Y) && has_bit(keys, BTN_LEFT)) caps |= CAP_MOUSE;
    if (has_bit(keys, KEY_A) && has_bit(keys, KEY_M) && has_bit(keys, KEY_Q) && has_bit(keys, KEY_Z) && has_bit(keys, KEY_SPACE)) caps |= CAP_KEYBOARD;
    return caps;
}

JNIEXPORT jstring JNICALL Java_com_termux_x11_Evdev_name(JNIEnv *env, jclass cls, jint fd) {
    char name[128] = "";
    ioctl(fd, EVIOCGNAME(sizeof name - 1), name);
    // NewStringUTF wants modified UTF-8, and a device can report anything
    for (char *c = name; *c; c++) if ((unsigned char) *c >= 0x80) *c = '?';
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jint JNICALL Java_com_termux_x11_Evdev_grab(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL Java_com_termux_x11_Evdev_poll(JNIEnv *env, jclass cls, jintArray fds, jint count, jintArray out, jint timeoutMs) {
    struct pollfd p[MAX_DEVICES];
    jint in[MAX_DEVICES];
    if (count < 0) count = 0;
    if (count > MAX_DEVICES) count = MAX_DEVICES;
    // Throws ArrayIndexOutOfBoundsException if fds is shorter than count, leaving in[] unset.
    (*env)->GetIntArrayRegion(env, fds, 0, count, in);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    for (int i = 0; i < count; i++) {
        p[i].fd = in[i];
        p[i].events = POLLIN;
        p[i].revents = 0;
    }
    int ready = poll(p, count, timeoutMs);
    if (ready < 0) return errno == EINTR ? 0 : -errno;
    if (ready == 0) return 0;

    jsize capacity = (*env)->GetArrayLength(env, out) / STRIDE;
    jint *o = (*env)->GetIntArrayElements(env, out, NULL);
    if (!o) return -ENOMEM;
    int n = 0;
    for (int i = 0; i < count && n < capacity; i++) {
        int gone = p[i].revents & (POLLERR | POLLHUP | POLLNVAL);
        if (p[i].revents & POLLIN) {
            struct input_event ev[32];
            while (n < capacity) {
                size_t room = (size_t) (capacity - n) < 32 ? (size_t) (capacity - n) : 32;
                ssize_t got = read(p[i].fd, ev, room * sizeof ev[0]);
                if (got < 0 && errno == EINTR) continue;
                if (got < 0 && errno == EAGAIN) break;
                if (got <= 0) {
                    gone = 1;
                    break;
                }
                for (size_t e = 0; e < (size_t) got / sizeof ev[0]; e++, n++) {
                    o[n * STRIDE] = i;
                    o[n * STRIDE + 1] = ev[e].type;
                    o[n * STRIDE + 2] = ev[e].code;
                    o[n * STRIDE + 3] = ev[e].value;
                }
            }
        }
        if (gone && n < capacity) {
            o[n * STRIDE] = i;
            o[n * STRIDE + 1] = TYPE_GONE;
            o[n * STRIDE + 2] = 0;
            o[n * STRIDE + 3] = 0;
            n++;
        }
    }
    (*env)->ReleaseIntArrayElements(env, out, o, 0);
    return n;
}

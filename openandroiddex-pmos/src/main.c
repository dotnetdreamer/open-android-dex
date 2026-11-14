/*
 * openandroiddex-pmos: the phone's half of the desktop on a postmarketOS phone.
 *
 * On Android the computer gets a screen of the phone's own, a virtual display,
 * through scrcpy. A phone running postmarketOS with KDE Plasma has no adb and
 * no scrcpy, but its compositor, KWin, can make the same thing: a virtual
 * output, a screen with nothing behind it that Plasma treats as a monitor
 * plugged in. This program asks KWin for one the size of the computer's
 * screen, encodes what Plasma draws on it as H.264, and puts the computer's
 * mouse and keyboard on it through KWin's fake input.
 *
 * The computer starts it over SSH (src-tauri/src/pmos.rs) and talks to it on
 * stdin and stdout, in the messages the app's desktop window already speaks
 * for the phone's Linux (UsbDesktop.tsx, LinuxStream.java): video and a few
 * JSON words out; pointer, keys and acknowledgements in. Each one is framed
 * as [kind u8][length u32 BE][payload], kind 0 being JSON text and 1 binary.
 *
 * KWin hands the virtual output and fake input only to a program whose own
 * desktop file asks for them (X-KDE-Wayland-Interfaces), which is why the app
 * installs one beside this binary, naming its exact path.
 *
 *   openandroiddex-pmos version
 *   openandroiddex-pmos probe
 *   openandroiddex-pmos serve --width W --height H --scale S [--cap C] [--encoder E] [--convert gl|cpu]
 *   openandroiddex-pmos snapshot --width W --height H --scale S --out F.png [--wait SECONDS]
 *
 * W and H are the screen's size in pixels (KWin makes its mode that size), S
 * the scale Plasma draws it at. After that the screen follows the window: its
 * size in the window's logical pixels, at scale S, at most C pixels on its
 * long side (W or H, whichever is longer, when C is not given).
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input-event-codes.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>

#include <gio/gio.h>
#include <glib-unix.h>
#include <glib.h>
#include <gst/app/gstappsink.h>
#include <gst/gst.h>
#include <gst/video/video.h>
#include <wayland-client.h>
#include <xkbcommon/xkbcommon.h>

#include "fake-input-client-protocol.h"
#include "xdg-output-unstable-v1-client-protocol.h"
#include "zkde-screencast-unstable-v1-client-protocol.h"

/* What `version` prints; pmos.rs compares the whole file's hash, not this. */
#define VERSION "1"

/*
 * Frames sent and not yet acknowledged before new ones stop going into the
 * encoder. On a cable the round trip is a millisecond or two, so this is a
 * backstop for a link that stalls, not a pace: the picture falls behind by at
 * most this many frames, and the next one in is the newest.
 */
#define MAX_INFLIGHT 4
/*
 * No word from the computer for this long and it is gone. A cable pulled
 * takes the USB network with it, and sshd here may not notice for hours; the
 * screen, and the phone's wake, must not outlive the computer by more than this.
 */
#define WATCHDOG_US (15 * G_USEC_PER_SEC)
/*
 * A wheel click from the window, as an axis value. libinput gives KWin 15 for
 * one notch of a real wheel; the window sends two clicks for one notch of the
 * computer's (100 px of its wheel, 50 a click: UsbDesktop.tsx), so each is half.
 */
#define WHEEL_STEP 7.5
/*
 * Repeat the last picture this often while nothing changes. KWin sends a
 * frame only when the screen is damaged, so without it a key frame asked for
 * on a still desktop would never come, and neither would the sharper second
 * encode of the last change.
 */
#define KEEPALIVE_MS 250

enum { KIND_TEXT = 0, KIND_BINARY = 1 };
enum { MSG_VIDEO = 0x01, IN_POINTER = 0x10, IN_KEY = 0x11, IN_ACK = 0x12 };

struct output {
    struct wl_output *wl;
    uint32_t global;
    struct zxdg_output_v1 *xdg;
    char *name;
    int32_t x, y;           /* wl_output geometry: where it sits in the compositor's space */
    int32_t lx, ly, lw, lh; /* xdg-output: its logical rectangle */
    gboolean logical;
    gboolean before; /* there before our screen was asked for */
};

static struct {
    GMainLoop *loop;
    int status;
    gboolean finishing;

    /* what the computer asked for */
    int width, height; /* the screen's first size, in pixels: the mode KWin makes it with */
    double scale;
    int cap; /* the most pixels the screen gets on its long side */
    char *name;
    char *forced_encoder;
    /* How the picture becomes the encoder's: on the GPU (gl) or the CPU (cpu). */
    char *convert;
    gboolean embed_cursor;

    struct wl_display *display;
    struct wl_registry *registry;
    struct zkde_screencast_unstable_v1 *screencast;
    uint32_t screencast_version;
    struct org_kde_kwin_fake_input *fake;
    uint32_t fake_version;
    struct zxdg_output_manager_v1 *xdg_manager;
    GList *outputs;
    GString *globals;

    struct zkde_screencast_stream_unstable_v1 *stream;
    gboolean created, closed;
    uint32_t node;
    guint64 serial;
    gboolean has_serial;
    char *stream_error;

    GstElement *pipeline, *appsink, *encoder_el;
    const char *encoder;
    gboolean hw;
    int bitrate; /* bit/s */
    gint sampled;
    gint pw, ph; /* the frames' size, in pixels */
    GPtrArray *candidates;
    guint candidate;

    gint attached;
    gint acks;
    gint inflight;
    gint64 last_ack;
    gint need_config;
    gint frames, bytes;

    guint8 buttons;
    /* keysym → the evdev code pressed for it, +1; 0 + 1 for one KWin typed by keysym */
    GHashTable *keys_down;

    struct wl_seat *seat;
    struct wl_keyboard *keyboard;
    struct xkb_context *xkb;
    struct xkb_keymap *keymap;
    /* keysym → (evdev code << 8 | the shift and level three it needs) + 1, on the phone's first layout */
    GHashTable *sym_keys;
    guint shift_code, level3_code;

    GMutex out_lock;
    gboolean out_broken;
    GByteArray *in;
    gint64 last_rx;

    GDBusConnection *bus;

    /* The screen following its window (see apply_size). */
    char *out_name;         /* the screen's name, as kscreen-doctor knows it */
    int asked_w, asked_h;   /* the size the window's last word came to, in pixels */
    GThread *sizer;         /* kscreen-doctor takes a moment: not on the main loop */
    GMutex size_lock;
    GCond size_cond;
    int next_w, next_h;     /* for the sizer; 0 while there is nothing to do */
    gboolean size_quit;
    gint custom;            /* custom modes may be on the screen: put back before it closes */
} app;

static void say(const char *fmt, ...) G_GNUC_PRINTF(1, 2);
static void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("openandroiddex-pmos: ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
}

static void finish(int status) {
    if (app.finishing) return;
    app.finishing = TRUE;
    app.status = status;
    if (app.loop) g_main_loop_quit(app.loop);
}

/* ── the computer's end: framed messages on stdout ─────────────────────── */

static gboolean write_all(int fd, struct iovec *iov, int n) {
    while (n > 0) {
        ssize_t w = writev(fd, iov, n);
        if (w < 0) {
            if (errno == EINTR) continue;
            return FALSE;
        }
        while (n > 0 && (size_t)w >= iov->iov_len) {
            w -= iov->iov_len;
            iov++;
            n--;
        }
        if (n > 0) {
            iov->iov_base = (char *)iov->iov_base + w;
            iov->iov_len -= w;
        }
    }
    return TRUE;
}

static gboolean on_out_broken(gpointer d) {
    say("the computer's end closed");
    finish(0);
    return G_SOURCE_REMOVE;
}

/* Any thread: the video comes from GStreamer's, the rest from the main loop's. */
static void send_frame(guint8 kind, const void *a, gsize alen, const void *b, gsize blen) {
    guint32 len = (guint32)(alen + blen);
    guint8 head[5] = {kind, len >> 24, len >> 16, len >> 8, len};
    struct iovec iov[3] = {{head, 5}, {(void *)a, alen}, {(void *)b, blen}};
    g_mutex_lock(&app.out_lock);
    if (!app.out_broken && !write_all(STDOUT_FILENO, iov, 3)) {
        app.out_broken = TRUE;
        g_idle_add(on_out_broken, NULL);
    }
    g_mutex_unlock(&app.out_lock);
}

static void send_text(const char *json) { send_frame(KIND_TEXT, json, strlen(json), NULL, 0); }

static void json_string(GString *s, const char *v) {
    g_string_append_c(s, '"');
    for (const unsigned char *p = (const unsigned char *)(v ? v : ""); *p; p++) {
        if (*p == '"' || *p == '\\') g_string_append_printf(s, "\\%c", *p);
        else if (*p < 0x20) g_string_append_printf(s, "\\u%04x", *p);
        else g_string_append_c(s, *p);
    }
    g_string_append_c(s, '"');
}

/* {"t":"state","state":…,"message":…}: what the window's cover says. */
static void send_state(const char *state, const char *message) {
    GString *s = g_string_new("{\"t\":\"state\",\"state\":");
    json_string(s, state);
    if (message) {
        g_string_append(s, ",\"message\":");
        json_string(s, message);
    }
    g_string_append_c(s, '}');
    send_text(s->str);
    g_string_free(s, TRUE);
}

/*
 * The value of "key" in a flat JSON object the window wrote: a number, a bare
 * word or a string without escapes. That is every message the window sends
 * this program; "clip", the one with free text in it, is not taken here.
 */
static gboolean json_raw(const char *json, const char *key, char *out, size_t cap) {
    char pat[64];
    g_snprintf(pat, sizeof pat, "\"%s\":", key);
    const char *p = strstr(json, pat);
    if (!p) return FALSE;
    p += strlen(pat);
    while (*p == ' ') p++;
    size_t n = 0;
    if (*p == '"') {
        for (p++; *p && *p != '"' && n + 1 < cap; p++) out[n++] = *p;
    } else {
        for (; *p && *p != ',' && *p != '}' && *p != ' ' && n + 1 < cap; p++) out[n++] = *p;
    }
    out[n] = 0;
    return TRUE;
}

/* ── the session: which Wayland, which bus ─────────────────────────────── */

static char *read_file(const char *path, gsize *len) {
    char *data = NULL;
    if (!g_file_get_contents(path, &data, len, NULL)) return NULL;
    return data;
}

static const char *environ_get(const char *env, gsize len, const char *key) {
    gsize klen = strlen(key);
    for (const char *p = env; p < env + len; p += strlen(p) + 1) {
        if (strncmp(p, key, klen) == 0 && p[klen] == '=') return p + klen + 1;
    }
    return NULL;
}

/*
 * Over SSH this runs outside the phone's graphical session, so its
 * environment says nothing about it. The session's own programs know: take
 * the Wayland socket, the runtime directory and the session bus from the
 * first of them that is this user's, Plasma's shell before anything else.
 */
static void adopt_session(void) {
    if (getenv("WAYLAND_DISPLAY") && getenv("XDG_RUNTIME_DIR") && getenv("DBUS_SESSION_BUS_ADDRESS")) return;
    static const char *const prefer[] = {"plasmashell", "kded6", "ksmserver", "kwin_wayland", NULL};
    char *best = NULL;
    gsize best_len = 0;
    int best_rank = 1000;
    DIR *d = opendir("/proc");
    struct dirent *e;
    while (d && (e = readdir(d))) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char path[64];
        struct stat st;
        g_snprintf(path, sizeof path, "/proc/%s", e->d_name);
        if (stat(path, &st) != 0 || st.st_uid != getuid()) continue;
        g_snprintf(path, sizeof path, "/proc/%s/comm", e->d_name);
        char *comm = read_file(path, NULL);
        if (!comm) continue;
        g_strstrip(comm);
        int rank = 100;
        for (int i = 0; prefer[i]; i++)
            if (strcmp(comm, prefer[i]) == 0) rank = i;
        g_free(comm);
        if (rank >= best_rank) continue;
        g_snprintf(path, sizeof path, "/proc/%s/environ", e->d_name);
        gsize len = 0;
        char *env = read_file(path, &len);
        if (!env || !environ_get(env, len, "WAYLAND_DISPLAY")) {
            g_free(env);
            continue;
        }
        g_free(best);
        best = env;
        best_len = len;
        best_rank = rank;
    }
    if (d) closedir(d);
    static const char *const keys[] = {"WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "DISPLAY",
                                       "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", NULL};
    if (best) {
        for (int i = 0; keys[i]; i++) {
            const char *v = environ_get(best, best_len, keys[i]);
            if (v && !getenv(keys[i])) setenv(keys[i], v, 1);
        }
        g_free(best);
    }
    if (!getenv("XDG_RUNTIME_DIR")) {
        char *dir = g_strdup_printf("/run/user/%u", (unsigned)getuid());
        setenv("XDG_RUNTIME_DIR", dir, 1);
        g_free(dir);
    }
    if (!getenv("WAYLAND_DISPLAY")) setenv("WAYLAND_DISPLAY", "wayland-0", 1);
    if (!getenv("DBUS_SESSION_BUS_ADDRESS")) {
        char *bus = g_strdup_printf("unix:path=%s/bus", getenv("XDG_RUNTIME_DIR"));
        setenv("DBUS_SESSION_BUS_ADDRESS", bus, 1);
        g_free(bus);
    }
}

/* ── Wayland ───────────────────────────────────────────────────────────── */

static void output_geometry(void *data, struct wl_output *wl, int32_t x, int32_t y, int32_t pw, int32_t ph,
                            int32_t subpixel, const char *make, const char *model, int32_t transform) {
    struct output *o = data;
    o->x = x;
    o->y = y;
}
static void output_mode(void *data, struct wl_output *wl, uint32_t flags, int32_t w, int32_t h, int32_t r) {}
static void output_done(void *data, struct wl_output *wl) {}
static void output_scale(void *data, struct wl_output *wl, int32_t factor) {}
static void output_name(void *data, struct wl_output *wl, const char *name) {
    struct output *o = data;
    g_free(o->name);
    o->name = g_strdup(name);
}
static void output_description(void *data, struct wl_output *wl, const char *description) {}
static const struct wl_output_listener output_listener = {
    .geometry = output_geometry,
    .mode = output_mode,
    .done = output_done,
    .scale = output_scale,
    .name = output_name,
    .description = output_description,
};

static void xdg_position(void *data, struct zxdg_output_v1 *x, int32_t lx, int32_t ly) {
    struct output *o = data;
    o->lx = lx;
    o->ly = ly;
}
static void xdg_size(void *data, struct zxdg_output_v1 *x, int32_t w, int32_t h) {
    struct output *o = data;
    o->lw = w;
    o->lh = h;
    o->logical = TRUE;
}
static void xdg_done(void *data, struct zxdg_output_v1 *x) {}
static void xdg_name(void *data, struct zxdg_output_v1 *x, const char *name) {
    struct output *o = data;
    if (!o->name) o->name = g_strdup(name);
}
static void xdg_description(void *data, struct zxdg_output_v1 *x, const char *description) {}
static const struct zxdg_output_v1_listener xdg_listener = {
    .logical_position = xdg_position,
    .logical_size = xdg_size,
    .done = xdg_done,
    .name = xdg_name,
    .description = xdg_description,
};

/* ── the phone's keyboard layout ───────────────────────────────────────── */

enum { NEED_SHIFT = 1, NEED_LEVEL3 = 2 };

static guint32 keymap_lookup(xkb_keysym_t sym) {
    gpointer v = app.sym_keys ? g_hash_table_lookup(app.sym_keys, GUINT_TO_POINTER(sym)) : NULL;
    return v ? GPOINTER_TO_UINT(v) - 1 : G_MAXUINT32;
}

/*
 * Which key makes each keysym on the phone's layout, and with which of Shift
 * and level three (AltGr): the reverse of the keymap, simplest key first.
 */
static void index_keymap(void) {
    struct xkb_keymap *km = app.keymap;
    if (!app.sym_keys) app.sym_keys = g_hash_table_new(NULL, NULL);
    g_hash_table_remove_all(app.sym_keys);
    xkb_mod_mask_t shift = 0, level3 = 0;
    xkb_mod_index_t i;
    if ((i = xkb_keymap_mod_get_index(km, XKB_MOD_NAME_SHIFT)) != XKB_MOD_INVALID && i < 32) shift |= 1u << i;
    if ((i = xkb_keymap_mod_get_index(km, "Mod5")) != XKB_MOD_INVALID && i < 32) level3 |= 1u << i;
    if ((i = xkb_keymap_mod_get_index(km, "LevelThree")) != XKB_MOD_INVALID && i < 32) level3 |= 1u << i;
    for (xkb_keycode_t kc = xkb_keymap_min_keycode(km); kc <= xkb_keymap_max_keycode(km); kc++) {
        if (kc < 8) continue;
        xkb_level_index_t levels = xkb_keymap_num_levels_for_key(km, kc, 0);
        for (xkb_level_index_t lv = 0; lv < levels; lv++) {
            const xkb_keysym_t *syms;
            if (xkb_keymap_key_get_syms_by_level(km, kc, 0, lv, &syms) != 1) continue;
            xkb_mod_mask_t masks[16];
            size_t n = xkb_keymap_key_get_mods_for_level(km, kc, 0, lv, masks, G_N_ELEMENTS(masks));
            guint need = G_MAXUINT;
            for (size_t m = 0; m < n; m++) {
                xkb_mod_mask_t left = masks[m];
                guint mine = 0;
                if (left & shift) mine |= NEED_SHIFT;
                if (left & level3) mine |= NEED_LEVEL3;
                left &= ~(shift | level3);
                if (left) continue; /* Caps Lock, Num Lock or Control: not a way to type it */
                if (need == G_MAXUINT || __builtin_popcount(mine) < __builtin_popcount(need)) need = mine;
            }
            if (need == G_MAXUINT) continue;
            guint32 packed = ((guint32)(kc - 8) << 8) | need;
            guint32 had = keymap_lookup(syms[0]);
            if (had != G_MAXUINT32 && __builtin_popcount(had & 0xff) <= __builtin_popcount(need)) continue;
            g_hash_table_insert(app.sym_keys, GUINT_TO_POINTER(syms[0]), GUINT_TO_POINTER(packed + 1));
        }
    }
    guint32 s = keymap_lookup(XKB_KEY_Shift_L);
    app.shift_code = s != G_MAXUINT32 ? s >> 8 : KEY_LEFTSHIFT;
    guint32 l3 = keymap_lookup(XKB_KEY_ISO_Level3_Shift);
    app.level3_code = l3 != G_MAXUINT32 ? l3 >> 8 : 0;
    say("keyboard: %u keysyms on the phone's layout", g_hash_table_size(app.sym_keys));
}

static void kb_keymap(void *d, struct wl_keyboard *kb, uint32_t format, int32_t fd, uint32_t size) {
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 || size == 0) {
        close(fd);
        return;
    }
    char *map = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return;
    if (!app.xkb) app.xkb = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *km =
        xkb_keymap_new_from_buffer(app.xkb, map, strnlen(map, size), XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
    munmap(map, size);
    if (!km) {
        say("keyboard: the phone's keymap would not compile");
        return;
    }
    if (app.keymap) xkb_keymap_unref(app.keymap);
    app.keymap = km;
    index_keymap();
}
static void kb_enter(void *d, struct wl_keyboard *kb, uint32_t serial, struct wl_surface *s, struct wl_array *keys) {}
static void kb_leave(void *d, struct wl_keyboard *kb, uint32_t serial, struct wl_surface *s) {}
static void kb_key(void *d, struct wl_keyboard *kb, uint32_t serial, uint32_t time, uint32_t key, uint32_t state) {}
static void kb_modifiers(void *d, struct wl_keyboard *kb, uint32_t serial, uint32_t dep, uint32_t lat, uint32_t lock,
                         uint32_t group) {}
static void kb_repeat(void *d, struct wl_keyboard *kb, int32_t rate, int32_t delay) {}
static const struct wl_keyboard_listener keyboard_listener = {
    .keymap = kb_keymap,
    .enter = kb_enter,
    .leave = kb_leave,
    .key = kb_key,
    .modifiers = kb_modifiers,
    .repeat_info = kb_repeat,
};

static void seat_capabilities(void *d, struct wl_seat *seat, uint32_t caps) {
    if ((caps & WL_SEAT_CAPABILITY_KEYBOARD) && !app.keyboard) {
        app.keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(app.keyboard, &keyboard_listener, NULL);
    }
}
static void seat_name(void *d, struct wl_seat *seat, const char *name) {}
static const struct wl_seat_listener seat_listener = {.capabilities = seat_capabilities, .name = seat_name};

static void attach_xdg(struct output *o) {
    if (!app.xdg_manager || o->xdg) return;
    o->xdg = zxdg_output_manager_v1_get_xdg_output(app.xdg_manager, o->wl);
    zxdg_output_v1_add_listener(o->xdg, &xdg_listener, o);
}

static void output_free(struct output *o) {
    if (o->xdg) zxdg_output_v1_destroy(o->xdg);
    if (wl_output_get_version(o->wl) >= WL_OUTPUT_RELEASE_SINCE_VERSION) wl_output_release(o->wl);
    else wl_output_destroy(o->wl);
    g_free(o->name);
    g_free(o);
}

static void global_add(void *data, struct wl_registry *r, uint32_t name, const char *iface, uint32_t version) {
    g_string_append_printf(app.globals, "%s%s:%u", app.globals->len ? "," : "", iface, version);
    if (strcmp(iface, zkde_screencast_unstable_v1_interface.name) == 0) {
        app.screencast_version = MIN(version, (uint32_t)zkde_screencast_unstable_v1_interface.version);
        app.screencast = wl_registry_bind(r, name, &zkde_screencast_unstable_v1_interface, app.screencast_version);
    } else if (strcmp(iface, org_kde_kwin_fake_input_interface.name) == 0) {
        app.fake_version = MIN(version, (uint32_t)org_kde_kwin_fake_input_interface.version);
        app.fake = wl_registry_bind(r, name, &org_kde_kwin_fake_input_interface, app.fake_version);
        org_kde_kwin_fake_input_authenticate(app.fake, "Open Android DeX",
                                             "Use this phone's desktop from a computer");
    } else if (strcmp(iface, wl_seat_interface.name) == 0 && !app.seat) {
        /* Only for its keymap: the phone's layout, which keys are found in. */
        app.seat = wl_registry_bind(r, name, &wl_seat_interface, MIN(version, 5u));
        wl_seat_add_listener(app.seat, &seat_listener, NULL);
    } else if (strcmp(iface, zxdg_output_manager_v1_interface.name) == 0) {
        app.xdg_manager = wl_registry_bind(r, name, &zxdg_output_manager_v1_interface, MIN(version, 3u));
        for (GList *l = app.outputs; l; l = l->next) attach_xdg(l->data);
    } else if (strcmp(iface, wl_output_interface.name) == 0) {
        struct output *o = g_new0(struct output, 1);
        o->global = name;
        o->before = app.stream == NULL;
        o->wl = wl_registry_bind(r, name, &wl_output_interface, MIN(version, 4u));
        wl_output_add_listener(o->wl, &output_listener, o);
        attach_xdg(o);
        app.outputs = g_list_append(app.outputs, o);
    }
}

static void global_remove(void *data, struct wl_registry *r, uint32_t name) {
    for (GList *l = app.outputs; l; l = l->next) {
        struct output *o = l->data;
        if (o->global == name) {
            app.outputs = g_list_delete_link(app.outputs, l);
            output_free(o);
            return;
        }
    }
}

static const struct wl_registry_listener registry_listener = {global_add, global_remove};

static void stream_closed(void *d, struct zkde_screencast_stream_unstable_v1 *s) {
    app.closed = TRUE;
    if (app.loop && !app.finishing) {
        say("KWin closed the screen");
        send_state("stopped", "The phone closed the desktop's screen.");
        finish(0);
    }
}
static void stream_created(void *d, struct zkde_screencast_stream_unstable_v1 *s, uint32_t node) {
    app.node = node;
    app.created = TRUE;
}
static void stream_failed(void *d, struct zkde_screencast_stream_unstable_v1 *s, const char *error) {
    g_free(app.stream_error);
    app.stream_error = g_strdup(error && *error ? error : "KWin would not make the screen");
}
static void stream_serial(void *d, struct zkde_screencast_stream_unstable_v1 *s, uint32_t hi, uint32_t lo) {
    app.serial = ((guint64)hi << 32) | lo;
    app.has_serial = TRUE;
}
static const struct zkde_screencast_stream_unstable_v1_listener stream_listener = {
    .closed = stream_closed,
    .created = stream_created,
    .failed = stream_failed,
    .serial = stream_serial,
};

/* Dispatch whatever arrives within `ms`; -1 when the connection has gone. */
static int dispatch_for(int ms) {
    while (wl_display_prepare_read(app.display) != 0) {
        if (wl_display_dispatch_pending(app.display) < 0) return -1;
    }
    if (wl_display_flush(app.display) < 0 && errno != EAGAIN) {
        wl_display_cancel_read(app.display);
        return -1;
    }
    struct pollfd p = {wl_display_get_fd(app.display), POLLIN, 0};
    int r = poll(&p, 1, ms);
    if (r > 0) {
        if (wl_display_read_events(app.display) < 0) return -1;
    } else {
        wl_display_cancel_read(app.display);
    }
    return wl_display_dispatch_pending(app.display);
}

static gboolean wayland_connect(GString *why) {
    app.globals = g_string_new(NULL);
    app.display = wl_display_connect(NULL);
    if (!app.display) {
        g_string_printf(why, "Couldn't reach the phone's desktop (Wayland %s in %s).", getenv("WAYLAND_DISPLAY"),
                        getenv("XDG_RUNTIME_DIR"));
        return FALSE;
    }
    app.registry = wl_display_get_registry(app.display);
    wl_registry_add_listener(app.registry, &registry_listener, NULL);
    /* Globals, then what each one bound says about itself (outputs' names). */
    wl_display_roundtrip(app.display);
    wl_display_roundtrip(app.display);
    return TRUE;
}

/* The output KWin made for us: by its name, else the one that came after. */
static struct output *our_output(void) {
    for (GList *l = app.outputs; l; l = l->next) {
        struct output *o = l->data;
        if (o->name && app.name && strstr(o->name, app.name)) return o;
    }
    for (GList *l = app.outputs; l; l = l->next) {
        struct output *o = l->data;
        if (!o->before) return o;
    }
    return NULL;
}

/* Ask KWin for the screen, and wait for its PipeWire stream. */
static gboolean make_screen(GString *why) {
    if (!app.screencast) {
        g_string_assign(why, "The phone's desktop didn't let Open Android DeX make a screen. "
                             "It needs KDE Plasma, and the permission file Open Android DeX puts on the phone.");
        return FALSE;
    }
    if (app.screencast_version < 2) {
        g_string_assign(why, "The phone's KDE Plasma is too old to make a screen for a computer.");
        return FALSE;
    }
    uint32_t pointer = app.embed_cursor ? ZKDE_SCREENCAST_UNSTABLE_V1_POINTER_EMBEDDED
                                        : ZKDE_SCREENCAST_UNSTABLE_V1_POINTER_HIDDEN;
    wl_fixed_t scale = wl_fixed_from_double(app.scale);
    if (app.screencast_version >= 4) {
        app.stream = zkde_screencast_unstable_v1_stream_virtual_output_with_description(
            app.screencast, app.name, "Open Android DeX: a computer's screen", app.width, app.height, scale, pointer);
    } else {
        app.stream = zkde_screencast_unstable_v1_stream_virtual_output(app.screencast, app.name, app.width,
                                                                       app.height, scale, pointer);
    }
    zkde_screencast_stream_unstable_v1_add_listener(app.stream, &stream_listener, NULL);
    gint64 deadline = g_get_monotonic_time() + 10 * G_USEC_PER_SEC;
    while (!app.created && !app.stream_error && !app.closed && g_get_monotonic_time() < deadline) {
        if (dispatch_for(200) < 0) {
            g_string_assign(why, "The phone's desktop went away while the screen was being made.");
            return FALSE;
        }
    }
    if (!app.created) {
        g_string_printf(why, "The phone couldn't make a screen for the desktop: %s",
                        app.stream_error ? app.stream_error : "KWin didn't answer.");
        return FALSE;
    }
    /* The new output's own globals, and its name and place. */
    wl_display_roundtrip(app.display);
    wl_display_roundtrip(app.display);
    struct output *o = our_output();
    say("screen %s: %dx%d pixels at scale %.3f, PipeWire node %u%s", o && o->name ? o->name : "(unnamed)",
        app.width, app.height, app.scale, app.node, app.has_serial ? " (with serial)" : "");
    return TRUE;
}

/* The scale the screen has now: its pixels over its logical size. */
static double scale_now(void) {
    struct output *o = our_output();
    return o && o->logical && o->lw > 0 ? (double)app.width / o->lw : 0;
}

/*
 * kscreen-doctor with `args` (settings, or -o), its stdout in `out` when
 * asked for. Any thread: it only waits for the program. Qt is told where it
 * is, since under SSH it would look for X first.
 */
static gboolean kscreen(const char *const *args, char **out) {
    GPtrArray *argv = g_ptr_array_new();
    g_ptr_array_add(argv, (gpointer) "kscreen-doctor");
    for (const char *const *a = args; *a; a++) g_ptr_array_add(argv, (gpointer)*a);
    g_ptr_array_add(argv, NULL);
    gchar **env = g_environ_setenv(g_get_environ(), "QT_QPA_PLATFORM", "wayland", TRUE);
    GError *err = NULL;
    gint status = 0;
    gchar *errout = NULL;
    gboolean ok = g_spawn_sync(NULL, (gchar **)argv->pdata, env,
                               G_SPAWN_SEARCH_PATH | (out ? 0 : G_SPAWN_STDOUT_TO_DEV_NULL), NULL, NULL, out,
                               &errout, &status, &err);
    if (!ok) {
        say("kscreen-doctor: %s", err ? err->message : "?");
        g_clear_error(&err);
    } else if (status != 0) {
        say("kscreen-doctor %s: %s", args[0], errout ? g_strstrip(errout) : "?");
        ok = FALSE;
    }
    g_free(errout);
    g_strfreev(env);
    g_ptr_array_free(argv, TRUE);
    return ok;
}

/*
 * KWin makes the screen at the size and scale asked for (the size is its
 * mode, in pixels), and then gives it the scale it remembers for a screen of
 * that name (kwinoutputconfig.json): the first computer's, for every computer
 * after. kscreen-doctor puts this computer's back, and KWin remembers that.
 */
static void keep_scale(void) {
    double have = scale_now();
    struct output *o = our_output();
    if (have <= 0 || !o || !o->name || fabs(have - app.scale) < 0.01) return;
    char setting[160];
    g_snprintf(setting, sizeof setting, "output.%s.scale.%.3f", o->name, app.scale);
    const char *const args[] = {setting, NULL};
    kscreen(args, NULL);
    gint64 deadline = g_get_monotonic_time() + 3 * G_USEC_PER_SEC;
    while (fabs(scale_now() - app.scale) >= 0.01 && g_get_monotonic_time() < deadline)
        if (dispatch_for(100) < 0) break;
    say("scale: KWin had %.3f for this screen, now %.3f", have, scale_now());
}

static void close_screen(void) {
    if (app.stream) {
        if (!app.closed) zkde_screencast_stream_unstable_v1_close(app.stream);
        else zkde_screencast_stream_unstable_v1_destroy(app.stream);
        app.stream = NULL;
    }
    if (app.display) wl_display_flush(app.display);
}

/* ── the screen following its window ───────────────────────────────────── */

/*
 * The window says its size ("resize", and in "hello"), in its own logical
 * pixels, as it does to the phone's Linux. Here that becomes the screen's
 * mode: that many logical pixels at the session's scale, so Plasma gets more
 * room as the window grows rather than a bigger picture of the same room, as
 * DeX does. KWin gives a virtual output a custom mode as it would a monitor
 * (kscreen-doctor's addCustomMode), its stream then carries frames of the new
 * size, the encoder starts over for them, and on_sample tells the window.
 *
 * KWin keeps custom modes, and the mode in use, in kwinoutputconfig.json
 * under the screen's name, and gives them back to the next screen of that
 * name: whatever computer opens it next would start at this window's last
 * size. So the screen goes back to the mode it was made with, and the custom
 * modes go, before it closes; and a session that ended without that is
 * tidied by the next one, as it starts.
 */

struct mode_info {
    int index, w, h;
};

/* kscreen-doctor colours its words even into a pipe. */
static void strip_escapes(char *s) {
    char *w = s;
    for (const char *r = s; *r; r++) {
        if (r[0] == '\x1b' && r[1] == '[') {
            r += 2;
            while (*r && !(*r >= '@' && *r <= '~')) r++;
            if (!*r) break;
            continue;
        }
        *w++ = *r;
    }
    *w = 0;
}

/*
 * The screen's custom modes as KWin has them now, by index, and the size of
 * the mode in use (0 × 0 if it could not be read). From kscreen-doctor -o:
 *   Output: 1 Virtual-OpenAndroidDeX …
 *       Modes:  1:1920x1040@60.00*!  2:1600x900@59.95
 *       Custom modes:
 *           0: 1600x900@59.95
 */
static GArray *screen_modes(int *cur_w, int *cur_h) {
    GArray *custom = g_array_new(FALSE, FALSE, sizeof(struct mode_info));
    *cur_w = *cur_h = 0;
    char *out = NULL;
    const char *const list[] = {"-o", NULL};
    if (!kscreen(list, &out) || !out) {
        g_free(out);
        return custom;
    }
    strip_escapes(out);
    gboolean ours = FALSE, in_custom = FALSE;
    gchar **lines = g_strsplit(out, "\n", -1);
    for (gchar **l = lines; *l; l++) {
        const char *t = *l;
        while (*t == ' ' || *t == '\t') t++;
        if (g_str_has_prefix(t, "Output:")) {
            char name[128] = "";
            ours = sscanf(t, "Output: %*d %127s", name) == 1 && strcmp(name, app.out_name) == 0;
            in_custom = FALSE;
            continue;
        }
        if (!ours) continue;
        if (g_str_has_prefix(t, "Modes:")) {
            for (const char *p = t + 6; (p = strchr(p, ':')); p++) {
                int w, h;
                char rate[32];
                if (sscanf(p + 1, "%dx%d@%31s", &w, &h, rate) == 3 && strchr(rate, '*')) {
                    *cur_w = w;
                    *cur_h = h;
                }
            }
            in_custom = FALSE;
        } else if (g_str_has_prefix(t, "Custom modes:")) {
            in_custom = TRUE;
        } else if (in_custom) {
            struct mode_info m;
            if (sscanf(t, "%d: %dx%d", &m.index, &m.w, &m.h) == 3) g_array_append_val(custom, m);
            else in_custom = FALSE;
        }
    }
    g_strfreev(lines);
    g_free(out);
    return custom;
}

/*
 * Make the screen w × h: its own first mode for that size, a custom one for
 * any other; and every other custom mode taken away, so that there is only
 * ever the one to put back. The sizer's thread, or the main one at the end.
 */
static gboolean apply_size(int w, int h) {
    gboolean first = w == app.width && h == app.height;
    int cur_w, cur_h;
    GArray *custom = screen_modes(&cur_w, &cur_h);
    gboolean have = FALSE;
    for (guint i = 0; i < custom->len; i++) {
        struct mode_info *m = &g_array_index(custom, struct mode_info, i);
        if (m->w == w && m->h == h) have = TRUE;
    }
    if (custom->len) g_atomic_int_set(&app.custom, 1);
    if (!first && !have) {
        char add[160];
        g_snprintf(add, sizeof add, "output.%s.addCustomMode.%d.%d.60000.full", app.out_name, w, h);
        const char *const args[] = {add, NULL};
        g_atomic_int_set(&app.custom, 1);
        if (!kscreen(args, NULL)) {
            say("size: KWin didn't take a %dx%d mode", w, h);
            g_array_free(custom, TRUE);
            return FALSE;
        }
        /* Where KWin put it in its list: the ones to take away are counted again. */
        g_array_free(custom, TRUE);
        custom = screen_modes(&cur_w, &cur_h);
    }
    GPtrArray *set = g_ptr_array_new_with_free_func(g_free);
    if (cur_w != w || cur_h != h) g_ptr_array_add(set, g_strdup_printf("output.%s.mode.%dx%d@60", app.out_name, w, h));
    guint left = 0;
    /* Highest index first: each one taken away moves the ones after it down.
     * The first size has a mode of its own, so none of them stays for it. */
    for (guint i = custom->len; i-- > 0;) {
        struct mode_info *m = &g_array_index(custom, struct mode_info, i);
        if (!first && m->w == w && m->h == h) left++;
        else g_ptr_array_add(set, g_strdup_printf("output.%s.removeCustomMode.%d", app.out_name, m->index));
    }
    gboolean ok = TRUE;
    if (set->len) {
        g_ptr_array_add(set, NULL);
        ok = kscreen((const char *const *)set->pdata, NULL);
        if (!ok) say("size: KWin didn't make the screen %dx%d", w, h);
    }
    if (ok && !left) g_atomic_int_set(&app.custom, 0);
    g_ptr_array_free(set, TRUE);
    g_array_free(custom, TRUE);
    return ok;
}

/* The sizer: one size at a time, the latest the window asked for. */
static gpointer sizer_main(gpointer d) {
    g_mutex_lock(&app.size_lock);
    while (!app.size_quit) {
        if (!app.next_w) {
            g_cond_wait(&app.size_cond, &app.size_lock);
            continue;
        }
        int w = app.next_w, h = app.next_h;
        app.next_w = app.next_h = 0;
        g_mutex_unlock(&app.size_lock);
        gint64 t0 = g_get_monotonic_time();
        if (apply_size(w, h)) say("size: %dx%d in %d ms", w, h, (int)((g_get_monotonic_time() - t0) / 1000));
        g_mutex_lock(&app.size_lock);
    }
    g_mutex_unlock(&app.size_lock);
    return NULL;
}

static void ask_size(int w, int h) {
    if (!app.sizer) return;
    g_mutex_lock(&app.size_lock);
    app.next_w = w;
    app.next_h = h;
    g_cond_signal(&app.size_cond);
    g_mutex_unlock(&app.size_lock);
}

static void start_sizer(void) {
    struct output *o = our_output();
    if (!o || !o->name) {
        say("size: the screen has no name to give kscreen-doctor, so it keeps its size");
        return;
    }
    app.out_name = g_strdup(o->name);
    app.asked_w = app.width;
    app.asked_h = app.height;
    app.sizer = g_thread_new("sizer", sizer_main, NULL);
    /* Left over from a session that ended without putting its mode back. */
    ask_size(app.width, app.height);
}

/* Before the screen closes: the mode it was made with, and no custom modes left behind. */
static void stop_sizer(void) {
    if (!app.sizer) return;
    g_mutex_lock(&app.size_lock);
    app.size_quit = TRUE;
    g_cond_signal(&app.size_cond);
    g_mutex_unlock(&app.size_lock);
    g_thread_join(app.sizer);
    app.sizer = NULL;
    if (g_atomic_int_get(&app.custom) && !app.closed && apply_size(app.width, app.height))
        say("size: back to %dx%d", app.width, app.height);
}

/* The window's size, in its logical pixels: the screen's, in pixels at the session's scale. */
static void on_resize(const char *json) {
    char v[32];
    if (!app.sizer || !json_raw(json, "w", v, sizeof v)) return;
    int w = atoi(v);
    if (!json_raw(json, "h", v, sizeof v)) return;
    int h = atoi(v);
    if (w < 100 || h < 100 || w > 16384 || h > 16384) return;
    double pw = w * app.scale, ph = h * app.scale;
    double k = app.cap / MAX(pw, ph);
    if (k < 1) {
        pw *= k;
        ph *= k;
    }
    /* Across, whole cells of eight (KWin's custom modes are CVT's, which rounds
     * to those); down, even, for the encoder's 4:2:0. */
    int sw = MAX(640, (int)lround(pw / 8) * 8);
    int sh = MAX(400, (int)lround(ph / 2) * 2);
    /* Within a few pixels of the first size is the first size, its own mode. */
    if (abs(sw - app.width) <= 8 && abs(sh - app.height) <= 8) {
        sw = app.width;
        sh = app.height;
    }
    if (sw == app.asked_w && sh == app.asked_h) return;
    app.asked_w = sw;
    app.asked_h = sh;
    say("size: the window is %dx%d, so the screen is %dx%d", w, h, sw, sh);
    ask_size(sw, sh);
}

/* ── input ─────────────────────────────────────────────────────────────── */

static gboolean to_global(int x, int y, double *gx, double *gy) {
    struct output *o = our_output();
    int pw = g_atomic_int_get(&app.pw), ph = g_atomic_int_get(&app.ph);
    if (!o || pw <= 0 || ph <= 0) return FALSE;
    if (o->logical && o->lw > 0 && o->lh > 0) {
        *gx = o->lx + (x + 0.5) * o->lw / pw;
        *gy = o->ly + (y + 0.5) * o->lh / ph;
    } else {
        *gx = o->x + (x + 0.5) / app.scale;
        *gy = o->y + (y + 0.5) / app.scale;
    }
    return TRUE;
}

static void axis(uint32_t which, double value) {
    org_kde_kwin_fake_input_axis(app.fake, which, wl_fixed_from_double(value));
}

/* RFB's button mask, as the window sends it: 1 left, 2 middle, 4 right, 8/16 wheel up/down, 32/64 left/right, 128 back. */
static void pointer(guint8 mask, int x, int y) {
    if (!app.fake) return;
    double gx, gy;
    if (to_global(x, y, &gx, &gy))
        org_kde_kwin_fake_input_pointer_motion_absolute(app.fake, wl_fixed_from_double(gx), wl_fixed_from_double(gy));
    static const struct {
        guint8 bit;
        uint32_t code;
    } buttons[] = {{1, BTN_LEFT}, {2, BTN_MIDDLE}, {4, BTN_RIGHT}, {128, BTN_SIDE}};
    guint8 changed = mask ^ app.buttons;
    for (gsize i = 0; i < G_N_ELEMENTS(buttons); i++) {
        if (changed & buttons[i].bit)
            org_kde_kwin_fake_input_button(app.fake, buttons[i].code,
                                           (mask & buttons[i].bit) ? WL_POINTER_BUTTON_STATE_PRESSED
                                                                   : WL_POINTER_BUTTON_STATE_RELEASED);
    }
    guint8 pressed = changed & mask;
    if (pressed & 8) axis(WL_POINTER_AXIS_VERTICAL_SCROLL, -WHEEL_STEP);
    if (pressed & 16) axis(WL_POINTER_AXIS_VERTICAL_SCROLL, WHEEL_STEP);
    if (pressed & 32) axis(WL_POINTER_AXIS_HORIZONTAL_SCROLL, -WHEEL_STEP);
    if (pressed & 64) axis(WL_POINTER_AXIS_HORIZONTAL_SCROLL, WHEEL_STEP);
    app.buttons = mask;
}

/* ── keys ──────────────────────────────────────────────────────────────── */

enum { HELD_SHIFT = 1, HELD_CTRL = 2, HELD_ALT = 4, HELD_SUPER = 8, HELD_LEVEL3 = 16 };

static guint held_bit(guint32 keysym) {
    switch (keysym) {
    case XKB_KEY_Shift_L:
    case XKB_KEY_Shift_R: return HELD_SHIFT;
    case XKB_KEY_Control_L:
    case XKB_KEY_Control_R: return HELD_CTRL;
    case XKB_KEY_Alt_L:
    case XKB_KEY_Alt_R:
    case XKB_KEY_Meta_L:
    case XKB_KEY_Meta_R: return HELD_ALT;
    case XKB_KEY_Super_L:
    case XKB_KEY_Super_R: return HELD_SUPER;
    case XKB_KEY_ISO_Level3_Shift:
    case XKB_KEY_Mode_switch: return HELD_LEVEL3;
    default: return 0;
    }
}

/* A modifier the phone's layout may not have where the computer's has it. */
static guint modifier_code(guint32 keysym) {
    switch (keysym) {
    case XKB_KEY_Shift_L: return KEY_LEFTSHIFT;
    case XKB_KEY_Shift_R: return KEY_RIGHTSHIFT;
    case XKB_KEY_Control_L: return KEY_LEFTCTRL;
    case XKB_KEY_Control_R: return KEY_RIGHTCTRL;
    case XKB_KEY_Alt_L:
    case XKB_KEY_Meta_L: return KEY_LEFTALT;
    case XKB_KEY_Alt_R:
    case XKB_KEY_Meta_R:
    case XKB_KEY_ISO_Level3_Shift:
    case XKB_KEY_Mode_switch: return KEY_RIGHTALT;
    case XKB_KEY_Super_L: return KEY_LEFTMETA;
    case XKB_KEY_Super_R: return KEY_RIGHTMETA;
    default: return 0;
    }
}

/* The computer's own modifiers, as held now; with `codes`, the keys holding them. */
static guint held_now(guint bits, GArray *codes) {
    guint held = 0;
    GHashTableIter it;
    gpointer k, v;
    g_hash_table_iter_init(&it, app.keys_down);
    while (g_hash_table_iter_next(&it, &k, &v)) {
        guint bit = held_bit(GPOINTER_TO_UINT(k));
        held |= bit;
        if (codes && (bit & bits)) {
            guint code = GPOINTER_TO_UINT(v) - 1;
            if (code) g_array_append_val(codes, code);
        }
    }
    return held;
}

static void press(guint code, gboolean down) {
    org_kde_kwin_fake_input_keyboard_key(app.fake, code, down ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
}

/*
 * One press of a key that types `keysym`, with the modifiers its place on the
 * phone's layout needs, and only for the press: Shift added for a capital,
 * the computer's own Shift held back for a character that is unshifted here,
 * AltGr the same. Xvnc does the same for the phone's Linux, which is what the
 * window's keysyms were written for. Ctrl, Alt or Super held is a shortcut,
 * whose Shift is the person's and stays; except that Windows makes AltGr out
 * of Ctrl and Alt, so with AltGr held a character is typed, not a shortcut.
 */
static void type_press(guint code, guint need, gboolean printable) {
    guint held = held_now(0, NULL);
    gboolean typing = printable && (held & HELD_LEVEL3);
    gboolean chord = !typing && (held & (HELD_CTRL | HELD_ALT | HELD_SUPER));
    GArray *lift = g_array_new(FALSE, FALSE, sizeof(guint)); /* the person's, let go of and put back */
    GArray *add = g_array_new(FALSE, FALSE, sizeof(guint));  /* ours, pressed and let go of */
    if (typing) held_now(HELD_CTRL | HELD_ALT | HELD_LEVEL3, lift);
    if ((need & NEED_SHIFT) && !(held & HELD_SHIFT)) g_array_append_val(add, app.shift_code);
    if (!(need & NEED_SHIFT) && (held & HELD_SHIFT) && !chord) held_now(HELD_SHIFT, lift);
    if ((need & NEED_LEVEL3) && app.level3_code && (typing || !(held & HELD_LEVEL3))) g_array_append_val(add, app.level3_code);
    for (guint i = 0; i < lift->len; i++) press(g_array_index(lift, guint, i), FALSE);
    for (guint i = 0; i < add->len; i++) press(g_array_index(add, guint, i), TRUE);
    press(code, TRUE);
    for (guint i = add->len; i > 0; i--) press(g_array_index(add, guint, i - 1), FALSE);
    for (guint i = 0; i < lift->len; i++) press(g_array_index(lift, guint, i), TRUE);
    g_array_free(lift, TRUE);
    g_array_free(add, TRUE);
}

/*
 * Keys come as X keysyms: the character the computer's keyboard typed, or the
 * key's place with Ctrl held (the window's keysym.ts). They are found on the
 * phone's own layout and pressed there as keys. A key held down on the
 * computer repeats there, and each repeat is a new press here, modifiers and
 * all: a phone app repeating the key by itself would type the repeats without
 * the Shift that was only there for the press.
 */
static void key(gboolean down, guint32 keysym) {
    if (!app.fake || app.fake_version < 4) return;
    gpointer was = g_hash_table_lookup(app.keys_down, GUINT_TO_POINTER(keysym));
    if (!down) {
        if (!was) return;
        guint code = GPOINTER_TO_UINT(was) - 1;
        if (code) press(code, FALSE);
        else if (app.fake_version >= 6) org_kde_kwin_fake_input_keyboard_keysym(app.fake, keysym, 0);
        g_hash_table_remove(app.keys_down, GUINT_TO_POINTER(keysym));
        return;
    }
    guint mod = modifier_code(keysym);
    if (mod) {
        if (was) return;
        guint32 found = keymap_lookup(keysym);
        guint code = found != G_MAXUINT32 && keysym != XKB_KEY_ISO_Level3_Shift ? found >> 8 : mod;
        if (keysym == XKB_KEY_ISO_Level3_Shift && app.level3_code) code = app.level3_code;
        press(code, TRUE);
        g_hash_table_insert(app.keys_down, GUINT_TO_POINTER(keysym), GUINT_TO_POINTER(code + 1));
        return;
    }
    guint32 found = keymap_lookup(keysym);
    if (found == G_MAXUINT32 && (keysym & 0xff000000u) == 0x01000000u) found = keymap_lookup(xkb_utf32_to_keysym(keysym & 0xffffff));
    if (found == G_MAXUINT32) {
        /* Not on the phone's layout: KWin's own keysym typing is the last try. */
        if (app.fake_version < 6) return;
        if (was) org_kde_kwin_fake_input_keyboard_keysym(app.fake, keysym, 0);
        org_kde_kwin_fake_input_keyboard_keysym(app.fake, keysym, 1);
        g_hash_table_insert(app.keys_down, GUINT_TO_POINTER(keysym), GUINT_TO_POINTER(1));
        return;
    }
    guint code = found >> 8;
    if (was) press(code, FALSE);
    gboolean printable = keysym < 0xff00 || (keysym >= 0x01000000u && keysym < 0x01110000u);
    type_press(code, found & 0xff, printable);
    g_hash_table_insert(app.keys_down, GUINT_TO_POINTER(keysym), GUINT_TO_POINTER(code + 1));
}

/* Nothing stays held on the phone once the computer has let go of the window. */
static void release_all(void) {
    if (!app.fake) return;
    if (app.buttons) pointer(0, 0, 0);
    GList *held = g_hash_table_get_keys(app.keys_down);
    for (GList *l = held; l; l = l->next) key(FALSE, GPOINTER_TO_UINT(l->data));
    g_list_free(held);
    if (app.display) wl_display_flush(app.display);
}

/* ── video ─────────────────────────────────────────────────────────────── */

static void force_keyframe(void) {
    if (!app.appsink) return;
    gst_element_send_event(app.appsink, gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0));
}

static void apply_bitrate(void) {
    if (!app.encoder_el) return;
    if (strcmp(app.encoder, "x264enc") == 0) g_object_set(app.encoder_el, "bitrate", (guint)(app.bitrate / 1000), NULL);
    else if (strcmp(app.encoder, "openh264enc") == 0) g_object_set(app.encoder_el, "bitrate", (guint)app.bitrate, NULL);
}

/*
 * Before the encoder: nothing goes in while no window is watching, nor while
 * the link has more than MAX_INFLIGHT frames unacknowledged. A frame skipped
 * here costs nothing; one skipped after the encoder would break the chain of
 * frames that refer to it.
 */
static GstPadProbeReturn gate(GstPad *pad, GstPadProbeInfo *info, gpointer d) {
    if (!g_atomic_int_get(&app.attached)) return GST_PAD_PROBE_DROP;
    if (g_atomic_int_get(&app.acks) && g_atomic_int_get(&app.inflight) >= MAX_INFLIGHT) {
        /* A window that stopped acknowledging (it went and came back) must not hold the picture for ever. */
        if (g_get_monotonic_time() - app.last_ack < G_USEC_PER_SEC) return GST_PAD_PROBE_DROP;
        g_atomic_int_set(&app.inflight, 0);
    }
    return GST_PAD_PROBE_OK;
}

static void send_video_config(void) {
    char *msg = g_strdup_printf("{\"t\":\"video\",\"dw\":%d,\"dh\":%d,\"cw\":%d,\"ch\":%d,\"encoder\":\"%s\","
                                "\"hw\":%s,\"bitrate\":%d}",
                                g_atomic_int_get(&app.pw), g_atomic_int_get(&app.ph), g_atomic_int_get(&app.pw),
                                g_atomic_int_get(&app.ph), app.encoder, app.hw ? "true" : "false", app.bitrate);
    send_text(msg);
    g_free(msg);
}

static GstFlowReturn on_sample(GstAppSink *sink, gpointer d) {
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_EOS;
    int w = 0, h = 0;
    GstCaps *sample_caps = gst_sample_get_caps(sample);
    GstStructure *s = sample_caps ? gst_caps_get_structure(sample_caps, 0) : NULL;
    if (s) {
        gst_structure_get_int(s, "width", &w);
        gst_structure_get_int(s, "height", &h);
    }
    if (g_atomic_int_get(&app.sampled) && w > 0 && h > 0 &&
        (w != g_atomic_int_get(&app.pw) || h != g_atomic_int_get(&app.ph))) {
        /* The screen took a new size (on_resize): the encoder has started over
         * with a key frame, and the window hears of the size before it. */
        g_atomic_int_set(&app.pw, w);
        g_atomic_int_set(&app.ph, h);
        g_atomic_int_set(&app.need_config, 1);
        say("video: now %dx%d", w, h);
    }
    if (!g_atomic_int_get(&app.sampled)) {
        g_atomic_int_set(&app.pw, w);
        g_atomic_int_set(&app.ph, h);
        g_atomic_int_set(&app.sampled, 1);
        say("video: %dx%d from %s", w, h, app.encoder);
        GstElement *src = app.pipeline ? gst_bin_get_by_name(GST_BIN(app.pipeline), "src") : NULL;
        GstPad *pad = src ? gst_element_get_static_pad(src, "src") : NULL;
        GstCaps *caps = pad ? gst_pad_get_current_caps(pad) : NULL;
        char *text = caps ? gst_caps_to_string(caps) : NULL;
        say("video: KWin sends %s", text ? text : "?");
        g_free(text);
        if (caps) gst_caps_unref(caps);
        if (pad) gst_object_unref(pad);
        if (src) gst_object_unref(src);
    }
    GstBuffer *buf = gst_sample_get_buffer(sample);
    GstMapInfo map;
    if (g_atomic_int_get(&app.attached) && buf && gst_buffer_map(buf, &map, GST_MAP_READ)) {
        if (g_atomic_int_compare_and_exchange(&app.need_config, 1, 0)) send_video_config();
        gboolean key = !GST_BUFFER_FLAG_IS_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT);
        guint64 pts = GST_BUFFER_PTS_IS_VALID(buf) ? GST_BUFFER_PTS(buf) / 1000 : (guint64)g_get_monotonic_time();
        guint8 head[10] = {MSG_VIDEO, key ? 1 : 0};
        for (int i = 0; i < 8; i++) head[2 + i] = (guint8)(pts >> (56 - 8 * i));
        if (g_atomic_int_get(&app.acks)) g_atomic_int_inc(&app.inflight);
        send_frame(KIND_BINARY, head, sizeof head, map.data, map.size);
        g_atomic_int_inc(&app.frames);
        g_atomic_int_add(&app.bytes, (gint)map.size);
        gst_buffer_unmap(buf, &map);
    }
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

/* The hardware encoder when the phone has one this user can open, then the software ones. */
static GPtrArray *encoder_candidates(void) {
    GPtrArray *list = g_ptr_array_new();
    static const char *const all[] = {"v4l2h264enc", "x264enc", "openh264enc", NULL};
    for (int i = 0; all[i]; i++) {
        if (app.forced_encoder && strcmp(app.forced_encoder, all[i]) != 0) continue;
        GstElement *e = gst_element_factory_make(all[i], NULL);
        if (!e) continue;
        /* Opening is where a hardware encoder this user may not use, or one the driver cannot drive, says no. */
        GstStateChangeReturn r = gst_element_set_state(e, GST_STATE_READY);
        gst_element_set_state(e, GST_STATE_NULL);
        gst_object_unref(e);
        if (r == GST_STATE_CHANGE_FAILURE) {
            say("encoder %s is there but won't open", all[i]);
            continue;
        }
        g_ptr_array_add(list, (gpointer)all[i]);
    }
    return list;
}

#define LEAKY "queue max-size-buffers=1 max-size-bytes=0 max-size-time=0 leaky=downstream"

/*
 * KWin's frames are BGRx; every H.264 encoder wants YUV. On the CPU that is
 * videoconvert; on the GPU the frames come as DMA-BUFs, never read back by
 * KWin, are converted by a shader and read back once, at 12 bits a pixel.
 */
static char *convert_desc(const char *format) {
    if (app.convert && strcmp(app.convert, "gl") == 0)
        return g_strdup_printf("glupload ! glcolorconvert ! video/x-raw(memory:GLMemory),format=%s ! gldownload ! "
                               "video/x-raw,format=%s",
                               format, format);
    return g_strdup_printf("videoconvert n-threads=4 ! video/x-raw,format=%s", format);
}

static char *pipeline_desc(const char *enc) {
    unsigned kbps = (unsigned)(app.bitrate / 1000);
    char *coder;
    if (strcmp(enc, "v4l2h264enc") == 0) {
        char *conv = convert_desc("NV12");
        coder = g_strdup_printf("%s ! " LEAKY " ! "
                                "v4l2h264enc name=enc extra-controls=\"controls,video_bitrate=%d,"
                                "video_gop_size=600,video_b_frames=0\"",
                                conv, app.bitrate);
        g_free(conv);
    } else if (strcmp(enc, "openh264enc") == 0) {
        char *conv = convert_desc("I420");
        coder = g_strdup_printf("%s ! " LEAKY " ! "
                                "openh264enc name=enc bitrate=%d complexity=low gop-size=600 multi-thread=4",
                                conv, app.bitrate);
        g_free(conv);
    } else {
        char *conv = convert_desc(app.convert && strcmp(app.convert, "gl") == 0 ? "NV12" : "I420");
        coder = g_strdup_printf("%s ! " LEAKY " ! "
                                "x264enc name=enc tune=zerolatency speed-preset=ultrafast bitrate=%u "
                                "key-int-max=600 threads=4 sliced-threads=true",
                                conv, kbps);
        g_free(conv);
    }
    char *desc = g_strdup_printf("pipewiresrc name=src do-timestamp=true keepalive-time=%d ! " LEAKY " ! %s ! "
                                 "h264parse config-interval=-1 ! video/x-h264,stream-format=byte-stream,alignment=au ! "
                                 "appsink name=out sync=false max-buffers=8 enable-last-sample=false",
                                 KEEPALIVE_MS, coder);
    g_free(coder);
    return desc;
}

/* The stream KWin made, by serial where this PipeWire knows serials, else by node. */
static void aim_source(GstElement *src) {
    char value[32];
    if (app.has_serial && g_object_class_find_property(G_OBJECT_GET_CLASS(src), "target-object")) {
        g_snprintf(value, sizeof value, "%" G_GUINT64_FORMAT, app.serial);
        g_object_set(src, "target-object", value, NULL);
    } else {
        g_snprintf(value, sizeof value, "%u", app.node);
        g_object_set(src, "path", value, NULL);
    }
}

static gboolean on_bus(GstBus *bus, GstMessage *m, gpointer d);

static void stop_video(void) {
    if (!app.pipeline) return;
    gst_element_set_state(app.pipeline, GST_STATE_NULL);
    gst_bus_remove_watch(gst_element_get_bus(app.pipeline));
    gst_object_unref(app.pipeline);
    app.pipeline = app.appsink = app.encoder_el = NULL;
}

static gboolean start_video(GString *why) {
    while (app.candidate < app.candidates->len) {
        const char *enc = g_ptr_array_index(app.candidates, app.candidate);
        char *desc = pipeline_desc(enc);
        GError *err = NULL;
        GstElement *p = gst_parse_launch(desc, &err);
        g_free(desc);
        if (!p || err) {
            say("encoder %s: %s", enc, err ? err->message : "no pipeline");
            g_clear_error(&err);
            if (p) gst_object_unref(p);
            app.candidate++;
            continue;
        }
        app.pipeline = p;
        app.encoder = enc;
        app.hw = strcmp(enc, "v4l2h264enc") == 0;
        aim_source(gst_bin_get_by_name(GST_BIN(p), "src"));
        app.encoder_el = gst_bin_get_by_name(GST_BIN(p), "enc");
        GstPad *in = gst_element_get_static_pad(app.encoder_el, "sink");
        gst_pad_add_probe(in, GST_PAD_PROBE_TYPE_BUFFER, gate, NULL, NULL);
        gst_object_unref(in);
        app.appsink = gst_bin_get_by_name(GST_BIN(p), "out");
        GstAppSinkCallbacks cb = {0};
        cb.new_sample = on_sample;
        gst_app_sink_set_callbacks(GST_APP_SINK(app.appsink), &cb, NULL, NULL);
        GstBus *bus = gst_element_get_bus(p);
        gst_bus_add_watch(bus, on_bus, NULL);
        gst_object_unref(bus);
        if (gst_element_set_state(p, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
            say("encoder %s: the pipeline would not start", enc);
            stop_video();
            app.candidate++;
            continue;
        }
        say("video: %s, %d kb/s", enc, app.bitrate / 1000);
        return TRUE;
    }
    g_string_assign(why, "The phone has no video encoder Open Android DeX can use. "
                         "Install gst-plugins-ugly on the phone (sudo apk add gst-plugins-ugly) and try again.");
    return FALSE;
}

static gboolean on_bus(GstBus *bus, GstMessage *m, gpointer d) {
    if (GST_MESSAGE_TYPE(m) == GST_MESSAGE_ERROR) {
        GError *err = NULL;
        char *dbg = NULL;
        gst_message_parse_error(m, &err, &dbg);
        say("video: %s (%s)", err ? err->message : "?", dbg ? dbg : "");
        gboolean early = !g_atomic_int_get(&app.sampled);
        GString *why = g_string_new(NULL);
        g_free(dbg);
        if (early && app.candidate + 1 < app.candidates->len) {
            /* An encoder that opened and then would not take the picture: the next one. */
            stop_video();
            app.candidate++;
            if (start_video(why)) {
                g_clear_error(&err);
                g_string_free(why, TRUE);
                return G_SOURCE_REMOVE;
            }
        }
        if (!why->len) g_string_printf(why, "The phone's desktop picture stopped: %s", err ? err->message : "?");
        send_state("stopped", why->str);
        g_string_free(why, TRUE);
        g_clear_error(&err);
        finish(1);
        return G_SOURCE_REMOVE;
    }
    if (GST_MESSAGE_TYPE(m) == GST_MESSAGE_EOS) {
        say("video: the stream ended");
        send_state("stopped", "The phone's desktop picture ended.");
        finish(0);
        return G_SOURCE_REMOVE;
    }
    if (GST_MESSAGE_TYPE(m) == GST_MESSAGE_WARNING) {
        GError *err = NULL;
        gst_message_parse_warning(m, &err, NULL);
        say("video warning: %s", err ? err->message : "?");
        g_clear_error(&err);
    }
    return G_SOURCE_CONTINUE;
}

/* ── keeping the phone awake ───────────────────────────────────────────── */

/*
 * The phone's own idle timers know nothing of a computer using it: without
 * these it would dim, lock and suspend under the person typing. Both are held
 * on this process's bus connection and go with it, however it ends.
 */
static void inhibit(void) {
    GError *err = NULL;
    app.bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &err);
    if (!app.bus) {
        say("no session bus, so the phone may sleep: %s", err ? err->message : "?");
        g_clear_error(&err);
        return;
    }
    static const char *const who[][3] = {
        {"org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver"},
        {"org.freedesktop.PowerManagement.Inhibit", "/org/freedesktop/PowerManagement/Inhibit",
         "org.freedesktop.PowerManagement.Inhibit"},
    };
    for (gsize i = 0; i < G_N_ELEMENTS(who); i++) {
        GVariant *r = g_dbus_connection_call_sync(
            app.bus, who[i][0], who[i][1], who[i][2], "Inhibit",
            g_variant_new("(ss)", "Open Android DeX", "This phone's desktop is open on a computer"),
            G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 3000, NULL, &err);
        if (r) g_variant_unref(r);
        else {
            say("%s: %s", who[i][2], err ? err->message : "?");
            g_clear_error(&err);
        }
    }
}

/* ── the computer's messages ───────────────────────────────────────────── */

static void on_hello(const char *json) {
    char v[64];
    g_atomic_int_set(&app.acks, json_raw(json, "acks", v, sizeof v) && strcmp(v, "true") == 0);
    if (json_raw(json, "bitrate", v, sizeof v)) {
        int b = atoi(v);
        /* The software encoder's cost grows with its bits; past this a cable gains nothing visible. */
        app.bitrate = CLAMP(b, 2000000, app.hw ? 40000000 : 16000000);
        apply_bitrate();
    }
    g_atomic_int_set(&app.inflight, 0);
    app.last_ack = g_get_monotonic_time();
    g_atomic_int_set(&app.need_config, 1);
    g_atomic_int_set(&app.attached, 1);
    send_state("running", NULL);
    force_keyframe();
    /* A window that comes back may have been resized meanwhile. */
    on_resize(json);
}

static void on_text(const char *json) {
    char t[32];
    if (!json_raw(json, "t", t, sizeof t)) return;
    if (strcmp(t, "hello") == 0) on_hello(json);
    else if (strcmp(t, "keyframe") == 0) force_keyframe();
    else if (strcmp(t, "ping") == 0) {
        char n[64];
        if (json_raw(json, "n", n, sizeof n) && strspn(n, "0123456789.-+eE") == strlen(n)) {
            char *pong = g_strdup_printf("{\"t\":\"pong\",\"n\":%s}", n);
            send_text(pong);
            g_free(pong);
        }
    } else if (strcmp(t, "bitrate") == 0) {
        char v[32];
        if (json_raw(json, "bitrate", v, sizeof v)) {
            app.bitrate = CLAMP(atoi(v), 2000000, app.hw ? 40000000 : 16000000);
            apply_bitrate();
        }
    } else if (strcmp(t, "bye") == 0) {
        /* The window went (pmos.rs says so); the screen stays for the next one. */
        g_atomic_int_set(&app.attached, 0);
        release_all();
    } else if (strcmp(t, "stop") == 0) {
        finish(0);
    } else if (strcmp(t, "resize") == 0) {
        on_resize(json);
    }
    /* "hb" only feeds the watchdog. */
}

static void on_message(guint8 kind, const guint8 *p, gsize n) {
    app.last_rx = g_get_monotonic_time();
    if (kind == KIND_BINARY) {
        if (n >= 1 && p[0] == IN_ACK) {
            if (g_atomic_int_get(&app.inflight) > 0) g_atomic_int_add(&app.inflight, -1);
            app.last_ack = app.last_rx;
        } else if (n >= 6 && p[0] == IN_POINTER) {
            pointer(p[1], (p[2] << 8) | p[3], (p[4] << 8) | p[5]);
        } else if (n >= 6 && p[0] == IN_KEY) {
            key(p[1] != 0, ((guint32)p[2] << 24) | ((guint32)p[3] << 16) | ((guint32)p[4] << 8) | p[5]);
        }
        return;
    }
    char *json = g_strndup((const char *)p, n);
    on_text(json);
    g_free(json);
}

static gboolean on_stdin(gint fd, GIOCondition cond, gpointer d) {
    guint8 buf[65536];
    for (;;) {
        ssize_t n = read(fd, buf, sizeof buf);
        if (n > 0) {
            g_byte_array_append(app.in, buf, (guint)n);
            continue;
        }
        if (n == 0) {
            say("the computer hung up");
            finish(0);
            return G_SOURCE_REMOVE;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
        say("reading from the computer: %s", g_strerror(errno));
        finish(1);
        return G_SOURCE_REMOVE;
    }
    gsize off = 0;
    while (app.in->len - off >= 5) {
        const guint8 *h = app.in->data + off;
        guint32 len = ((guint32)h[1] << 24) | ((guint32)h[2] << 16) | ((guint32)h[3] << 8) | h[4];
        if (len > (1u << 22)) {
            say("a message from the computer is too long (%u bytes)", len);
            finish(1);
            return G_SOURCE_REMOVE;
        }
        if (app.in->len - off - 5 < len) break;
        on_message(h[0], h + 5, len);
        off += 5 + len;
    }
    if (off) g_byte_array_remove_range(app.in, 0, (guint)off);
    if (app.display) wl_display_flush(app.display);
    return G_SOURCE_CONTINUE;
}

static gboolean on_wayland(gint fd, GIOCondition cond, gpointer d) {
    if ((cond & (G_IO_ERR | G_IO_HUP | G_IO_NVAL)) || wl_display_dispatch(app.display) < 0) {
        say("lost the phone's desktop (Wayland)");
        send_state("stopped", "The phone's desktop closed.");
        finish(1);
        return G_SOURCE_REMOVE;
    }
    wl_display_flush(app.display);
    return G_SOURCE_CONTINUE;
}

static gboolean on_tick(gpointer d) {
    gint64 now = g_get_monotonic_time();
    if (now - app.last_rx > WATCHDOG_US) {
        say("nothing from the computer for %d s: closing the screen", (int)(WATCHDOG_US / G_USEC_PER_SEC));
        finish(0);
        return G_SOURCE_REMOVE;
    }
    int frames = g_atomic_int_get(&app.frames);
    int bytes = g_atomic_int_get(&app.bytes);
    g_atomic_int_add(&app.frames, -frames);
    g_atomic_int_add(&app.bytes, -bytes);
    if (g_atomic_int_get(&app.attached)) {
        char *msg = g_strdup_printf("{\"t\":\"stats\",\"fps\":%d,\"kbps\":%d,\"encodeMs\":0}", frames, bytes / 125);
        send_text(msg);
        g_free(msg);
    }
    return G_SOURCE_CONTINUE;
}

static gboolean on_signal(gpointer d) {
    say("asked to stop");
    finish(0);
    return G_SOURCE_REMOVE;
}

/* ── commands ──────────────────────────────────────────────────────────── */

static gboolean parse_args(int argc, char **argv) {
    app.width = 1920;
    app.height = 1080;
    app.scale = 1.0;
    app.name = g_strdup("OpenAndroidDeX");
    app.bitrate = 12000000;
    for (int i = 2; i < argc; i++) {
        const char *a = argv[i];
        const char *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (strcmp(a, "--embed-cursor") == 0) {
            app.embed_cursor = TRUE;
            continue;
        }
        if (!v) {
            say("%s needs a value", a);
            return FALSE;
        }
        i++;
        if (strcmp(a, "--width") == 0) app.width = atoi(v);
        else if (strcmp(a, "--height") == 0) app.height = atoi(v);
        else if (strcmp(a, "--cap") == 0) app.cap = atoi(v);
        else if (strcmp(a, "--scale") == 0) app.scale = g_ascii_strtod(v, NULL);
        else if (strcmp(a, "--name") == 0) {
            g_free(app.name);
            app.name = g_strdup(v);
        } else if (strcmp(a, "--encoder") == 0) app.forced_encoder = g_strdup(v);
        else if (strcmp(a, "--convert") == 0) app.convert = g_strdup(v);
        else if (strcmp(a, "--bitrate") == 0) app.bitrate = atoi(v);
        else if (strcmp(a, "--out") == 0 || strcmp(a, "--wait") == 0) continue; /* snapshot's, read there */
        else {
            say("unknown option %s", a);
            return FALSE;
        }
    }
    if (app.width < 320 || app.height < 240 || app.width > 7680 || app.height > 4320 || app.scale < 0.5 ||
        app.scale > 4) {
        say("a screen of %dx%d at %.2f is not one to make", app.width, app.height, app.scale);
        return FALSE;
    }
    if (app.cap <= 0) app.cap = MAX(app.width, app.height);
    app.cap = CLAMP(app.cap, 640, 7680);
    return TRUE;
}

static const char *arg_value(int argc, char **argv, const char *name) {
    for (int i = 2; i + 1 < argc; i++)
        if (strcmp(argv[i], name) == 0) return argv[i + 1];
    return NULL;
}

/* One line of JSON: whether this phone can do it, and what it has to do it with. */
static int cmd_probe(void) {
    GString *why = g_string_new(NULL);
    gboolean wl = wayland_connect(why);
    GString *s = g_string_new("{\"version\":");
    json_string(s, VERSION);
    g_string_append(s, ",\"wayland\":");
    json_string(s, getenv("WAYLAND_DISPLAY"));
    g_string_append_printf(s, ",\"connected\":%s", wl ? "true" : "false");
    g_string_append_printf(s, ",\"screencast\":%u,\"fakeInput\":%u", app.screencast_version, app.fake_version);
    g_string_append(s, ",\"outputs\":[");
    for (GList *l = app.outputs; l; l = l->next) {
        struct output *o = l->data;
        g_string_append_printf(s, "%s{\"name\":", l == app.outputs ? "" : ",");
        json_string(s, o->name);
        g_string_append_printf(s, ",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}", o->lx, o->ly, o->lw, o->lh);
    }
    g_string_append(s, "],\"encoders\":[");
    GPtrArray *enc = encoder_candidates();
    for (guint i = 0; i < enc->len; i++) {
        if (i) g_string_append_c(s, ',');
        json_string(s, g_ptr_array_index(enc, i));
    }
    g_ptr_array_free(enc, TRUE);
    GstElementFactory *pw = gst_element_factory_find("pipewiresrc");
    g_string_append_printf(s, "],\"pipewire\":%s", pw ? "true" : "false");
    if (pw) gst_object_unref(pw);
    g_string_append(s, ",\"globals\":");
    json_string(s, app.globals ? app.globals->str : "");
    if (!wl) {
        g_string_append(s, ",\"error\":");
        json_string(s, why->str);
    }
    g_string_append(s, "}\n");
    fputs(s->str, stdout);
    fflush(stdout);
    g_string_free(s, TRUE);
    g_string_free(why, TRUE);
    if (app.display) wl_display_disconnect(app.display);
    return wl ? 0 : 1;
}

/* For looking at what Plasma puts on the screen: one frame of it, as a PNG. */
static int cmd_snapshot(int argc, char **argv) {
    const char *out = arg_value(argc, argv, "--out");
    const char *wait = arg_value(argc, argv, "--wait");
    if (!out) {
        say("snapshot needs --out");
        return 2;
    }
    GString *why = g_string_new(NULL);
    if (!wayland_connect(why) || !make_screen(why)) {
        say("%s", why->str);
        return 1;
    }
    keep_scale();
    gint64 until = g_get_monotonic_time() + (gint64)((wait ? g_ascii_strtod(wait, NULL) : 3.0) * G_USEC_PER_SEC);
    while (g_get_monotonic_time() < until)
        if (dispatch_for(100) < 0) return 1;
    char *desc = g_strdup_printf("pipewiresrc name=src ! videoconvert ! pngenc snapshot=true ! filesink location=\"%s\"",
                                 out);
    GError *err = NULL;
    GstElement *p = gst_parse_launch(desc, &err);
    g_free(desc);
    if (!p) {
        say("%s", err ? err->message : "no pipeline");
        return 1;
    }
    aim_source(gst_bin_get_by_name(GST_BIN(p), "src"));
    gst_element_set_state(p, GST_STATE_PLAYING);
    GstBus *bus = gst_element_get_bus(p);
    int status = 1;
    gint64 deadline = g_get_monotonic_time() + 10 * G_USEC_PER_SEC;
    while (g_get_monotonic_time() < deadline) {
        dispatch_for(20);
        GstMessage *m = gst_bus_timed_pop_filtered(bus, 50 * GST_MSECOND, GST_MESSAGE_EOS | GST_MESSAGE_ERROR);
        if (!m) continue;
        if (GST_MESSAGE_TYPE(m) == GST_MESSAGE_EOS) status = 0;
        else {
            GError *e = NULL;
            gst_message_parse_error(m, &e, NULL);
            say("snapshot: %s", e ? e->message : "?");
            g_clear_error(&e);
        }
        gst_message_unref(m);
        break;
    }
    gst_object_unref(bus);
    gst_element_set_state(p, GST_STATE_NULL);
    gst_object_unref(p);
    struct output *o = our_output();
    if (o) printf("{\"output\":\"%s\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}\n", o->name ? o->name : "", o->lx, o->ly, o->lw, o->lh);
    close_screen();
    wl_display_disconnect(app.display);
    return status;
}

static int cmd_serve(void) {
    signal(SIGPIPE, SIG_IGN);
    app.in = g_byte_array_new();
    app.keys_down = g_hash_table_new(NULL, NULL);
    app.last_rx = g_get_monotonic_time();
    GString *why = g_string_new(NULL);
    if (!wayland_connect(why) || !make_screen(why)) {
        say("%s", why->str);
        send_state("failed", why->str);
        return 1;
    }
    keep_scale();
    start_sizer();
    app.candidates = encoder_candidates();
    if (!start_video(why)) {
        say("%s", why->str);
        send_state("failed", why->str);
        stop_sizer();
        close_screen();
        return 1;
    }
    inhibit();
    {
        struct output *o = our_output();
        GString *s = g_string_new("{\"t\":\"ready\",\"output\":");
        json_string(s, o && o->name ? o->name : "");
        g_string_append_printf(s, ",\"w\":%d,\"h\":%d,\"scale\":%.4f,\"encoder\":\"%s\",\"hw\":%s,\"keys\":%s}",
                               app.width, app.height, app.scale, app.encoder, app.hw ? "true" : "false",
                               app.fake && app.fake_version >= 4 ? "true" : "false");
        send_text(s->str);
        g_string_free(s, TRUE);
    }
    app.loop = g_main_loop_new(NULL, FALSE);
    fcntl(STDIN_FILENO, F_SETFL, fcntl(STDIN_FILENO, F_GETFL) | O_NONBLOCK);
    g_unix_fd_add(STDIN_FILENO, G_IO_IN | G_IO_HUP | G_IO_ERR, on_stdin, NULL);
    wl_display_flush(app.display);
    g_unix_fd_add(wl_display_get_fd(app.display), G_IO_IN | G_IO_HUP | G_IO_ERR, on_wayland, NULL);
    g_timeout_add_seconds(1, on_tick, NULL);
    g_unix_signal_add(SIGTERM, on_signal, NULL);
    g_unix_signal_add(SIGINT, on_signal, NULL);
    g_unix_signal_add(SIGHUP, on_signal, NULL);
    g_main_loop_run(app.loop);

    release_all();
    g_atomic_int_set(&app.attached, 0);
    stop_video();
    stop_sizer();
    close_screen();
    wl_display_disconnect(app.display);
    say("done");
    return app.status;
}

int main(int argc, char **argv) {
    g_mutex_init(&app.out_lock);
    const char *cmd = argc > 1 ? argv[1] : "";
    if (strcmp(cmd, "version") == 0) {
        printf("openandroiddex-pmos %s\n", VERSION);
        return 0;
    }
    if (strcmp(cmd, "probe") != 0 && strcmp(cmd, "serve") != 0 && strcmp(cmd, "snapshot") != 0) {
        fprintf(stderr, "usage: openandroiddex-pmos version|probe|serve|snapshot [--width W --height H --scale S]\n");
        return 2;
    }
    if (!parse_args(argc, argv)) return 2;
    adopt_session();
    /*
     * A registry of its own, so a hardware encoder this user was only lately
     * allowed to open is looked at again rather than remembered as absent.
     */
    if (!getenv("GST_REGISTRY")) {
        char *reg = g_build_filename(g_get_user_cache_dir(), "openandroiddex", "gst-registry.bin", NULL);
        char *dir = g_path_get_dirname(reg);
        g_mkdir_with_parents(dir, 0700);
        setenv("GST_REGISTRY", reg, 1);
        g_free(dir);
        g_free(reg);
    }
    gst_init(NULL, NULL);
    if (strcmp(cmd, "probe") == 0) return cmd_probe();
    if (strcmp(cmd, "snapshot") == 0) return cmd_snapshot(argc, argv);
    return cmd_serve();
}

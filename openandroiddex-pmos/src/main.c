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
 * Phosh (phoc, a wlroots compositor) has no virtual outputs, but wlroots can
 * run a headless output beside the phone's panel when the session starts with
 * it (WLR_BACKENDS=…,headless). This program then sizes that output for the
 * computer, makes it Phosh's primary screen so the shell (bar, app grid,
 * windows) is there, copies it with wlr-screencopy into the same encoder, and
 * types and points on it with a virtual keyboard and pointer. When it ends,
 * or dies (a guard process of its own sees to that), the phone's panel is
 * primary again and the headless output is off. See "Phosh" below.
 *
 * On KWin, while the computer's screen is up, a script of KWin's own (here.js)
 * makes an app opened from one screen show on that screen, where Plasma
 * Mobile's drawer would switch to the app's window on the other. This program
 * answers it on the session bus. See "What is opened from a screen" below.
 *
 * The clipboard goes both ways while a window is open (Wayland's data-control,
 * see "The clipboard"). Files dropped on the window arrive through `receive`,
 * into the phone's Downloads, over a second channel of the computer's SSH
 * connection; serve then drops them on the app where they were let go of
 * ("Files dropped on the window").
 *
 *   openandroiddex-pmos version
 *   openandroiddex-pmos probe
 *   openandroiddex-pmos serve --width W --height H --scale S [--cap C] [--encoder E] [--convert gl|cpu]
 *   openandroiddex-pmos snapshot --width W --height H --scale S --out F.png [--wait SECONDS]
 *   openandroiddex-pmos rest
 *   openandroiddex-pmos receive [--part]
 *   openandroiddex-pmos clip [--set TEXT]
 *
 * W and H are the screen's size in pixels (KWin makes its mode that size), S
 * the scale Plasma draws it at. After that the screen follows the window: its
 * size in the window's logical pixels, at scale S, at most C pixels on its
 * long side (W or H, whichever is longer, when C is not given). `rest` turns
 * Phosh's headless output off and gives the phone's panel the shell back: the
 * session's autostart runs it at login, and the guard when serve has gone.
 * `receive` takes files from the computer on stdin (see "Files from the
 * computer"). `clip` prints the phone's clipboard, or with --set puts TEXT on
 * it until something else is copied: for looking at the clipboard by hand.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input-event-codes.h>
#include <math.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#include <gio/gdesktopappinfo.h>
#include <gio/gio.h>
#include <glib-unix.h>
#include <glib.h>
#include <gst/app/gstappsink.h>
#include <gst/app/gstappsrc.h>
#include <gst/gst.h>
#include <gst/video/video.h>
#include <wayland-client.h>
#include <xkbcommon/xkbcommon.h>

#include "ext-data-control-v1-client-protocol.h"
#include "fake-input-client-protocol.h"
#include "here-js.h" /* src/here.js as HERE_JS, made by build.sh */
#include "virtual-keyboard-unstable-v1-client-protocol.h"
#include "wlr-data-control-unstable-v1-client-protocol.h"
#include "wlr-layer-shell-unstable-v1-client-protocol.h"
#include "wlr-output-management-unstable-v1-client-protocol.h"
#include "wlr-screencopy-unstable-v1-client-protocol.h"
#include "wlr-virtual-pointer-unstable-v1-client-protocol.h"
#include "xdg-output-unstable-v1-client-protocol.h"
#include "zkde-screencast-unstable-v1-client-protocol.h"

/* What `version` prints; pmos.rs compares the whole file's hash, not this. */
#define VERSION "5"

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
    int32_t mw, mh;         /* wl_output: the mode in use, in pixels */
    gboolean logical;
    gboolean before; /* there before our screen was asked for */
};

/* wlr-output-management's view of a screen (a "head") and its modes: what Phosh is configured through. */
struct head_mode {
    struct zwlr_output_mode_v1 *wl;
    struct head *head;
    int w, h, refresh;
};

struct head {
    struct zwlr_output_head_v1 *wl;
    char *name;
    gboolean enabled;
    struct head_mode *current;
    GList *modes;
    int x, y, transform;
    double scale;
};

/* The picture of Phosh's screen, as wlr-screencopy last delivered it: a wl_shm buffer of ours. */
struct shot {
    struct wl_buffer *wl;
    guint8 *data;
    gsize size;
    uint32_t format;
    int w, h, stride;
};

static struct {
    GMainLoop *loop;
    int status;
    gboolean finishing;
    gboolean no_session; /* no program of this user's had a desktop to take the session from */

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
    /* The stream's size, which KWin's picture of the screen follows (see restart_stream). */
    GstElement *sizecaps;   /* the capsfilter after pipewiresrc */
    gint stream_w, stream_h;
    gint asked_at;          /* when the window asked (ms_now() + 1), until the picture is that size; then 0 */
    gint64 t0;

    /* Phosh, or any wlroots compositor (see "Phosh" below). */
    gboolean wlr;                     /* the screen is a wlroots headless output, not KWin's virtual one */
    struct wl_shm *shm;
    struct zwlr_output_manager_v1 *om;
    uint32_t om_global, om_version;   /* to bind it again (wlr_make_screen) */
    uint32_t om_serial;
    GList *heads;                     /* struct head */
    struct zwlr_screencopy_manager_v1 *copier;
    uint32_t copier_version;
    struct zwlr_virtual_pointer_manager_v1 *vp_manager;
    uint32_t vp_version;
    struct zwlr_virtual_pointer_v1 *vpointer;
    struct zwp_virtual_keyboard_manager_v1 *vk_manager;
    struct zwp_virtual_keyboard_v1 *vkeyboard;
    struct xkb_state *vk_state;       /* the virtual keyboard's modifiers: wlroots leaves them to the client */
    char *keymap_text;                /* the seat's keymap, as the compositor sent it */
    char *screen_name;                /* the headless output we drive (HEADLESS-1) */
    char *home_name;                  /* the screen that was primary before ours, for the end */
    gboolean primary_moved;           /* Phosh's shell is on our screen: give it back at the end */
    gboolean primary_wanted;          /* waiting for the phone to be unlocked to move it */
    guint lock_watch;
    struct zwlr_screencopy_frame_v1 *frame;
    struct shot shot;
    uint32_t shot_format;
    int shot_w, shot_h, shot_stride;
    gboolean shot_yinvert;
    gboolean capturing;
    GstElement *appsrc;
    int src_w, src_h;                 /* the appsrc's caps: frames of another size rebuild the pipeline */
    uint32_t src_format;
    GstBuffer *last_buf;              /* sent again while the screen is still (KEEPALIVE_MS) */
    gint64 last_push;
    int wheel_half[4];                /* the window's half-notch clicks, two to a notch here */
    struct zwlr_output_configuration_v1 *cfg; /* a size on its way to the compositor */
    int cfg_w, cfg_h;                 /* the size it carries */
    int next_cfg_w, next_cfg_h;       /* asked for while it was on its way */
    int guard_fd;                     /* the guard's pipe: it puts the phone back when this closes */

    /* What is opened from a screen shows there (KWin; see here.js). */
    gchar **session_env;              /* the session's environment, for the windows opened for it */
    gint64 pressed_at;                /* the computer's last key or button, g_get_monotonic_time() */
    char *kwin_name;                  /* KWin's unique name on the bus: only its script is answered */
    guint here_object, here_owner;
    gboolean here_loaded;

    /* The clipboard (see "The clipboard"): Wayland's data-control, the standard one or wlroots' older one. */
    struct ext_data_control_manager_v1 *dc_ext;
    struct zwlr_data_control_manager_v1 *dc_wlr;
    struct wl_proxy *dc_device;       /* the seat's device, of whichever of the two */
    struct clip_offer *clip_now;      /* what the phone's clipboard offers now */
    struct clip_offer *clip_primary;  /* the selection's, kept only to be let go of */
    struct clip_source *clip_mine;    /* the computer's text, while it is the phone's clipboard */
    struct clip_read *clip_reading;   /* the phone's clipboard on its way to the computer */
    gboolean clip_share;              /* the window's switch is on ("clipshare"): until it says so, nothing goes */
    gboolean clip_print;              /* `clip`: the phone's clipboard goes to stdout, once */

    /* Files dropped on the window, handed to the app under them (see "Files dropped on the window"). */
    struct wl_compositor *compositor;
    struct wl_data_device_manager *ddm;
    uint32_t ddm_version;
    struct wl_data_device *data_device;
    struct wl_data_offer *dnd_offer;  /* one the compositor gave this program's own surface; let go of */
    struct zwlr_layer_shell_v1 *layer_shell;
    uint32_t seat_caps;
    struct wl_pointer *wl_pointer;
    int last_x, last_y;               /* the computer's pointer, in the picture's pixels */
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
 * this program but "clip" and "drop", whose free text json_text takes.
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

static int hex4(const char *p) {
    int v = 0;
    for (int i = 0; i < 4; i++) {
        int d = g_ascii_xdigit_value(p[i]);
        if (d < 0) return -1;
        v = v * 16 + d;
    }
    return v;
}

/*
 * The string value of "key" in a flat JSON object, its escapes undone, as the
 * window's JSON.stringify wrote it: the clipboard's text, a drop's URIs. NULL
 * when there is none. A surrogate half without its other half (JSON.stringify
 * writes those as \uD8xx) becomes U+FFFD; a \u0000 stays, the length says
 * where the text ends.
 */
static GString *json_text(const char *json, const char *key) {
    char pat[64];
    g_snprintf(pat, sizeof pat, "\"%s\":", key);
    const char *p = strstr(json, pat);
    if (!p) return NULL;
    p += strlen(pat);
    while (*p == ' ') p++;
    if (*p != '"') return NULL;
    GString *s = g_string_new(NULL);
    for (p++; *p && *p != '"'; p++) {
        if (*p != '\\') {
            g_string_append_c(s, *p);
            continue;
        }
        p++;
        switch (*p) {
        case '"':
        case '\\':
        case '/': g_string_append_c(s, *p); break;
        case 'b': g_string_append_c(s, '\b'); break;
        case 'f': g_string_append_c(s, '\f'); break;
        case 'n': g_string_append_c(s, '\n'); break;
        case 'r': g_string_append_c(s, '\r'); break;
        case 't': g_string_append_c(s, '\t'); break;
        case 'u': {
            int c = hex4(p + 1);
            if (c < 0) goto bad;
            p += 4;
            if (c >= 0xD800 && c <= 0xDBFF && p[1] == '\\' && p[2] == 'u') {
                int lo = hex4(p + 3);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                    p += 6;
                }
            }
            if (c >= 0xD800 && c <= 0xDFFF) c = 0xFFFD;
            g_string_append_unichar(s, (gunichar)c);
            break;
        }
        default: goto bad;
        }
    }
    if (*p == '"') return s;
bad:
    g_string_free(s, TRUE);
    return NULL;
}

/* ── the fast cores ────────────────────────────────────────────────────── */

/*
 * Phones mix fast and slow cores, and the scheduler kept the encoder on the
 * slow ones: on the Galaxy S24, x264 made 19 frames a second at 1920x1020
 * left to it, and 109 on its fast cores (2026-11-16, moving SMPTE bars, one
 * thread). So this program, and every thread it starts (GStreamer's
 * included), runs on all but the slowest cores: those whose top clock is
 * the lowest of the phone's, when the phone has cores of more than one.
 */
static void prefer_fast_cores(void) {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    if (n <= 1 || n > CPU_SETSIZE) return;
    guint64 *top = g_new0(guint64, n);
    guint64 lo = G_MAXUINT64, hi = 0;
    for (long i = 0; i < n; i++) {
        char path[96];
        g_snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%ld/cpufreq/cpuinfo_max_freq", i);
        char *s = NULL;
        top[i] = g_file_get_contents(path, &s, NULL, NULL) ? g_ascii_strtoull(s, NULL, 10) : 0;
        g_free(s);
        if (!top[i]) continue;
        lo = MIN(lo, top[i]);
        hi = MAX(hi, top[i]);
    }
    if (hi > lo) {
        cpu_set_t set;
        CPU_ZERO(&set);
        GString *which = g_string_new(NULL);
        for (long i = 0; i < n; i++) {
            if (top[i] > lo) {
                CPU_SET((size_t)i, &set);
                g_string_append_printf(which, "%s%ld", which->len ? "," : "", i);
            }
        }
        if (sched_setaffinity(0, sizeof set, &set) == 0)
            say("cpus: %s, the fast ones (the slow ones top out at %.2f GHz)", which->str, lo / 1e6);
        g_string_free(which, TRUE);
    }
    g_free(top);
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
 * Whether a process belongs to a login session with a seat, the one at the
 * phone's own screen, as logind sees it. A desktop started from an SSH login
 * (a nested test session, say) has none, and its shell would otherwise do as
 * well as the real one. Without logind every process counts as seated.
 */
static gboolean seated(GDBusConnection *sys, const char *pid) {
    if (!sys) return TRUE;
    GVariant *r = g_dbus_connection_call_sync(sys, "org.freedesktop.login1", "/org/freedesktop/login1",
                                              "org.freedesktop.login1.Manager", "GetSessionByPID",
                                              g_variant_new("(u)", (guint32)atoi(pid)), G_VARIANT_TYPE("(o)"),
                                              G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
    if (!r) return FALSE;
    const char *session = NULL;
    g_variant_get(r, "(&o)", &session);
    GVariant *v = g_dbus_connection_call_sync(sys, "org.freedesktop.login1", session, "org.freedesktop.DBus.Properties",
                                              "Get", g_variant_new("(ss)", "org.freedesktop.login1.Session", "Seat"),
                                              G_VARIANT_TYPE("(v)"), G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
    gboolean yes = FALSE;
    if (v) {
        GVariant *seat = NULL;
        g_variant_get(v, "(v)", &seat);
        if (g_variant_is_of_type(seat, G_VARIANT_TYPE("(so)"))) {
            const char *id = NULL;
            g_variant_get(seat, "(&s&o)", &id, NULL);
            yes = id && *id;
        }
        g_variant_unref(seat);
        g_variant_unref(v);
    }
    g_variant_unref(r);
    return yes;
}

/*
 * Over SSH this runs outside the phone's graphical session, so its
 * environment says nothing about it. The session's own programs know: take
 * the Wayland socket, the runtime directory and the session bus from the
 * first of them that is this user's, the shell (Plasma's or Phosh) first, and
 * one in the session at the phone's screen before any other (see seated).
 */
static void adopt_session(void) {
    if (getenv("WAYLAND_DISPLAY") && getenv("XDG_RUNTIME_DIR") && getenv("DBUS_SESSION_BUS_ADDRESS")) return;
    static const char *const prefer[] = {"plasmashell", "phosh", "kded6", "ksmserver", "kwin_wayland", NULL};
    enum { UNSEATED = 1000 };
    GDBusConnection *sys = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, NULL);
    char *best = NULL;
    gsize best_len = 0;
    int best_rank = 2 * UNSEATED;
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
        /* logind is asked about the shell and its companions only; any other program is a last resort. */
        if (rank >= 100 || !seated(sys, e->d_name)) rank += UNSEATED;
        if (rank >= best_rank) {
            g_free(env);
            continue;
        }
        g_free(best);
        best = env;
        best_len = len;
        best_rank = rank;
    }
    if (d) closedir(d);
    if (sys) g_object_unref(sys);
    /* XDG_DATA_DIRS: where the session finds desktop files (Flatpak's exports, say), for here.js's apps. */
    static const char *const keys[] = {"WAYLAND_DISPLAY",     "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS",
                                       "DISPLAY",             "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP",
                                       "PLASMA_PLATFORM",     "XDG_DATA_DIRS",    NULL};
    if (best) {
        for (int i = 0; keys[i]; i++) {
            const char *v = environ_get(best, best_len, keys[i]);
            if (v && !getenv(keys[i])) setenv(keys[i], v, 1);
        }
        /* All of it, for a window opened for the session: an app started from here starts as the drawer's do. */
        GPtrArray *env = g_ptr_array_new();
        for (const char *p = best; p < best + best_len; p += strlen(p) + 1)
            if (strchr(p, '=')) g_ptr_array_add(env, g_strdup(p));
        g_ptr_array_add(env, NULL);
        app.session_env = (gchar **)g_ptr_array_free(env, FALSE);
        g_free(best);
    } else {
        /* No program of this user's has a desktop: nobody is logged in (Phosh's greeter is another user's). */
        app.no_session = TRUE;
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
static void output_mode(void *data, struct wl_output *wl, uint32_t flags, int32_t w, int32_t h, int32_t r) {
    struct output *o = data;
    if (flags & WL_OUTPUT_MODE_CURRENT) {
        o->mw = w;
        o->mh = h;
    }
}
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
    /* wlroots sends the keymap of whichever keyboard was used last (the phone's
     * own keys, its on-screen keyboard, ours): once the virtual keyboard has
     * one, keys are looked up in that one only. */
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 || size == 0 || app.vkeyboard) {
        close(fd);
        return;
    }
    char *map = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return;
    if (!app.xkb) app.xkb = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    struct xkb_keymap *km =
        xkb_keymap_new_from_buffer(app.xkb, map, strnlen(map, size), XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
    /* The virtual keyboard (Phosh) hands the same keymap back to the compositor. */
    g_free(app.keymap_text);
    app.keymap_text = g_strndup(map, strnlen(map, size));
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
    app.seat_caps = caps; /* the pointer's, for a drop (carry) */
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

/* ── wlroots' screens: wlr-output-management ───────────────────────────── */

static void mode_size(void *d, struct zwlr_output_mode_v1 *m, int32_t w, int32_t h) {
    struct head_mode *hm = d;
    hm->w = w;
    hm->h = h;
}
static void mode_refresh(void *d, struct zwlr_output_mode_v1 *m, int32_t refresh) {
    struct head_mode *hm = d;
    hm->refresh = refresh;
}
static void mode_preferred(void *d, struct zwlr_output_mode_v1 *m) {}
static void mode_finished(void *d, struct zwlr_output_mode_v1 *m) {
    struct head_mode *hm = d;
    if (hm->head) {
        hm->head->modes = g_list_remove(hm->head->modes, hm);
        if (hm->head->current == hm) hm->head->current = NULL;
    }
    if (zwlr_output_mode_v1_get_version(m) >= ZWLR_OUTPUT_MODE_V1_RELEASE_SINCE_VERSION) zwlr_output_mode_v1_release(m);
    else zwlr_output_mode_v1_destroy(m);
    g_free(hm);
}
static const struct zwlr_output_mode_v1_listener mode_listener = {
    .size = mode_size,
    .refresh = mode_refresh,
    .preferred = mode_preferred,
    .finished = mode_finished,
};

static void head_name(void *d, struct zwlr_output_head_v1 *h, const char *name) {
    struct head *hd = d;
    g_free(hd->name);
    hd->name = g_strdup(name);
}
static void head_description(void *d, struct zwlr_output_head_v1 *h, const char *desc) {}
static void head_physical_size(void *d, struct zwlr_output_head_v1 *h, int32_t w, int32_t hh) {}
static void head_mode(void *d, struct zwlr_output_head_v1 *h, struct zwlr_output_mode_v1 *m) {
    struct head *hd = d;
    struct head_mode *hm = g_new0(struct head_mode, 1);
    hm->wl = m;
    hm->head = hd;
    hd->modes = g_list_append(hd->modes, hm);
    zwlr_output_mode_v1_add_listener(m, &mode_listener, hm);
}
static void head_enabled(void *d, struct zwlr_output_head_v1 *h, int32_t enabled) {
    struct head *hd = d;
    hd->enabled = enabled != 0;
    if (!enabled) hd->current = NULL;
}
static void head_current_mode(void *d, struct zwlr_output_head_v1 *h, struct zwlr_output_mode_v1 *m) {
    struct head *hd = d;
    hd->current = m ? zwlr_output_mode_v1_get_user_data(m) : NULL;
}
static void head_position(void *d, struct zwlr_output_head_v1 *h, int32_t x, int32_t y) {
    struct head *hd = d;
    hd->x = x;
    hd->y = y;
}
static void head_transform(void *d, struct zwlr_output_head_v1 *h, int32_t t) {
    struct head *hd = d;
    hd->transform = t;
}
static void head_scale(void *d, struct zwlr_output_head_v1 *h, wl_fixed_t s) {
    struct head *hd = d;
    hd->scale = wl_fixed_to_double(s);
}
static void head_finished(void *d, struct zwlr_output_head_v1 *h) {
    struct head *hd = d;
    app.heads = g_list_remove(app.heads, hd);
    for (GList *l = hd->modes; l; l = l->next) ((struct head_mode *)l->data)->head = NULL;
    g_list_free(hd->modes);
    if (zwlr_output_head_v1_get_version(h) >= ZWLR_OUTPUT_HEAD_V1_RELEASE_SINCE_VERSION) zwlr_output_head_v1_release(h);
    else zwlr_output_head_v1_destroy(h);
    g_free(hd->name);
    g_free(hd);
}
static void head_make(void *d, struct zwlr_output_head_v1 *h, const char *s) {}
static void head_model(void *d, struct zwlr_output_head_v1 *h, const char *s) {}
static void head_serial(void *d, struct zwlr_output_head_v1 *h, const char *s) {}
static void head_adaptive_sync(void *d, struct zwlr_output_head_v1 *h, uint32_t state) {}
static const struct zwlr_output_head_v1_listener head_listener = {
    .name = head_name,
    .description = head_description,
    .physical_size = head_physical_size,
    .mode = head_mode,
    .enabled = head_enabled,
    .current_mode = head_current_mode,
    .position = head_position,
    .transform = head_transform,
    .scale = head_scale,
    .finished = head_finished,
    .make = head_make,
    .model = head_model,
    .serial_number = head_serial,
    .adaptive_sync = head_adaptive_sync,
};

static void om_head(void *d, struct zwlr_output_manager_v1 *m, struct zwlr_output_head_v1 *h) {
    struct head *hd = g_new0(struct head, 1);
    hd->wl = h;
    hd->scale = 1.0;
    app.heads = g_list_append(app.heads, hd);
    zwlr_output_head_v1_add_listener(h, &head_listener, hd);
}
static void om_done(void *d, struct zwlr_output_manager_v1 *m, uint32_t serial) { app.om_serial = serial; }
static void om_finished(void *d, struct zwlr_output_manager_v1 *m) {}
static const struct zwlr_output_manager_v1_listener om_listener = {
    .head = om_head,
    .done = om_done,
    .finished = om_finished,
};

static struct head *head_named(const char *name) {
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        if (hd->name && name && strcmp(hd->name, name) == 0) return hd;
    }
    return NULL;
}

/* wlroots names a headless output HEADLESS-<n>: the screen for the computer. */
static struct head *headless_head(void) {
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        if (hd->name && g_str_has_prefix(hd->name, "HEADLESS-")) return hd;
    }
    return NULL;
}

/* `clip` looks at the clipboard only: no fake input device of its own, which KWin would add to the seat. */
static gboolean clipboard_only;

static void global_add(void *data, struct wl_registry *r, uint32_t name, const char *iface, uint32_t version) {
    g_string_append_printf(app.globals, "%s%s:%u", app.globals->len ? "," : "", iface, version);
    if (strcmp(iface, zkde_screencast_unstable_v1_interface.name) == 0) {
        app.screencast_version = MIN(version, (uint32_t)zkde_screencast_unstable_v1_interface.version);
        app.screencast = wl_registry_bind(r, name, &zkde_screencast_unstable_v1_interface, app.screencast_version);
    } else if (strcmp(iface, org_kde_kwin_fake_input_interface.name) == 0 && !clipboard_only) {
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
    } else if (strcmp(iface, wl_shm_interface.name) == 0) {
        app.shm = wl_registry_bind(r, name, &wl_shm_interface, 1);
    } else if (strcmp(iface, zwlr_output_manager_v1_interface.name) == 0) {
        app.om_global = name;
        app.om_version = MIN(version, (uint32_t)zwlr_output_manager_v1_interface.version);
        app.om = wl_registry_bind(r, name, &zwlr_output_manager_v1_interface, app.om_version);
        zwlr_output_manager_v1_add_listener(app.om, &om_listener, NULL);
    } else if (strcmp(iface, zwlr_screencopy_manager_v1_interface.name) == 0) {
        app.copier_version = MIN(version, (uint32_t)zwlr_screencopy_manager_v1_interface.version);
        app.copier = wl_registry_bind(r, name, &zwlr_screencopy_manager_v1_interface, app.copier_version);
    } else if (strcmp(iface, zwlr_virtual_pointer_manager_v1_interface.name) == 0) {
        app.vp_version = MIN(version, (uint32_t)zwlr_virtual_pointer_manager_v1_interface.version);
        app.vp_manager = wl_registry_bind(r, name, &zwlr_virtual_pointer_manager_v1_interface, app.vp_version);
    } else if (strcmp(iface, zwp_virtual_keyboard_manager_v1_interface.name) == 0) {
        app.vk_manager = wl_registry_bind(r, name, &zwp_virtual_keyboard_manager_v1_interface, 1);
    } else if (strcmp(iface, ext_data_control_manager_v1_interface.name) == 0) {
        /* The clipboard: the standard data-control (KWin 6.8 has only this one), else wlroots' own. */
        app.dc_ext = wl_registry_bind(r, name, &ext_data_control_manager_v1_interface, 1);
    } else if (strcmp(iface, zwlr_data_control_manager_v1_interface.name) == 0) {
        app.dc_wlr = wl_registry_bind(r, name, &zwlr_data_control_manager_v1_interface, MIN(version, 2u));
    } else if (strcmp(iface, wl_compositor_interface.name) == 0) {
        app.compositor = wl_registry_bind(r, name, &wl_compositor_interface, MIN(version, 4u));
    } else if (strcmp(iface, wl_data_device_manager_interface.name) == 0) {
        app.ddm_version = MIN(version, 3u);
        app.ddm = wl_registry_bind(r, name, &wl_data_device_manager_interface, app.ddm_version);
    } else if (strcmp(iface, zwlr_layer_shell_v1_interface.name) == 0) {
        app.layer_shell = wl_registry_bind(r, name, &zwlr_layer_shell_v1_interface, MIN(version, 3u));
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
        if (app.no_session)
            g_string_assign(why, "Nobody is logged in on the phone yet. Log in on it, then open its desktop here again.");
        else
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

/* The output KWin made for us: by its name, else the one that came after. On
 * Phosh, the headless output we drive, by its name only. */
static struct output *our_output(void) {
    if (app.wlr) {
        for (GList *l = app.outputs; l; l = l->next) {
            struct output *o = l->data;
            if (o->name && app.screen_name && strcmp(o->name, app.screen_name) == 0) return o;
        }
        return NULL;
    }
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

static gboolean wlr_make_screen(GString *why);

/* Ask KWin for the screen, and wait for its PipeWire stream; or, on Phosh, ready its headless output. */
static gboolean make_screen(GString *why) {
    if (!app.screencast && app.om && app.copier && app.shm) {
        app.wlr = TRUE;
        return wlr_make_screen(why);
    }
    if (!app.screencast) {
        g_string_assign(why, "The phone's desktop didn't let Open Android DeX make a screen. "
                             "It needs KDE Plasma or Phosh (and, on Plasma, the permission file Open Android DeX "
                             "puts on the phone).");
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
    if (!o || !o->logical || o->lw <= 0) return 0;
    /* The mode in use, which is not always the one asked for: KWin gives a
     * new screen a custom mode it kept for that name, from a session that
     * ended without putting its own back (1000x640 for a 1496x862 screen on
     * 2026-11-14), until the sizer's tidy takes it away. */
    return (double)(o->mw > 0 ? o->mw : app.width) / o->lw;
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

/* ── Plasma Mobile's docked mode ─────────────────────────────────────── */

/*
 * Plasma Mobile shows every app full screen and without a title bar, on all
 * its screens, unless its Docked Mode is on (the quick setting; the key is
 * convergenceModeEnabled in plasmamobilerc's [General]). The shell and KWin
 * follow that file live when it is written with --notify. A computer's
 * screen wants windows, and the phone wants its phone back afterwards: so
 * docked mode is on while a computer has the screen, and then as the phone
 * had it. It is one setting, so the phone's own screen has windows too
 * meanwhile.
 *
 * What the phone had is kept in a file until it is put back, so a session
 * that ends any other way still puts it back (the guard; `rest` from the
 * login autostart after a restart), and a second session doesn't take the
 * first one's "on" for the phone's own.
 */
static gboolean plasma_mobile(void) {
    const char *p = getenv("PLASMA_PLATFORM");
    return p && g_str_has_prefix(p, "phone");
}

static char *docked_file(void) {
    return g_build_filename(g_get_user_cache_dir(), "openandroiddex", "plasma-docked-before", NULL);
}

/* kreadconfig6 (value NULL; the answer in *out) or kwriteconfig6 --notify on docked mode's key; the 5s on older Plasma. */
static gboolean docked_config(const char *value, char **out) {
    for (int v = 6; v >= 5; v--) {
        char prog[24];
        g_snprintf(prog, sizeof prog, "%s%d", value ? "kwriteconfig" : "kreadconfig", v);
        char *path = g_find_program_in_path(prog);
        if (!path) continue;
        const char *argv[12];
        int n = 0;
        argv[n++] = path;
        if (value) argv[n++] = "--notify";
        argv[n++] = "--file";
        argv[n++] = "plasmamobilerc";
        argv[n++] = "--group";
        argv[n++] = "General";
        argv[n++] = "--key";
        argv[n++] = "convergenceModeEnabled";
        if (value) argv[n++] = value;
        argv[n] = NULL;
        GError *err = NULL;
        gint status = 0;
        gboolean ok = g_spawn_sync(NULL, (gchar **)argv, NULL, out ? 0 : G_SPAWN_STDOUT_TO_DEV_NULL, NULL, NULL, out,
                                   NULL, &status, &err) &&
                      status == 0;
        if (err) {
            say("%s: %s", prog, err->message);
            g_clear_error(&err);
        }
        g_free(path);
        return ok;
    }
    say("docked mode: the phone has no kwriteconfig6 or kwriteconfig5");
    return FALSE;
}

static gboolean rest_autostart(void);
static void start_guard(void);

/*
 * Plasma Mobile's own docked-mode switch (MobileShellSettings::setConvergenceModeEnabled)
 * also runs plasma-mobile-envmanager --apply-settings, which gives KWin docked mode's rules
 * (windows can be dragged, open centred rather than maximized, have a title bar's buttons)
 * or the phone's back, and has KWin reload them. Writing the setting alone left KWin with
 * the phone's: no window could be dragged on the computer's screen (S24, 2026-11-16).
 */
static void apply_docked_rules(void) {
    char *prog = g_find_program_in_path("plasma-mobile-envmanager");
    if (!prog) return;
    gchar **env = app.session_env ? g_strdupv(app.session_env) : g_get_environ();
    env = g_environ_unsetenv(env, "XDG_ACTIVATION_TOKEN");
    env = g_environ_unsetenv(env, "DESKTOP_STARTUP_ID");
    env = g_environ_setenv(env, "QT_QPA_PLATFORM", "offscreen", TRUE); /* as startplasmamobile runs it */
    const char *argv[] = {prog, "--apply-settings", NULL};
    gint status = 0;
    GError *err = NULL;
    gboolean ok = g_spawn_sync(NULL, (gchar **)argv, env, G_SPAWN_STDOUT_TO_DEV_NULL | G_SPAWN_STDERR_TO_DEV_NULL,
                               NULL, NULL, NULL, NULL, &status, &err) &&
                  g_spawn_check_wait_status(status, NULL);
    if (!ok) say("docked mode: KWin's rules for it weren't applied (%s)", err ? err->message : "the envmanager failed");
    g_clear_error(&err);
    g_strfreev(env);
    g_free(prog);
}

/* Docked mode on for this computer's screen, keeping what the phone had. Plasma Mobile only. */
static void dock(void) {
    if (!plasma_mobile()) return;
    char *file = docked_file();
    char *before = NULL;
    if (!g_file_get_contents(file, &before, NULL, NULL)) {
        /* No session left one: this is the phone's own setting. */
        char *now = NULL;
        gboolean read = docked_config(NULL, &now);
        if (read && now && strcmp(g_strstrip(now), "true") == 0) {
            say("docked mode: on already");
            /* KWin's rules may still be the phone's (a session that only wrote the setting) */
            apply_docked_rules();
            read = FALSE;
        }
        g_free(now);
        char *dir = g_path_get_dirname(file);
        if (read && (g_mkdir_with_parents(dir, 0700) != 0 || !g_file_set_contents(file, "false", -1, NULL))) {
            say("docked mode: left as it is (nowhere to keep what the phone had)");
            read = FALSE;
        }
        g_free(dir);
        if (!read) {
            g_free(file);
            return;
        }
        before = g_strdup("false");
    }
    g_strstrip(before);
    rest_autostart();
    if (app.guard_fd == 0) start_guard();
    if (docked_config("true", NULL)) {
        apply_docked_rules();
        say("docked mode: on while this computer has the screen (the phone had it %s)",
            strcmp(before, "true") == 0 ? "on" : "off");
    }
    g_free(before);
    g_free(file);
}

/* Docked mode back as the phone had it, if a computer's session turned it on. */
static void undock(void) {
    char *file = docked_file();
    char *before = NULL;
    if (g_file_get_contents(file, &before, NULL, NULL)) {
        gboolean on = strcmp(g_strstrip(before), "true") == 0;
        if (docked_config(on ? "true" : "false", NULL)) {
            apply_docked_rules();
            unlink(file);
            say("docked mode: back %s, as the phone had it", on ? "on" : "off");
        }
        g_free(before);
    }
    g_free(file);
}

static void wlr_close_screen(void);

static void close_screen(void) {
    if (app.wlr) wlr_close_screen();
    if (app.stream) {
        if (!app.closed) zkde_screencast_stream_unstable_v1_close(app.stream);
        else zkde_screencast_stream_unstable_v1_destroy(app.stream);
        app.stream = NULL;
    }
    if (app.display) wl_display_flush(app.display);
}

/* ── Phosh ─────────────────────────────────────────────────────────────── */

/*
 * phoc has no virtual output to ask for, but it is wlroots, and wlroots runs a
 * headless output beside the phone's panel when the session starts with
 * WLR_BACKENDS=…,headless. phosh-session reads the user's ~/.phoshdebug before
 * it starts phoc, so that is where the backend is added (setup_phosh), and an
 * autostart entry turns the output off at each login (`rest`) until a
 * computer asks for it.
 *
 * Phosh draws its shell (bar, app grid, lock screen) on its primary screen
 * only; the others get the wallpaper. So the headless output is made primary
 * while a computer has it, through Phosh's DisplayConfig (Mutter's D-Bus API,
 * which Phosh implements): only a "persistent" config moves the primary, Phosh
 * keeps it in memory and saves nothing, and it refuses any change while the
 * phone is locked. The phone's panel gets the primary back at the end. A
 * primary screen nobody can see would leave the phone's own screen with only
 * its wallpaper, so a guard process puts it back if this one dies first.
 *
 * The picture: wlr-screencopy into a wl_shm buffer of ours, copied into
 * GStreamer through an appsrc, the encoder as for KWin. The input: a virtual
 * pointer tied to the output, and a virtual keyboard with the seat's own
 * keymap, whose modifiers wlroots leaves to its client.
 */

#define DC_NAME "org.gnome.Mutter.DisplayConfig"
#define DC_PATH "/org/gnome/Mutter/DisplayConfig"
#define PHOSHDEBUG_MARK "# Open Android DeX: a screen for a computer"

static void stop_video(void);
static gboolean start_video(GString *why);

static uint32_t ms32(void) { return (uint32_t)(g_get_monotonic_time() / 1000); }

enum { CFG_PENDING, CFG_OK, CFG_FAILED, CFG_CANCELLED };

static void cfg_succeeded(void *d, struct zwlr_output_configuration_v1 *c) { *(int *)d = CFG_OK; }
static void cfg_failed(void *d, struct zwlr_output_configuration_v1 *c) { *(int *)d = CFG_FAILED; }
static void cfg_cancelled(void *d, struct zwlr_output_configuration_v1 *c) { *(int *)d = CFG_CANCELLED; }
static const struct zwlr_output_configuration_v1_listener cfg_listener = {
    .succeeded = cfg_succeeded,
    .failed = cfg_failed,
    .cancelled = cfg_cancelled,
};

/*
 * One configuration of every screen the compositor has (wlr-output-management
 * takes all or none): `ours` on at w × h pixels and scale s, right of the
 * others, or off; the others as they are.
 */
static struct zwlr_output_configuration_v1 *build_cfg(struct head *ours, gboolean on, int w, int h, double s) {
    struct zwlr_output_configuration_v1 *c = zwlr_output_manager_v1_create_configuration(app.om, app.om_serial);
    int right = 0;
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        if (hd == ours || !hd->enabled || !hd->current) continue;
        int lw = (int)lround((hd->transform % 2 ? hd->current->h : hd->current->w) / (hd->scale > 0 ? hd->scale : 1.0));
        right = MAX(right, hd->x + lw);
    }
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        if (hd == ours && on) {
            struct zwlr_output_configuration_head_v1 *ch = zwlr_output_configuration_v1_enable_head(c, hd->wl);
            zwlr_output_configuration_head_v1_set_custom_mode(ch, w, h, 60000);
            zwlr_output_configuration_head_v1_set_scale(ch, wl_fixed_from_double(s));
            zwlr_output_configuration_head_v1_set_transform(ch, WL_OUTPUT_TRANSFORM_NORMAL);
            zwlr_output_configuration_head_v1_set_position(ch, right, 0);
            zwlr_output_configuration_head_v1_destroy(ch);
        } else if (hd != ours && hd->enabled) {
            zwlr_output_configuration_head_v1_destroy(zwlr_output_configuration_v1_enable_head(c, hd->wl));
        } else {
            zwlr_output_configuration_v1_disable_head(c, hd->wl);
        }
    }
    return c;
}

/* Apply and wait for the answer (setting up and putting back: not on the main loop). */
static gboolean apply_sync(struct head *ours, gboolean on, int w, int h, double s) {
    for (int attempt = 0; attempt < 4; attempt++) {
        int result = CFG_PENDING;
        struct zwlr_output_configuration_v1 *c = build_cfg(ours, on, w, h, s);
        zwlr_output_configuration_v1_add_listener(c, &cfg_listener, &result);
        zwlr_output_configuration_v1_apply(c);
        gint64 deadline = g_get_monotonic_time() + 5 * G_USEC_PER_SEC;
        while (result == CFG_PENDING && g_get_monotonic_time() < deadline)
            if (dispatch_for(100) < 0) break;
        zwlr_output_configuration_v1_destroy(c);
        if (result == CFG_OK) {
            wl_display_roundtrip(app.display);
            wl_display_roundtrip(app.display);
            return TRUE;
        }
        if (result != CFG_CANCELLED) {
            say("screens: the compositor %s %s %dx%d", result == CFG_FAILED ? "refused" : "didn't answer",
                on ? "turning on" : "turning off", w, h);
            return FALSE;
        }
        /* Changed meanwhile (Phosh configures screens too): again, with the new serial. */
        wl_display_roundtrip(app.display);
    }
    return FALSE;
}

/* The size the screen had last, kept for `rest`: the next computer most likely asks for it again. */
static char *size_file(void) { return g_build_filename(g_get_user_cache_dir(), "openandroiddex", "phosh-screen", NULL); }

static void remember_size(int w, int h, double s) {
    char *f = size_file();
    char *dir = g_path_get_dirname(f);
    char num[G_ASCII_DTOSTR_BUF_SIZE];
    char *line = g_strdup_printf("%d %d %s\n", w, h, g_ascii_formatd(num, sizeof num, "%.4f", s));
    g_mkdir_with_parents(dir, 0700);
    g_file_set_contents(f, line, -1, NULL);
    g_free(line);
    g_free(dir);
    g_free(f);
}

static gboolean remembered_size(int *w, int *h, double *s) {
    char *f = size_file(), *text = NULL;
    gboolean ok = FALSE;
    if (g_file_get_contents(f, &text, NULL, NULL)) {
        gchar **v = g_strsplit(g_strstrip(text), " ", 3);
        if (g_strv_length(v) == 3) {
            *w = atoi(v[0]);
            *h = atoi(v[1]);
            *s = g_ascii_strtod(v[2], NULL);
            ok = *w >= 320 && *h >= 240 && *w <= 7680 && *h <= 4320 && *s >= 0.5 && *s <= 4;
        }
        g_strfreev(v);
    }
    g_free(text);
    g_free(f);
    return ok;
}

/* While serving: a new size, one at a time, the latest asked for. */
static void size_next(void);
static void acfg_result(struct zwlr_output_configuration_v1 *c, int result) {
    zwlr_output_configuration_v1_destroy(c);
    app.cfg = NULL;
    if (result == CFG_OK) {
        say("size: %dx%d in place", app.cfg_w, app.cfg_h);
        remember_size(app.cfg_w, app.cfg_h, app.scale);
    }
    else if (result == CFG_CANCELLED && !app.next_cfg_w) {
        app.next_cfg_w = app.cfg_w;
        app.next_cfg_h = app.cfg_h;
    } else if (result == CFG_FAILED) say("size: the compositor refused %dx%d", app.cfg_w, app.cfg_h);
    size_next();
}
static void acfg_succeeded(void *d, struct zwlr_output_configuration_v1 *c) { acfg_result(c, CFG_OK); }
static void acfg_failed(void *d, struct zwlr_output_configuration_v1 *c) { acfg_result(c, CFG_FAILED); }
static void acfg_cancelled(void *d, struct zwlr_output_configuration_v1 *c) { acfg_result(c, CFG_CANCELLED); }
static const struct zwlr_output_configuration_v1_listener acfg_listener = {
    .succeeded = acfg_succeeded,
    .failed = acfg_failed,
    .cancelled = acfg_cancelled,
};

static void size_next(void) {
    if (app.cfg || !app.next_cfg_w || app.finishing) return;
    struct head *hd = head_named(app.screen_name);
    if (!hd) return;
    app.cfg_w = app.next_cfg_w;
    app.cfg_h = app.next_cfg_h;
    app.next_cfg_w = app.next_cfg_h = 0;
    app.cfg = build_cfg(hd, TRUE, app.cfg_w, app.cfg_h, app.scale);
    zwlr_output_configuration_v1_add_listener(app.cfg, &acfg_listener, NULL);
    zwlr_output_configuration_v1_apply(app.cfg);
    wl_display_flush(app.display);
}

/* The screen at w × h pixels: the next frame comes at that size, and the pipeline follows it (push_shot). */
static void wlr_resize(int w, int h) {
    app.next_cfg_w = w;
    app.next_cfg_h = h;
    size_next();
}

/* Phosh's DisplayConfig, with no wait for a session bus that isn't there. */
static GVariant *dc_call(const char *method, GVariant *args, const GVariantType *reply, GError **err) {
    if (!app.bus) app.bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
    if (!app.bus) {
        g_set_error_literal(err, G_IO_ERROR, G_IO_ERROR_NOT_CONNECTED, "no session bus");
        if (args) g_variant_unref(g_variant_ref_sink(args));
        return NULL;
    }
    return g_dbus_connection_call_sync(app.bus, DC_NAME, DC_PATH, DC_NAME, method, args, reply,
                                       G_DBUS_CALL_FLAGS_NONE, 5000, NULL, err);
}

/* A screen's mode as Phosh names it, and its size. */
struct dc_mode {
    char *id;
    int w, h;
};

static void dc_mode_free(gpointer p) {
    struct dc_mode *m = p;
    g_free(m->id);
    g_free(m);
}

/*
 * The shell on `primary` (NULL: where it is), every screen kept as it is except
 * `drop`, which is left out and so turned off (NULL: none), and `add`, a screen
 * that is off, turned on right of the others at app.scale in its first mode
 * (NULL: none). With `was`, the primary before.
 */
static gboolean phosh_layout(const char *primary, const char *drop, const char *add, char **was, GString *why) {
    GError *err = NULL;
    GVariant *st = dc_call("GetCurrentState", NULL, NULL, &err);
    if (!st) {
        g_string_printf(why, "Phosh's display settings didn't answer: %s", err ? err->message : "?");
        g_clear_error(&err);
        return FALSE;
    }
    guint32 serial = 0;
    GVariant *monitors = NULL, *logical = NULL;
    g_variant_get(st, "(u@a((ssss)a(siiddada{sv})a{sv})@a(iiduba(ssss)a{sv})@a{sv})", &serial, &monitors, &logical,
                  NULL);
    /* The mode each screen has now (or, for one that is off, its first), by its id: Phosh wants it named. */
    GHashTable *mode_of = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, dc_mode_free);
    GVariantIter it;
    GVariant *mon;
    g_variant_iter_init(&it, monitors);
    while ((mon = g_variant_iter_next_value(&it))) {
        const char *conn = NULL;
        GVariant *modes = NULL;
        g_variant_get(mon, "((&s&s&s&s)@a(siiddada{sv})@a{sv})", &conn, NULL, NULL, NULL, &modes, NULL);
        GVariantIter mit;
        GVariant *mode;
        g_variant_iter_init(&mit, modes);
        while ((mode = g_variant_iter_next_value(&mit))) {
            const char *id = NULL;
            gint32 w = 0, h = 0;
            GVariant *props = NULL;
            gboolean current = FALSE;
            g_variant_get(mode, "(&siidd@ad@a{sv})", &id, &w, &h, NULL, NULL, NULL, &props);
            if (props) g_variant_lookup(props, "is-current", "b", &current);
            if (current || !g_hash_table_contains(mode_of, conn)) {
                struct dc_mode *m = g_new0(struct dc_mode, 1);
                m->id = g_strdup(id);
                m->w = w;
                m->h = h;
                g_hash_table_replace(mode_of, g_strdup(conn), m);
            }
            if (props) g_variant_unref(props);
            g_variant_unref(mode);
        }
        g_variant_unref(modes);
        g_variant_unref(mon);
    }
    GVariantBuilder lb;
    g_variant_builder_init(&lb, G_VARIANT_TYPE("a(iiduba(ssa{sv}))"));
    gboolean found = FALSE;
    int right = 0;
    g_variant_iter_init(&it, logical);
    GVariant *lm;
    while ((lm = g_variant_iter_next_value(&it))) {
        gint32 x, y;
        gdouble scale;
        guint32 transform;
        gboolean is_primary;
        GVariant *mons = NULL;
        g_variant_get(lm, "(iidub@a(ssss)@a{sv})", &x, &y, &scale, &transform, &is_primary, &mons, NULL);
        const char *conn = NULL;
        if (g_variant_n_children(mons) > 0) g_variant_get_child(mons, 0, "(&s&s&s&s)", &conn, NULL, NULL, NULL);
        if (conn && is_primary && was) *was = g_strdup(conn);
        struct dc_mode *m = conn ? g_hash_table_lookup(mode_of, conn) : NULL;
        if (conn && m && !(drop && strcmp(conn, drop) == 0) && !(add && strcmp(conn, add) == 0)) {
            gboolean p = primary ? strcmp(conn, primary) == 0 : is_primary;
            found |= p;
            right = MAX(right, x + (int)lround((transform % 2 ? m->h : m->w) / (scale > 0 ? scale : 1.0)));
            GVariantBuilder mb;
            g_variant_builder_init(&mb, G_VARIANT_TYPE("a(ssa{sv})"));
            g_variant_builder_add(&mb, "(ss@a{sv})", conn, m->id, g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0));
            g_variant_builder_add(&lb, "(iidub@a(ssa{sv}))", x, y, scale, transform, p, g_variant_builder_end(&mb));
        }
        g_variant_unref(mons);
        g_variant_unref(lm);
    }
    struct dc_mode *am = add ? g_hash_table_lookup(mode_of, add) : NULL;
    if (am) {
        gboolean p = primary && strcmp(add, primary) == 0;
        found |= p;
        GVariantBuilder mb;
        g_variant_builder_init(&mb, G_VARIANT_TYPE("a(ssa{sv})"));
        g_variant_builder_add(&mb, "(ss@a{sv})", add, am->id, g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0));
        g_variant_builder_add(&lb, "(iidub@a(ssa{sv}))", right, 0, app.scale > 0 ? app.scale : 1.0, 0u, p,
                              g_variant_builder_end(&mb));
    } else if (add) {
        g_string_printf(why, "Phosh doesn't list the screen %s.", add);
        g_variant_builder_clear(&lb);
        g_variant_unref(monitors);
        g_variant_unref(logical);
        g_variant_unref(st);
        g_hash_table_unref(mode_of);
        return FALSE;
    }
    g_variant_unref(monitors);
    g_variant_unref(logical);
    g_variant_unref(st);
    g_hash_table_unref(mode_of);
    if (!found) {
        g_variant_builder_clear(&lb);
        g_string_printf(why, "Phosh has no screen %s to put its shell on.", primary ? primary : "(its own)");
        return FALSE;
    }
    GVariant *r = dc_call("ApplyMonitorsConfig",
                          g_variant_new("(uu@a(iiduba(ssa{sv}))@a{sv})", serial, 2u, g_variant_builder_end(&lb),
                                        g_variant_new_array(G_VARIANT_TYPE("{sv}"), NULL, 0)),
                          NULL, &err);
    if (!r) {
        g_string_printf(why, "Phosh didn't take the screens' new layout: %s", err ? err->message : "?");
        g_clear_error(&err);
        return FALSE;
    }
    g_variant_unref(r);
    return TRUE;
}

/*
 * logind's LockedHint for this user's session on the phone's own seat, which
 * Phosh sets as it locks (seen right on 2026-11-15, where GNOME's ScreenSaver
 * GetActive said false on the lock screen). FALSE when there is no such answer.
 */
static gboolean session_locked_hint(gboolean *locked) {
    GDBusConnection *sys = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, NULL);
    if (!sys) return FALSE;
    gboolean found = FALSE;
    GVariant *r = g_dbus_connection_call_sync(sys, "org.freedesktop.login1", "/org/freedesktop/login1",
                                              "org.freedesktop.login1.Manager", "ListSessions", NULL,
                                              G_VARIANT_TYPE("(a(susso))"), G_DBUS_CALL_FLAGS_NONE, 2000, NULL, NULL);
    if (r) {
        GVariantIter *it = NULL;
        const char *id, *user, *seat, *path;
        guint32 uid;
        g_variant_get(r, "(a(susso))", &it);
        while (!found && g_variant_iter_next(it, "(&su&s&s&o)", &id, &uid, &user, &seat, &path)) {
            if (uid != getuid() || strcmp(seat, "seat0") != 0) continue;
            GVariant *v = g_dbus_connection_call_sync(sys, "org.freedesktop.login1", path, "org.freedesktop.DBus.Properties",
                                                      "Get", g_variant_new("(ss)", "org.freedesktop.login1.Session", "LockedHint"),
                                                      G_VARIANT_TYPE("(v)"), G_DBUS_CALL_FLAGS_NONE, 2000, NULL, NULL);
            if (v) {
                GVariant *b = NULL;
                g_variant_get(v, "(v)", &b);
                if (b && g_variant_is_of_type(b, G_VARIANT_TYPE_BOOLEAN)) {
                    *locked = g_variant_get_boolean(b);
                    found = TRUE;
                }
                if (b) g_variant_unref(b);
                g_variant_unref(v);
            }
        }
        g_variant_iter_free(it);
        g_variant_unref(r);
    }
    g_object_unref(sys);
    return found;
}

/* Phosh's lock screen is up. It refuses to move screens while it is. */
static gboolean phone_locked(void) {
    gboolean locked = FALSE;
    if (session_locked_hint(&locked)) return locked;
    if (!app.bus) return FALSE;
    GVariant *r = g_dbus_connection_call_sync(app.bus, "org.gnome.ScreenSaver", "/org/gnome/ScreenSaver",
                                              "org.gnome.ScreenSaver", "GetActive", NULL, G_VARIANT_TYPE("(b)"),
                                              G_DBUS_CALL_FLAGS_NONE, 2000, NULL, NULL);
    gboolean active = FALSE;
    if (r) {
        g_variant_get(r, "(b)", &active);
        g_variant_unref(r);
    }
    return active;
}

/* The shell onto the computer's screen: now, or once the phone is unlocked (retry_primary). */
static void move_primary(void) {
    static gboolean told;
    if (!app.primary_wanted || app.primary_moved || app.finishing) return;
    if (phone_locked()) {
        if (!told) say("phosh: the phone is locked; its shell comes to this screen once it is unlocked");
        told = TRUE;
        return;
    }
    GString *why = g_string_new(NULL);
    char *was = NULL;
    if (phosh_layout(app.screen_name, NULL, NULL, &was, why)) {
        if (was && strcmp(was, app.screen_name) != 0) {
            g_free(app.home_name);
            app.home_name = g_strdup(was);
        }
        app.primary_moved = TRUE;
        app.primary_wanted = FALSE;
        say("phosh: the shell is on this computer's screen (it was on %s)", app.home_name ? app.home_name : "?");
    } else {
        say("phosh: %s", why->str);
    }
    g_free(was);
    g_string_free(why, TRUE);
}

static void on_lock_changed(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                            const char *signal, GVariant *params, gpointer d) {
    gboolean active = FALSE;
    g_variant_get(params, "(b)", &active);
    if (!active) move_primary();
}

/* Once a second while the shell waits to come over: ScreenSaver's signal is not to be relied on (see above). */
static gboolean retry_primary(gpointer d) {
    if (app.finishing || !app.primary_wanted || app.primary_moved) return G_SOURCE_REMOVE;
    move_primary();
    return app.primary_moved ? G_SOURCE_REMOVE : G_SOURCE_CONTINUE;
}

/* A screen that isn't ours, for the shell to go back to: the phone's panel. */
static const char *home_screen(struct head *ours) {
    if (app.home_name && head_named(app.home_name)) return app.home_name;
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        if (hd != ours && hd->enabled && hd->name && !g_str_has_prefix(hd->name, "HEADLESS-")) return hd->name;
    }
    return NULL;
}

/*
 * The phone as it was: the shell on its panel, the headless output off. Phosh
 * first, which turns the output off by leaving it out; wlr-output-management
 * if Phosh won't (its lock screen is up): Phosh then finds its primary gone
 * and takes the panel.
 */
static void put_phone_back(struct head *ours) {
    if (!ours || !ours->enabled) return;
    const char *home = home_screen(ours);
    GString *why = g_string_new(NULL);
    if (home && phosh_layout(home, ours->name, NULL, NULL, why)) {
        gint64 deadline = g_get_monotonic_time() + 3 * G_USEC_PER_SEC;
        while (ours->enabled && g_get_monotonic_time() < deadline)
            if (dispatch_for(100) < 0) break;
    } else if (why->len) {
        say("phosh: %s", why->str);
    }
    if (ours->enabled && !apply_sync(ours, FALSE, 0, 0, 0)) say("screens: %s stays on", ours->name);
    else say("screens: %s is off, the shell is on %s", ours->name, home ? home : "the phone's screen");
    g_string_free(why, TRUE);
}

/* ~/.phoshdebug and the autostart entry: the headless output from the next login on, off until it is used. */
static gboolean setup_phosh(void) {
    gboolean ok = TRUE;
    char *path = g_build_filename(g_get_home_dir(), ".phoshdebug", NULL);
    char *old = NULL;
    g_file_get_contents(path, &old, NULL, NULL);
    if (!old || !strstr(old, PHOSHDEBUG_MARK)) {
        GString *s = g_string_new(old ? old : "");
        if (s->len && s->str[s->len - 1] != '\n') g_string_append_c(s, '\n');
        g_string_append(s, PHOSHDEBUG_MARK " (wlroots' headless output, off until a computer opens it)\n"
                           "case \",$WLR_BACKENDS,\" in *,headless,*) ;; "
                           "*) WLR_BACKENDS=\"${WLR_BACKENDS:-drm,libinput},headless\" ;; esac\n"
                           "export WLR_BACKENDS\n");
        ok &= g_file_set_contents(path, s->str, -1, NULL);
        g_string_free(s, TRUE);
    }
    g_free(old);
    g_free(path);
    ok &= rest_autostart();
    return ok;
}

/*
 * The login autostart that runs `rest`: on Phosh the headless output off
 * until a computer opens it, on Plasma Mobile docked mode as the phone had it
 * if a session ended without putting it back (a restart, a flat battery).
 */
static gboolean rest_autostart(void) {
    char exe[4096];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (n <= 0) return FALSE;
    exe[n] = 0;
    gboolean ok = TRUE;
    char *dir = g_build_filename(g_get_user_config_dir(), "autostart", NULL);
    char *file = g_build_filename(dir, "com.ccrstech.openandroiddex.pmos-rest.desktop", NULL);
    char *entry = g_strdup_printf("[Desktop Entry]\n"
                                  "Type=Application\n"
                                  "Name=Open Android DeX\n"
                                  "Comment=Puts the phone back after a computer's screen: off until a computer opens "
                                  "it, and Plasma Mobile's docked mode as it was\n"
                                  "Exec=\"%s\" rest\n"
                                  "NoDisplay=true\n"
                                  "OnlyShowIn=Phosh;KDE;\n",
                                  exe);
    char *had = NULL;
    if (!g_file_get_contents(file, &had, NULL, NULL) || strcmp(had, entry) != 0)
        ok = g_mkdir_with_parents(dir, 0755) == 0 && g_file_set_contents(file, entry, -1, NULL);
    g_free(had);
    g_free(entry);
    g_free(file);
    g_free(dir);
    return ok;
}

/* A process of its own that waits for this one to end, however it ends, and then runs `rest`. */
static void start_guard(void) {
    int p[2];
    char exe[4096];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    if (n <= 0 || pipe2(p, O_CLOEXEC) < 0) return;
    exe[n] = 0;
    pid_t pid = fork();
    if (pid == 0) {
        /* Only what is safe between fork and exec: the guard is a fresh program. */
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) {
            dup2(nul, 0);
            dup2(nul, 1);
            dup2(nul, 2);
        }
        if (p[0] == 3) fcntl(3, F_SETFD, 0); /* dup2 onto itself would keep close-on-exec */
        else dup2(p[0], 3);
        setsid();
        execl(exe, "openandroiddex-pmos", "guard", (char *)NULL);
        _exit(127);
    }
    close(p[0]);
    if (pid < 0) {
        close(p[1]);
        return;
    }
    app.guard_fd = p[1];
    say("guard: %d puts the phone's screen back if this ends without doing so", (int)pid);
}

/* The virtual pointer on our screen and the virtual keyboard with the seat's keymap. */
static void wlr_input(void) {
    struct output *o = our_output();
    if (app.vp_manager && app.seat && o) {
        app.vpointer = app.vp_version >= 2
                           ? zwlr_virtual_pointer_manager_v1_create_virtual_pointer_with_output(app.vp_manager, app.seat, o->wl)
                           : zwlr_virtual_pointer_manager_v1_create_virtual_pointer(app.vp_manager, app.seat);
    }
    if (!app.vk_manager || !app.seat) return;
    if (!app.keymap) {
        /* A seat with no keyboard of its own: the default layout. */
        if (!app.xkb) app.xkb = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
        app.keymap = xkb_keymap_new_from_names(app.xkb, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS);
        if (!app.keymap) return;
        g_free(app.keymap_text);
        app.keymap_text = xkb_keymap_get_as_string(app.keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
        index_keymap();
    }
    gsize size = strlen(app.keymap_text) + 1;
    int fd = memfd_create("openandroiddex-keymap", MFD_CLOEXEC);
    if (fd < 0 || write(fd, app.keymap_text, size) != (ssize_t)size) {
        if (fd >= 0) close(fd);
        say("keyboard: no keymap for the virtual keyboard");
        return;
    }
    app.vkeyboard = zwp_virtual_keyboard_manager_v1_create_virtual_keyboard(app.vk_manager, app.seat);
    zwp_virtual_keyboard_v1_keymap(app.vkeyboard, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)size);
    close(fd);
    app.vk_state = xkb_state_new(app.keymap);
}

/*
 * wlroots 0.19's output management asserts (head_send_state: found) when a
 * head with a custom mode is turned on while a client is bound that has no
 * mode object for it, which is every client that bound while it was off,
 * as long as another client (Phosh) still holds one from before: phoc
 * aborts and the session ends (seen on the S24, 2026-11-15, twice). So this
 * program lets go of output management, has Phosh turn the screen on, and
 * binds again once the screen is there.
 */
static void release_om(void) {
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        for (GList *m = hd->modes; m; m = m->next) {
            struct head_mode *hm = m->data;
            if (zwlr_output_mode_v1_get_version(hm->wl) >= ZWLR_OUTPUT_MODE_V1_RELEASE_SINCE_VERSION)
                zwlr_output_mode_v1_release(hm->wl);
            else zwlr_output_mode_v1_destroy(hm->wl);
            g_free(hm);
        }
        g_list_free(hd->modes);
        if (zwlr_output_head_v1_get_version(hd->wl) >= ZWLR_OUTPUT_HEAD_V1_RELEASE_SINCE_VERSION)
            zwlr_output_head_v1_release(hd->wl);
        else zwlr_output_head_v1_destroy(hd->wl);
        g_free(hd->name);
        g_free(hd);
    }
    g_list_free(app.heads);
    app.heads = NULL;
    if (app.om) {
        zwlr_output_manager_v1_stop(app.om);
        zwlr_output_manager_v1_destroy(app.om);
        app.om = NULL;
    }
    wl_display_roundtrip(app.display);
}

static void rebind_om(void) {
    app.om = wl_registry_bind(app.registry, app.om_global, &zwlr_output_manager_v1_interface, app.om_version);
    zwlr_output_manager_v1_add_listener(app.om, &om_listener, NULL);
    wl_display_roundtrip(app.display);
    wl_display_roundtrip(app.display);
}

/*
 * Phosh turns `name` on beside its screens, its shell left where it is; wait
 * for the compositor to show it. Turned on and made primary in one config, the
 * screen's wallpaper came out zoomed in (seen 2026-11-15): Phosh draws the
 * primary screen's wallpaper for its "usable area", not yet the new screen's.
 * So the shell moves in a config of its own, in wlr_make_screen, which also
 * has the order of shell and size.
 */
static gboolean phosh_turn_on(const char *name, GString *why) {
    /* Phosh refuses while its lock screen is up ("Can't configure outputs when
     * locked"), whether or not its ScreenSaver says so: the person may be
     * unlocking it now, so it is asked again for a while. */
    gint64 give_up = g_get_monotonic_time() + 25 * G_USEC_PER_SEC;
    gboolean told = FALSE;
    char *was = NULL;
    for (;;) {
        if (!phone_locked()) {
            release_om();
            g_string_truncate(why, 0);
            if (phosh_layout(NULL, NULL, name, &was, why)) break;
            rebind_om();
            g_clear_pointer(&was, g_free);
            if (!strstr(why->str, "locked")) return FALSE;
        }
        if (g_get_monotonic_time() > give_up) {
            g_string_assign(why, "The phone is locked. Unlock it, then open its desktop here again.");
            return FALSE;
        }
        if (!told) say("phosh: the phone is locked; waiting for it to be unlocked");
        told = TRUE;
        g_usleep(500 * 1000);
    }
    if (was && strcmp(was, name) != 0) {
        g_free(app.home_name);
        app.home_name = g_strdup(was);
    }
    g_free(was);
    gint64 deadline = g_get_monotonic_time() + 5 * G_USEC_PER_SEC;
    while (!our_output() && g_get_monotonic_time() < deadline)
        if (dispatch_for(100) < 0) break;
    rebind_om();
    if (!our_output()) {
        g_string_assign(why, "Phosh didn't turn on the screen for this computer.");
        return FALSE;
    }
    say("phosh: turned %s on beside %s", name, app.home_name ? app.home_name : "?");
    return TRUE;
}

static gboolean wlr_make_screen(GString *why) {
    struct head *hd = headless_head();
    if (!hd) {
        gboolean phosh = (getenv("XDG_CURRENT_DESKTOP") && strstr(getenv("XDG_CURRENT_DESKTOP"), "Phosh"));
        if (phosh && setup_phosh()) {
            g_string_assign(why, "The phone is ready to make a screen for this computer from its next login. "
                                 "Log out on the phone (or restart it), then open its desktop here again.");
        } else {
            g_string_assign(why, "The phone's desktop has no screen to spare for this computer. On Phosh, Open "
                                 "Android DeX sets one up in ~/.phoshdebug; this desktop isn't Phosh.");
        }
        return FALSE;
    }
    setup_phosh(); /* the autostart entry, for a phone whose headless output was set up by hand */
    app.screen_name = g_strdup(hd->name);
    if (!app.bus) app.bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
    if (!hd->enabled) {
        /* Off, as `rest` leaves it: Phosh turns it on (see release_om). */
        if (!phosh_turn_on(app.screen_name, why)) return FALSE;
        hd = head_named(app.screen_name);
        if (!hd) {
            g_string_assign(why, "The phone's screen for this computer went away as it was turned on.");
            return FALSE;
        }
    }
    /* Its wl_output: the registry announces it once the output is on. */
    gint64 deadline = g_get_monotonic_time() + 3 * G_USEC_PER_SEC;
    while (!our_output() && g_get_monotonic_time() < deadline)
        if (dispatch_for(100) < 0) break;
    if (!our_output()) {
        g_string_assign(why, "The phone's desktop turned on a screen for this computer but never showed it.");
        put_phone_back(hd);
        return FALSE;
    }
    start_guard();
    wlr_input();
    /* Phosh's shell onto it, and back to it after any lock the phone was in. */
    app.primary_wanted = TRUE;
    if (app.bus)
        app.lock_watch = g_dbus_connection_signal_subscribe(app.bus, NULL, "org.gnome.ScreenSaver", "ActiveChanged",
                                                            "/org/gnome/ScreenSaver", NULL, G_DBUS_SIGNAL_FLAGS_NONE,
                                                            on_lock_changed, NULL, NULL);
    /*
     * The shell first, then the size. Phosh sizes the primary screen's
     * wallpaper from its usable area, and a screen sized and then made primary
     * within the same second came out with it zoomed in (2026-11-15). Made
     * primary at the size it came on at, given a moment, and then sized, it is
     * drawn right, as the window's own later sizes always were. No frame goes
     * to the window before all this: the picture starts after (cmd_serve).
     */
    if (!hd->current || hd->current->w != app.width || hd->current->h != app.height || fabs(hd->scale - app.scale) > 0.01) {
        move_primary();
        if (app.primary_moved) {
            gint64 settle = g_get_monotonic_time() + 2 * G_USEC_PER_SEC;
            while (g_get_monotonic_time() < settle)
                if (dispatch_for(100) < 0) break;
        }
        hd = head_named(app.screen_name);
        if (!hd) {
            g_string_assign(why, "The phone's screen for this computer went away as it was turned on.");
            return FALSE;
        }
    }
    if (!apply_sync(hd, TRUE, app.width, app.height, app.scale)) {
        g_string_assign(why, "The phone's desktop wouldn't size its screen for this computer.");
        put_phone_back(hd);
        return FALSE;
    }
    remember_size(app.width, app.height, app.scale);
    say("screen %s: %dx%d pixels at scale %.3f (Phosh: a headless output)%s%s", hd->name, app.width, app.height,
        app.scale, app.vpointer ? "" : ", no pointer", app.vkeyboard ? "" : ", no keyboard");
    move_primary();
    return TRUE;
}

/* ── Phosh: the picture ────────────────────────────────────────────────── */

static const char *shm_gst_format(uint32_t f) {
    switch (f) {
    case WL_SHM_FORMAT_XRGB8888: return "BGRx";
    case WL_SHM_FORMAT_ARGB8888: return "BGRA";
    case WL_SHM_FORMAT_XBGR8888: return "RGBx";
    case WL_SHM_FORMAT_ABGR8888: return "RGBA";
    default: return NULL;
    }
}

static void shot_free(void) {
    if (app.shot.wl) wl_buffer_destroy(app.shot.wl);
    if (app.shot.data) munmap(app.shot.data, app.shot.size);
    memset(&app.shot, 0, sizeof app.shot);
}

static gboolean shot_alloc(uint32_t format, int w, int h, int stride) {
    if (app.shot.wl && app.shot.format == format && app.shot.w == w && app.shot.h == h && app.shot.stride == stride)
        return TRUE;
    shot_free();
    gsize size = (gsize)stride * h;
    int fd = memfd_create("openandroiddex-shot", MFD_CLOEXEC);
    if (fd < 0) return FALSE;
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return FALSE;
    }
    guint8 *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        close(fd);
        return FALSE;
    }
    struct wl_shm_pool *pool = wl_shm_create_pool(app.shm, fd, (int32_t)size);
    app.shot.wl = wl_shm_pool_create_buffer(pool, 0, w, h, stride, format);
    wl_shm_pool_destroy(pool);
    close(fd);
    app.shot.data = data;
    app.shot.size = size;
    app.shot.format = format;
    app.shot.w = w;
    app.shot.h = h;
    app.shot.stride = stride;
    return TRUE;
}

static void capture_next(void);
static int capture_failures;

static gboolean capture_again(gpointer d) {
    capture_next();
    return G_SOURCE_REMOVE;
}

/* The pipeline built again for frames of a new size or kind, as KWin's stream gets one (restart_stream). */
static void rebuild_for_shot(void) {
    stop_video();
    gst_buffer_replace(&app.last_buf, NULL);
    app.src_w = app.shot.w;
    app.src_h = app.shot.h;
    app.src_format = app.shot.format;
    GString *why = g_string_new(NULL);
    if (!start_video(why)) {
        say("video: the picture would not start again at %dx%d: %s", app.src_w, app.src_h, why->str);
        send_state("stopped", why->str);
        finish(1);
    }
    g_string_free(why, TRUE);
}

static void push_buffer(GstBuffer *b) {
    app.last_push = g_get_monotonic_time();
    if (app.appsrc) gst_app_src_push_buffer(GST_APP_SRC(app.appsrc), b);
    else gst_buffer_unref(b);
}

/* The frame wlroots just copied into our buffer, into the encoder: rows in order, packed. */
static void push_shot(void) {
    if (!app.pipeline || app.finishing || !shm_gst_format(app.shot.format)) return;
    if (app.shot.w != app.src_w || app.shot.h != app.src_h || app.shot.format != app.src_format) {
        rebuild_for_shot();
        if (!app.appsrc) return;
    }
    gsize row = (gsize)app.shot.w * 4;
    GstBuffer *b = gst_buffer_new_allocate(NULL, row * app.shot.h, NULL);
    GstMapInfo m;
    if (!b || !gst_buffer_map(b, &m, GST_MAP_WRITE)) {
        if (b) gst_buffer_unref(b);
        return;
    }
    for (int y = 0; y < app.shot.h; y++) {
        int sy = app.shot_yinvert ? app.shot.h - 1 - y : y;
        memcpy(m.data + (gsize)y * row, app.shot.data + (gsize)sy * app.shot.stride, row);
    }
    gst_buffer_unmap(b, &m);
    gst_buffer_replace(&app.last_buf, b);
    push_buffer(b);
}

static void shot_copy(struct zwlr_screencopy_frame_v1 *f) {
    if (!shm_gst_format(app.shot_format) || !shot_alloc(app.shot_format, app.shot_w, app.shot_h, app.shot_stride)) {
        say("video: the phone's screen comes as wl_shm format 0x%x, %dx%d, which this can't take", app.shot_format,
            app.shot_w, app.shot_h);
        zwlr_screencopy_frame_v1_destroy(f);
        app.frame = NULL;
        app.capturing = FALSE;
        send_state("stopped", "The phone's screen comes in a picture format Open Android DeX can't take.");
        finish(1);
        return;
    }
    /* After the first frame of a size, only a changed screen is copied: KeepAlive sends the still one again. */
    if (app.copier_version >= 2 && app.last_buf && app.shot_w == app.src_w && app.shot_h == app.src_h)
        zwlr_screencopy_frame_v1_copy_with_damage(f, app.shot.wl);
    else
        zwlr_screencopy_frame_v1_copy(f, app.shot.wl);
}

static void shot_buffer(void *d, struct zwlr_screencopy_frame_v1 *f, uint32_t format, uint32_t w, uint32_t h,
                        uint32_t stride) {
    app.shot_format = format;
    app.shot_w = (int)w;
    app.shot_h = (int)h;
    app.shot_stride = (int)stride;
    if (app.copier_version < 3) shot_copy(f);
}
static void shot_flags(void *d, struct zwlr_screencopy_frame_v1 *f, uint32_t flags) {
    app.shot_yinvert = (flags & ZWLR_SCREENCOPY_FRAME_V1_FLAGS_Y_INVERT) != 0;
}
static void shot_ready(void *d, struct zwlr_screencopy_frame_v1 *f, uint32_t hi, uint32_t lo, uint32_t ns) {
    zwlr_screencopy_frame_v1_destroy(f);
    app.frame = NULL;
    capture_failures = 0;
    push_shot();
    capture_next();
}
static void shot_failed(void *d, struct zwlr_screencopy_frame_v1 *f) {
    zwlr_screencopy_frame_v1_destroy(f);
    app.frame = NULL;
    app.capturing = FALSE;
    /* The screen changed size or went for a moment. Gone for good: give up after ten seconds. */
    if (++capture_failures > 100) {
        say("video: the phone's screen can't be copied any more");
        send_state("stopped", "The phone's screen for this computer went away.");
        finish(1);
        return;
    }
    g_timeout_add(100, capture_again, NULL);
}
static void shot_damage(void *d, struct zwlr_screencopy_frame_v1 *f, uint32_t x, uint32_t y, uint32_t w, uint32_t h) {}
static void shot_dmabuf(void *d, struct zwlr_screencopy_frame_v1 *f, uint32_t format, uint32_t w, uint32_t h) {}
static void shot_buffer_done(void *d, struct zwlr_screencopy_frame_v1 *f) { shot_copy(f); }
static const struct zwlr_screencopy_frame_v1_listener shot_listener = {
    .buffer = shot_buffer,
    .flags = shot_flags,
    .ready = shot_ready,
    .failed = shot_failed,
    .damage = shot_damage,
    .linux_dmabuf = shot_dmabuf,
    .buffer_done = shot_buffer_done,
};

/* The next frame, while a window is watching; one at a time. */
static void capture_next(void) {
    if (app.frame || app.finishing || !app.copier) return;
    struct output *o = our_output();
    if (!o || !g_atomic_int_get(&app.attached)) {
        app.capturing = FALSE;
        return;
    }
    app.capturing = TRUE;
    app.frame = zwlr_screencopy_manager_v1_capture_output(app.copier, 0, o->wl);
    zwlr_screencopy_frame_v1_add_listener(app.frame, &shot_listener, NULL);
    wl_display_flush(app.display);
}

/* A still screen sends nothing: its last frame again, for a key frame asked for and the encoder's second pass. */
static gboolean keepalive(gpointer d) {
    if (app.finishing) return G_SOURCE_REMOVE;
    if (app.appsrc && app.last_buf && g_atomic_int_get(&app.attached) &&
        g_get_monotonic_time() - app.last_push >= KEEPALIVE_MS * 1000)
        push_buffer(gst_buffer_ref(app.last_buf));
    return G_SOURCE_CONTINUE;
}

/* ── Phosh: input ──────────────────────────────────────────────────────── */

static void wlr_press(guint code, gboolean down) {
    if (!app.vkeyboard) return;
    zwp_virtual_keyboard_v1_key(app.vkeyboard, ms32(), code,
                                down ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
    if (!app.vk_state) return;
    xkb_state_update_key(app.vk_state, code + 8, down ? XKB_KEY_DOWN : XKB_KEY_UP);
    zwp_virtual_keyboard_v1_modifiers(app.vkeyboard, xkb_state_serialize_mods(app.vk_state, XKB_STATE_MODS_DEPRESSED),
                                      xkb_state_serialize_mods(app.vk_state, XKB_STATE_MODS_LATCHED),
                                      xkb_state_serialize_mods(app.vk_state, XKB_STATE_MODS_LOCKED),
                                      xkb_state_serialize_layout(app.vk_state, XKB_STATE_LAYOUT_EFFECTIVE));
}

/* Two of the window's wheel clicks are one notch here (WHEEL_STEP): wlroots counts whole notches. */
static void wlr_wheel(int idx, uint32_t axis, int dir) {
    app.wheel_half[idx ^ 1] = 0;
    if (++app.wheel_half[idx] < 2) return;
    app.wheel_half[idx] = 0;
    zwlr_virtual_pointer_v1_axis_source(app.vpointer, WL_POINTER_AXIS_SOURCE_WHEEL);
    zwlr_virtual_pointer_v1_axis_discrete(app.vpointer, ms32(), axis, wl_fixed_from_double(dir * 2 * WHEEL_STEP), dir);
}

static void wlr_pointer(guint8 mask, int x, int y) {
    if (!app.vpointer) return;
    int pw = g_atomic_int_get(&app.pw), ph = g_atomic_int_get(&app.ph);
    uint32_t t = ms32();
    if (pw > 0 && ph > 0)
        zwlr_virtual_pointer_v1_motion_absolute(app.vpointer, t, (uint32_t)CLAMP(x, 0, pw - 1),
                                                (uint32_t)CLAMP(y, 0, ph - 1), (uint32_t)pw, (uint32_t)ph);
    static const struct {
        guint8 bit;
        uint32_t code;
    } buttons[] = {{1, BTN_LEFT}, {2, BTN_MIDDLE}, {4, BTN_RIGHT}, {128, BTN_SIDE}};
    guint8 changed = mask ^ app.buttons;
    for (gsize i = 0; i < G_N_ELEMENTS(buttons); i++) {
        if (changed & buttons[i].bit)
            zwlr_virtual_pointer_v1_button(app.vpointer, t, buttons[i].code,
                                           (mask & buttons[i].bit) ? WL_POINTER_BUTTON_STATE_PRESSED
                                                                   : WL_POINTER_BUTTON_STATE_RELEASED);
    }
    guint8 pressed = changed & mask;
    if (pressed & 8) wlr_wheel(0, WL_POINTER_AXIS_VERTICAL_SCROLL, -1);
    if (pressed & 16) wlr_wheel(1, WL_POINTER_AXIS_VERTICAL_SCROLL, 1);
    if (pressed & 32) wlr_wheel(2, WL_POINTER_AXIS_HORIZONTAL_SCROLL, -1);
    if (pressed & 64) wlr_wheel(3, WL_POINTER_AXIS_HORIZONTAL_SCROLL, 1);
    zwlr_virtual_pointer_v1_frame(app.vpointer);
    app.buttons = mask;
}

static void wlr_close_screen(void) {
    if (app.frame) {
        zwlr_screencopy_frame_v1_destroy(app.frame);
        app.frame = NULL;
    }
    if (app.vpointer) {
        zwlr_virtual_pointer_v1_destroy(app.vpointer);
        app.vpointer = NULL;
    }
    if (app.vkeyboard) {
        zwp_virtual_keyboard_v1_destroy(app.vkeyboard);
        app.vkeyboard = NULL;
    }
    if (app.lock_watch && app.bus) g_dbus_connection_signal_unsubscribe(app.bus, app.lock_watch);
    app.lock_watch = 0;
    if (app.display) wl_display_roundtrip(app.display);
    put_phone_back(head_named(app.screen_name));
    app.primary_moved = FALSE;
    shot_free();
}

/* ── the screen following its window ───────────────────────────────────── */

/*
 * The window says its size ("resize", and in "hello"), in its own logical
 * pixels, as it does to the phone's Linux. Here that becomes the screen's
 * mode: that many logical pixels at the session's scale, so Plasma gets more
 * room as the window grows rather than a bigger picture of the same room, as
 * DeX does. KWin gives a virtual output a custom mode as it would a monitor
 * (kscreen-doctor's addCustomMode), and Plasma lays the screen out at the new
 * size, as for a monitor's new resolution.
 *
 * The stream does not follow that mode. KWin 6.8 sizes a virtual output's
 * stream the other way round: the output can be resized
 * (DrmVirtualOutput::canResize), so the stream offers any size from 200 × 200
 * to 10000 × 10000 and KWin makes the output whatever size the stream is
 * agreed at (ScreenCastStream::onStreamParamChanged), never the stream the
 * output's size. With the mode alone, the stream stayed at its first size and
 * the new screen came squeezed into it; with the stream alone, KWin resized
 * the output but Plasma never laid it out again (both on 2026-11-14). So
 * both: the mode first, then the stream asked for that same size
 * (restart_stream), which KWin takes for a size the output already has.
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

/* Milliseconds into this run: what fits a gint for the threads to share. */
static gint ms_now(void) {
    return (gint)((g_get_monotonic_time() - app.t0) / 1000);
}

/* The size the stream is asked for, on the capsfilter after pipewiresrc. Any
 * memory: the GPU path's DMA-BUFs as well as the CPU path's frames. */
static void ask_stream_size(int w, int h) {
    g_atomic_int_set(&app.stream_w, w);
    g_atomic_int_set(&app.stream_h, h);
    if (!app.sizecaps) return;
    GstCaps *caps = gst_caps_new_simple("video/x-raw", "width", G_TYPE_INT, w, "height", G_TYPE_INT, h, NULL);
    gst_caps_set_features(caps, 0, gst_caps_features_new_any());
    g_object_set(app.sizecaps, "caps", caps, NULL);
    gst_caps_unref(caps);
}

/*
 * The stream at the size the screen now has: on the main loop, once the
 * sizer has KWin's mode. A new pipeline, built as at the start. Tried first
 * on 2026-11-14, and worse: renegotiated in place (pipewiresrc reconnects to
 * KWin's stream, keeps a frame of the old size to send again on a still
 * screen, and the new size's first frame waited 11 s for the screen's next
 * change), and taken to READY and back (the first frame came, then nothing
 * for 7 s, the screen changing or not). Built afresh, the first frame of the
 * new size comes about 100 ms after the pipeline does, and the rest follow.
 */
static void stop_video(void);
static gboolean start_video(GString *why);

static gboolean restart_stream(gpointer d) {
    int w = GPOINTER_TO_INT(d) >> 16, h = GPOINTER_TO_INT(d) & 0xffff;
    if (!app.pipeline || app.finishing) return G_SOURCE_REMOVE;
    if (w == g_atomic_int_get(&app.stream_w) && h == g_atomic_int_get(&app.stream_h)) return G_SOURCE_REMOVE;
    stop_video();
    ask_stream_size(w, h);
    GString *why = g_string_new(NULL);
    if (!start_video(why)) {
        say("size: the picture would not start again at %dx%d: %s", w, h, why->str);
        send_state("stopped", why->str);
        finish(1);
    }
    g_string_free(why, TRUE);
    return G_SOURCE_REMOVE;
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
        if (apply_size(w, h)) {
            say("size: %dx%d in %d ms", w, h, (int)((g_get_monotonic_time() - t0) / 1000));
            /* w and h are below 2^15: both fit one pointer. */
            g_idle_add(restart_stream, GINT_TO_POINTER(w << 16 | h));
        }
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
    if ((!app.sizer && !app.wlr) || !json_raw(json, "w", v, sizeof v)) return;
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
    /* + 1: 0 is "no size on its way". */
    g_atomic_int_set(&app.asked_at, ms_now() + 1);
    say("size: the window is %dx%d, so the screen is %dx%d", w, h, sw, sh);
    if (app.wlr) wlr_resize(sw, sh);
    else ask_size(sw, sh);
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
    /* A button pressed or let go of: what here.js asks about (not the wheel, not moving). */
    if ((mask ^ app.buttons) & (1 | 2 | 4 | 128)) app.pressed_at = g_get_monotonic_time();
    /* Where the pointer goes back to after a drop has borrowed it (carry). */
    app.last_x = x;
    app.last_y = y;
    if (app.wlr) {
        wlr_pointer(mask, x, y);
        return;
    }
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
    if (app.wlr) wlr_press(code, down);
    else org_kde_kwin_fake_input_keyboard_key(app.fake, code, down ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
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
    if (app.wlr ? !app.vkeyboard : (!app.fake || app.fake_version < 4)) return;
    if (down) app.pressed_at = g_get_monotonic_time();
    gpointer was = g_hash_table_lookup(app.keys_down, GUINT_TO_POINTER(keysym));
    if (!down) {
        if (!was) return;
        guint code = GPOINTER_TO_UINT(was) - 1;
        if (code) press(code, FALSE);
        else if (!app.wlr && app.fake_version >= 6) org_kde_kwin_fake_input_keyboard_keysym(app.fake, keysym, 0);
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
        /* Not on the phone's layout: KWin's own keysym typing is the last try. wlroots has none. */
        if (app.wlr || app.fake_version < 6) return;
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
    if (!app.fake && !app.vpointer && !app.vkeyboard) return;
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
        gint at = g_atomic_int_get(&app.asked_at);
        if (at && g_atomic_int_compare_and_exchange(&app.asked_at, at, 0))
            say("video: now %dx%d, %d ms after the window asked", w, h, ms_now() + 1 - at);
        else
            say("video: now %dx%d", w, h);
        GstElement *src = app.pipeline ? gst_bin_get_by_name(GST_BIN(app.pipeline), "src") : NULL;
        GstPad *pad = src ? gst_element_get_static_pad(src, "src") : NULL;
        GstCaps *caps = pad ? gst_pad_get_current_caps(pad) : NULL;
        char *text = caps ? gst_caps_to_string(caps) : NULL;
        say("video: the screen sends %s", text ? text : "?");
        g_free(text);
        if (caps) gst_caps_unref(caps);
        if (pad) gst_object_unref(pad);
        if (src) gst_object_unref(src);
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
        say("video: the screen sends %s", text ? text : "?");
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
        /* One thread, on a fast core (prefer_fast_cores): on the Galaxy S24 at 1920x1020 that encoded
         * 56-109 frames a second where four sliced threads made 26-85, and x264 by itself about 130.
         * Each slice keeps its core only partly busy, so the cores' clocks never went up. */
        char *conv = convert_desc(app.convert && strcmp(app.convert, "gl") == 0 ? "NV12" : "I420");
        coder = g_strdup_printf("%s ! " LEAKY " ! "
                                "x264enc name=enc tune=zerolatency speed-preset=ultrafast bitrate=%u "
                                "key-int-max=600 threads=1",
                                conv, kbps);
        g_free(conv);
    }
    /* "size": the screen's size, asked of KWin's stream (restart_stream). On
     * Phosh the frames come from wlr-screencopy through an appsrc (push_shot),
     * which resends the last one itself (keepalive). */
    char *source = app.wlr ? g_strdup("appsrc name=src is-live=true do-timestamp=true format=time max-buffers=2 "
                                      "leaky-type=downstream")
                           : g_strdup_printf("pipewiresrc name=src do-timestamp=true keepalive-time=%d ! "
                                             "capsfilter name=size",
                                             KEEPALIVE_MS);
    char *desc = g_strdup_printf("%s ! " LEAKY " ! %s ! "
                                 "h264parse config-interval=-1 ! video/x-h264,stream-format=byte-stream,alignment=au ! "
                                 "appsink name=out sync=false max-buffers=8 enable-last-sample=false",
                                 source, coder);
    g_free(source);
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
    if (app.sizecaps) gst_object_unref(app.sizecaps);
    if (app.appsrc) gst_object_unref(app.appsrc);
    app.pipeline = app.appsink = app.encoder_el = app.sizecaps = app.appsrc = NULL;
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
        if (app.wlr) {
            /* Phosh's frames, as wlr-screencopy gives them: their size and kind are the caps. */
            app.appsrc = gst_bin_get_by_name(GST_BIN(p), "src");
            GstCaps *caps = gst_caps_new_simple("video/x-raw", "format", G_TYPE_STRING, shm_gst_format(app.src_format),
                                                "width", G_TYPE_INT, app.src_w, "height", G_TYPE_INT, app.src_h,
                                                "framerate", GST_TYPE_FRACTION, 0, 1, NULL);
            gst_app_src_set_caps(GST_APP_SRC(app.appsrc), caps);
            gst_caps_unref(caps);
        } else {
            aim_source(gst_bin_get_by_name(GST_BIN(p), "src"));
        }
        app.encoder_el = gst_bin_get_by_name(GST_BIN(p), "enc");
        GstPad *in = gst_element_get_static_pad(app.encoder_el, "sink");
        gst_pad_add_probe(in, GST_PAD_PROBE_TYPE_BUFFER, gate, NULL, NULL);
        gst_object_unref(in);
        app.sizecaps = gst_bin_get_by_name(GST_BIN(p), "size");
        ask_stream_size(g_atomic_int_get(&app.stream_w), g_atomic_int_get(&app.stream_h));
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
 * these it would dim, lock and suspend under the person typing. All are held
 * on this process's bus connection and go with it, however it ends. Phosh's
 * session (GNOME's session manager) takes idle and suspend inhibitions of its
 * own kind; Plasma takes the freedesktop ones.
 */
static void inhibit(void) {
    GError *err = NULL;
    if (!app.bus) app.bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &err);
    if (!app.bus) {
        say("no session bus, so the phone may sleep: %s", err ? err->message : "?");
        g_clear_error(&err);
        return;
    }
    if (app.wlr) {
        /* Flags: 4 suspend, 8 idle (gnome-session's GsmInhibitorFlag). */
        GVariant *r = g_dbus_connection_call_sync(
            app.bus, "org.gnome.SessionManager", "/org/gnome/SessionManager", "org.gnome.SessionManager", "Inhibit",
            g_variant_new("(susu)", "Open Android DeX", 0u, "This phone's desktop is open on a computer", 4u | 8u),
            G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 3000, NULL, &err);
        if (r) g_variant_unref(r);
        else {
            say("org.gnome.SessionManager: %s", err ? err->message : "?");
            g_clear_error(&err);
        }
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

/* ── What is opened from a screen shows there (KWin) ───────────────────── */

/*
 * Plasma Mobile's app drawer and dock switch to an app's window when it has
 * one, on whichever screen that window is. Tapped on the computer's screen,
 * Brave, Calls or VLC came up on the phone's (Firefox only opened where it was
 * tapped because its window's id doesn't match its desktop file, so the drawer
 * started it again). While the computer's screen is up, KWin runs a script of
 * ours, here.js, that brings such a window to the screen it was asked from, or
 * opens a new one of the app there: see the script for the rules. KWin's
 * scripts can't start programs, or see the computer's mouse apart from the
 * phone's touch, so this program answers it on the session bus:
 *
 *   Look(app) → (screen, pressed, windows)  the computer's screen, how many ms
 *                                           ago the computer last pressed a key
 *                                           or button, and whether the app's
 *                                           desktop file has a new-window action
 *   NewWindow(app) → opened                 that action, as the session would run it
 *   Note(text)                              a line for this program's log
 *
 * Only KWin (the script runs in it) is answered.
 */
#define HERE_BUS "com.ccrstech.openandroiddex.Pmos"
#define HERE_PATH "/com/ccrstech/openandroiddex/Pmos"
#define HERE_SCRIPT "openandroiddex-pmos"

static const char here_xml[] =
    "<node><interface name='" HERE_BUS "'>"
    "<method name='Look'><arg type='s' name='app' direction='in'/><arg type='s' name='screen' direction='out'/>"
    "<arg type='u' name='pressed' direction='out'/><arg type='b' name='windows' direction='out'/></method>"
    "<method name='NewWindow'><arg type='s' name='app' direction='in'/><arg type='b' name='opened' direction='out'/></method>"
    "<method name='Note'><arg type='s' name='text' direction='in'/></method>"
    "</interface></node>";

/* An app as KWin names a window's (its desktop file, without .desktop): nothing that could be a path. */
static GDesktopAppInfo *app_info(const char *id) {
    if (!id || !*id || *id == '.' || strlen(id) > 200) return NULL;
    for (const char *p = id; *p; p++)
        if (!g_ascii_isalnum(*p) && *p != '.' && *p != '-' && *p != '_') return NULL;
    char *file = g_strconcat(id, ".desktop", NULL);
    GDesktopAppInfo *info = g_desktop_app_info_new(file);
    g_free(file);
    return info;
}

/*
 * The desktop file's action for another window: "new-window" in the
 * specification's example and GNOME's apps, "NewWindow" in KDE's,
 * "new-empty-window" in VS Code's. Not a private window, and none for an app
 * that says it has a single main window.
 */
static const char *new_window_action(GDesktopAppInfo *info) {
    if (!info || g_desktop_app_info_get_boolean(info, "SingleMainWindow")) return NULL;
    const char *const *acts = g_desktop_app_info_list_actions(info);
    for (int i = 0; acts && acts[i]; i++) {
        char flat[64];
        gsize n = 0;
        for (const char *p = acts[i]; *p && n + 1 < sizeof flat; p++)
            if (g_ascii_isalnum(*p)) flat[n++] = g_ascii_tolower(*p);
        flat[n] = 0;
        if (strcmp(flat, "newwindow") == 0 || strcmp(flat, "newemptywindow") == 0 || strcmp(flat, "windownew") == 0)
            return acts[i];
    }
    return NULL;
}

static void child_setsid(gpointer d) { setsid(); }

static void opened_ended(GPid pid, gint status, gpointer d) {
    if (!g_spawn_check_wait_status(status, NULL)) say("here: %s ended with status %d", (char *)d, status);
    g_spawn_close_pid(pid);
    g_free(d);
}

/*
 * Run a desktop action's Exec line as the session would: in the session's
 * environment, in its own process session (nothing of this program's SSH
 * login reaches it), with nothing on this program's stdout, which is the
 * computer's stream. GLib's own launcher would leave that stdout to it.
 */
static gboolean spawn_action(GDesktopAppInfo *info, const char *action, GString *why) {
    const char *file = g_desktop_app_info_get_filename(info);
    GKeyFile *kf = g_key_file_new();
    char *group = g_strdup_printf("Desktop Action %s", action);
    char *exec = NULL, *dir = NULL, *icon = NULL;
    gchar **words = NULL;
    GError *err = NULL;
    gboolean ok = FALSE;
    if (file && g_key_file_load_from_file(kf, file, G_KEY_FILE_NONE, NULL)) {
        exec = g_key_file_get_string(kf, group, G_KEY_FILE_DESKTOP_KEY_EXEC, NULL);
        dir = g_key_file_get_string(kf, G_KEY_FILE_DESKTOP_GROUP, G_KEY_FILE_DESKTOP_KEY_PATH, NULL);
        icon = g_key_file_get_string(kf, G_KEY_FILE_DESKTOP_GROUP, G_KEY_FILE_DESKTOP_KEY_ICON, NULL);
    }
    if (!exec) g_string_assign(why, "its desktop file has no Exec for the action");
    else if (g_shell_parse_argv(exec, NULL, &words, &err)) {
        /* The field codes: no files or URLs to give it; the icon, name and file as the specification says. */
        GPtrArray *argv = g_ptr_array_new_with_free_func(g_free);
        for (int i = 0; words[i]; i++) {
            const char *w = words[i];
            if (w[0] == '%' && w[1] && !w[2]) {
                if (w[1] == 'i' && icon && *icon) {
                    g_ptr_array_add(argv, g_strdup("--icon"));
                    g_ptr_array_add(argv, g_strdup(icon));
                } else if (w[1] == 'c') {
                    g_ptr_array_add(argv, g_strdup(g_app_info_get_name(G_APP_INFO(info))));
                } else if (w[1] == 'k') {
                    g_ptr_array_add(argv, g_strdup(file));
                } else if (w[1] == '%') {
                    g_ptr_array_add(argv, g_strdup("%"));
                }
                continue;
            }
            GString *s = g_string_new(NULL);
            for (const char *p = w; *p; p++) {
                if (p[0] == '%' && p[1] == '%') p++;
                g_string_append_c(s, *p);
            }
            g_ptr_array_add(argv, g_string_free(s, FALSE));
        }
        g_ptr_array_add(argv, NULL);
        if (argv->len > 1) {
            gchar **env = app.session_env ? g_strdupv(app.session_env) : g_get_environ();
            /* The session's own start had these; they are not this window's. */
            env = g_environ_unsetenv(env, "XDG_ACTIVATION_TOKEN");
            env = g_environ_unsetenv(env, "DESKTOP_STARTUP_ID");
            const char *home = g_environ_getenv(env, "HOME");
            int nul = open("/dev/null", O_RDWR | O_CLOEXEC);
            GPid pid = 0;
            ok = nul >= 0 && g_spawn_async_with_fds(dir && *dir ? dir : home, (gchar **)argv->pdata, env,
                                                    G_SPAWN_SEARCH_PATH_FROM_ENVP | G_SPAWN_DO_NOT_REAP_CHILD,
                                                    child_setsid, NULL, &pid, nul, nul, nul, &err);
            if (nul >= 0) close(nul);
            if (ok) g_child_watch_add(pid, opened_ended, g_strdup(g_desktop_app_info_get_filename(info)));
            else if (!err) g_string_assign(why, "no /dev/null to start it with");
            g_strfreev(env);
        } else {
            g_string_assign(why, "the action's Exec is empty");
        }
        g_ptr_array_free(argv, TRUE);
    }
    if (err) {
        g_string_assign(why, err->message);
        g_clear_error(&err);
    }
    g_strfreev(words);
    g_free(exec);
    g_free(dir);
    g_free(icon);
    g_free(group);
    g_key_file_unref(kf);
    return ok;
}

/* The app's new-window action: over the bus for an app that is started that way, else run here. */
static gboolean open_new_window(const char *id) {
    GDesktopAppInfo *info = app_info(id);
    const char *action = new_window_action(info);
    gboolean ok = FALSE;
    GString *why = g_string_new(NULL);
    if (!action) {
        g_string_assign(why, info ? "its desktop file has no new-window action" : "no desktop file for it");
    } else if (g_desktop_app_info_get_boolean(info, "DBusActivatable")) {
        /* org.freedesktop.Application.ActivateAction; GLib runs nothing here for such an app. */
        g_desktop_app_info_launch_action(info, action, NULL);
        ok = TRUE;
    } else {
        ok = spawn_action(info, action, why);
    }
    if (ok) say("here: %s: opening its \"%s\"", id, action);
    else say("here: %s: no new window (%s)", id, why->str);
    g_string_free(why, TRUE);
    if (info) g_object_unref(info);
    return ok;
}

static void here_call(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                      const char *method, GVariant *params, GDBusMethodInvocation *inv, gpointer d) {
    if (!app.kwin_name || g_strcmp0(sender, app.kwin_name) != 0) {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.AccessDenied",
                                                   "Only KWin's script for the computer's screen asks this.");
        return;
    }
    const char *text = NULL;
    g_variant_get(params, "(&s)", &text);
    if (strcmp(method, "Look") == 0) {
        struct output *o = our_output();
        gint64 ago = app.pressed_at ? (g_get_monotonic_time() - app.pressed_at) / 1000 : G_MAXUINT32;
        GDesktopAppInfo *info = *text ? app_info(text) : NULL;
        gboolean more = new_window_action(info) != NULL;
        if (info) g_object_unref(info);
        g_dbus_method_invocation_return_value(
            inv, g_variant_new("(sub)", o && o->name ? o->name : "", (guint32)MIN(ago, (gint64)G_MAXUINT32), more));
    } else if (strcmp(method, "NewWindow") == 0) {
        g_dbus_method_invocation_return_value(inv, g_variant_new("(b)", open_new_window(text)));
    } else {
        say("here: %s", text);
        g_dbus_method_invocation_return_value(inv, NULL);
    }
}

static GVariant *kwin_scripting(GDBusConnection *bus, const char *method, GVariant *args, const char *reply,
                                GError **err) {
    return g_dbus_connection_call_sync(bus, "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting", method, args,
                                       reply ? G_VARIANT_TYPE(reply) : NULL, G_DBUS_CALL_FLAGS_NONE, 3000, NULL, err);
}

/* KWin unloads ours by its name; TRUE if it had it. */
static gboolean unload_here(GDBusConnection *bus) {
    gboolean had = FALSE;
    GVariant *r = kwin_scripting(bus, "unloadScript", g_variant_new("(s)", HERE_SCRIPT), "(b)", NULL);
    if (r) {
        g_variant_get(r, "(b)", &had);
        g_variant_unref(r);
    }
    return had;
}

/* The service, then the script: KWin reads it from a file, in the cache, written anew each time. */
static void here_start(void) {
    if (app.wlr) return;
    GError *err = NULL;
    if (!app.bus) app.bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &err);
    GVariant *r = app.bus ? g_dbus_connection_call_sync(app.bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                                                        "org.freedesktop.DBus", "GetNameOwner",
                                                        g_variant_new("(s)", "org.kde.KWin"), G_VARIANT_TYPE("(s)"),
                                                        G_DBUS_CALL_FLAGS_NONE, 3000, NULL, &err)
                          : NULL;
    if (!r) {
        say("here: apps open where KWin puts them (%s)", err ? err->message : "no session bus");
        g_clear_error(&err);
        return;
    }
    g_variant_get(r, "(s)", &app.kwin_name);
    g_variant_unref(r);
    static const GDBusInterfaceVTable vtable = {here_call, NULL, NULL, {0}};
    GDBusNodeInfo *node = g_dbus_node_info_new_for_xml(here_xml, NULL);
    app.here_object = g_dbus_connection_register_object(app.bus, HERE_PATH, node->interfaces[0], &vtable, NULL, NULL, &err);
    g_dbus_node_info_unref(node);
    if (!app.here_object) {
        say("here: apps open where KWin puts them (%s)", err ? err->message : "?");
        g_clear_error(&err);
        return;
    }
    app.here_owner = g_bus_own_name_on_connection(app.bus, HERE_BUS,
                                                  G_BUS_NAME_OWNER_FLAGS_REPLACE | G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT,
                                                  NULL, NULL, NULL, NULL);
    char *dir = g_build_filename(g_get_user_cache_dir(), "openandroiddex", NULL);
    char *file = g_build_filename(dir, "kwin-here.js", NULL);
    g_mkdir_with_parents(dir, 0700);
    gint id = -1;
    if (g_file_set_contents(file, HERE_JS, -1, &err)) {
        /* One a session that died left behind goes first: KWin loads a name once. */
        unload_here(app.bus);
        /* KWin numbers a script by how many it has (Scripting::loadScript), so after another was
         * unloaded the number can be one in use, whose /Scripting/ScriptN is that one's. */
        char *before = NULL;
        r = g_dbus_connection_call_sync(app.bus, "org.kde.KWin", "/Scripting", "org.freedesktop.DBus.Introspectable",
                                        "Introspect", NULL, G_VARIANT_TYPE("(s)"), G_DBUS_CALL_FLAGS_NONE, 3000,
                                        NULL, NULL);
        if (r) {
            g_variant_get(r, "(s)", &before);
            g_variant_unref(r);
        }
        r = kwin_scripting(app.bus, "loadScript", g_variant_new("(ss)", file, HERE_SCRIPT), "(i)", &err);
        if (r) {
            g_variant_get(r, "(i)", &id);
            g_variant_unref(r);
        }
        char node[48], path[48];
        g_snprintf(node, sizeof node, "name=\"Script%d\"", id);
        g_snprintf(path, sizeof path, "/Scripting/Script%d", id);
        if (id >= 0 && before && !strstr(before, node)) {
            /* Ours alone; KWin answers once it has read the file. */
            r = g_dbus_connection_call_sync(app.bus, "org.kde.KWin", path, "org.kde.kwin.Script", "run", NULL, NULL,
                                            G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &err);
            if (r) g_variant_unref(r);
        } else if (id >= 0) {
            /* start() also applies KWin's script settings (what is on or off), as its settings page does. */
            say("here: KWin's number %d for the script is another's; starting it with KWin's own", id);
            r = kwin_scripting(app.bus, "start", NULL, NULL, &err);
            if (r) g_variant_unref(r);
        }
        g_free(before);
    }
    if (id >= 0 && !err) {
        app.here_loaded = TRUE;
        say("here: KWin runs %s: what is opened from a screen shows there", file);
        /* If this program dies, the guard's `rest` unloads it. */
        if (app.guard_fd == 0) start_guard();
    } else {
        say("here: KWin would not run %s (%s)", file, err ? err->message : "no script id");
    }
    g_clear_error(&err);
    g_free(file);
    g_free(dir);
}

/* ── KWin's on-screen keyboard after a session ─────────────────────────── */

/*
 * plasma-keyboard, Plasma Mobile's on-screen keyboard (KWin's input method),
 * sizes itself for one screen and doesn't always follow when it is shown on
 * another. After sessions on a computer's screen the phone's keyboard was a
 * narrow strip of its keys, and stayed so until a new keyboard process came
 * (S24, 2026-11-16). So when the computer's screen has gone, the keyboard
 * process goes too, and KWin starts a new one, laid out for the phone: KWin
 * starts its input method again whenever it crashes, and gives up only after
 * five crashes each within 20 s of the last (InputMethod::startInputMethod).
 * SIGTERM won't do: plasma-keyboard ends cleanly then, which KWin leaves be.
 * Nothing of the user's settings is touched.
 */
static pid_t kwin_pid(GDBusConnection *bus) {
    GVariant *r = bus ? g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                                                    "org.freedesktop.DBus", "GetConnectionUnixProcessID",
                                                    g_variant_new("(s)", "org.kde.KWin"), G_VARIANT_TYPE("(u)"),
                                                    G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL)
                      : NULL;
    guint32 pid = 0;
    if (r) {
        g_variant_get(r, "(u)", &pid);
        g_variant_unref(r);
    }
    return (pid_t)pid;
}

/* KWin's input method: the child it gave the variable only that one gets (MALIIT_ENABLE_ANIMATIONS). */
static pid_t input_method_pid(pid_t kwin) {
    pid_t found = 0;
    DIR *d = opendir("/proc");
    struct dirent *e;
    while (d && !found && (e = readdir(d))) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        char path[64];
        g_snprintf(path, sizeof path, "/proc/%s/stat", e->d_name);
        char *line = read_file(path, NULL);
        /* pid (comm) state ppid ...: the comm can hold spaces, so from its closing parenthesis */
        const char *close = line ? strrchr(line, ')') : NULL;
        int ppid = 0;
        if (close && sscanf(close + 1, " %*c %d", &ppid) == 1 && ppid == kwin) {
            g_snprintf(path, sizeof path, "/proc/%s/environ", e->d_name);
            gsize len = 0;
            char *env = read_file(path, &len);
            if (env && environ_get(env, len, "MALIIT_ENABLE_ANIMATIONS") && environ_get(env, len, "WAYLAND_SOCKET"))
                found = (pid_t)atoi(e->d_name);
            g_free(env);
        }
        g_free(line);
    }
    if (d) closedir(d);
    return found;
}

static void restart_keyboard(GDBusConnection *bus, const char *who) {
    pid_t kwin = kwin_pid(bus);
    pid_t old = kwin > 0 ? input_method_pid(kwin) : 0;
    if (old <= 0) return; /* no KWin (Phosh, or the session has ended), or no keyboard of its */
    if (kill(old, SIGKILL) != 0) {
        say("%s: the on-screen keyboard (%d) could not be started again: %s", who, (int)old, g_strerror(errno));
        return;
    }
    pid_t now = 0;
    for (int i = 0; i < 30 && (now <= 0 || now == old); i++) {
        g_usleep(100 * 1000);
        now = input_method_pid(kwin);
    }
    if (now > 0 && now != old) say("%s: the on-screen keyboard started again, for the phone's screen (%d, was %d)", who, (int)now, (int)old);
    else say("%s: the on-screen keyboard (%d) ended, and KWin hasn't started another yet", who, (int)old);
}

static void here_stop(void) {
    if (app.here_loaded && unload_here(app.bus)) say("here: KWin's script stopped");
    app.here_loaded = FALSE;
    if (app.here_owner) g_bus_unown_name(app.here_owner);
    if (app.here_object) g_dbus_connection_unregister_object(app.bus, app.here_object);
    app.here_owner = app.here_object = 0;
}

/* ── The clipboard ─────────────────────────────────────────────────────── */

/*
 * Both ways, by the window's rules (clipboardShare.ts): the computer's text
 * comes as {"t":"clip","text":…} when the window comes to the front, and
 * what is copied on the phone goes to the window as the same message while
 * one is open. The window decides whether it lands on the computer's
 * clipboard; an empty text from it takes back what it gave.
 *
 * A Wayland program sees the clipboard only while it has the keyboard, which
 * this one never has. Data-control is what clipboard managers use instead:
 * the seat's clipboard at any time, and a way to set it. KWin 6.8 has the
 * standard one (ext-data-control-v1); wlroots (Phosh) also its own older
 * one (zwlr-data-control-unstable-v1), the same but for its names.
 *
 * Text only, in the types toolkits ask for. What the computer put there
 * carries a type of this program's own beside them, so that when the phone
 * says its clipboard is now that, it isn't sent back. The phone's text goes
 * only once a window is open, only while its switch is on
 * ({"t":"clipshare","on":…}, which the window says when it shows the picture
 * and whenever the switch moves), and only when it changes: a window that
 * opens hands the computer's clipboard over (if it is in front), which the
 * phone's older one mustn't overwrite.
 */
#define CLIP_MINE "application/x-openandroiddex-pmos"
/* The most the window takes from the phone at once (clipboardShare.ts's COMPUTER_MAX). */
#define CLIP_MAX (4 << 20)
static const char *const CLIP_TYPES[] = {"text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "TEXT", "STRING",
                                         NULL};

struct clip_offer {
    struct wl_proxy *wl;
    GPtrArray *types;
};

struct clip_source {
    struct wl_proxy *wl;
    GBytes *text;
};

struct clip_read {
    int fd;
    guint watch, timeout;
    GByteArray *buf;
};

struct clip_write {
    GBytes *text;
    gsize off;
};

static gboolean clip_has(struct clip_offer *o, const char *mime) {
    for (guint i = 0; i < o->types->len; i++)
        if (strcmp(g_ptr_array_index(o->types, i), mime) == 0) return TRUE;
    return FALSE;
}

static void clip_offer_free(struct clip_offer *o) {
    if (!o) return;
    if (app.dc_ext) ext_data_control_offer_v1_destroy((struct ext_data_control_offer_v1 *)o->wl);
    else zwlr_data_control_offer_v1_destroy((struct zwlr_data_control_offer_v1 *)o->wl);
    g_ptr_array_free(o->types, TRUE);
    g_free(o);
}

static void clip_offer_type(struct clip_offer *o, const char *mime) {
    if (o->types->len < 64) g_ptr_array_add(o->types, g_strdup(mime));
}
static void ext_offer_type(void *d, struct ext_data_control_offer_v1 *o, const char *mime) { clip_offer_type(d, mime); }
static void wlr_offer_type(void *d, struct zwlr_data_control_offer_v1 *o, const char *mime) { clip_offer_type(d, mime); }
static const struct ext_data_control_offer_v1_listener ext_offer_listener = {.offer = ext_offer_type};
static const struct zwlr_data_control_offer_v1_listener wlr_offer_listener = {.offer = wlr_offer_type};

static void clip_read_stop(void) {
    struct clip_read *r = app.clip_reading;
    if (!r) return;
    app.clip_reading = NULL;
    if (r->watch) g_source_remove(r->watch);
    if (r->timeout) g_source_remove(r->timeout);
    close(r->fd);
    g_byte_array_free(r->buf, TRUE);
    g_free(r);
}

/* What was copied on the phone, to the window; for `clip`, to stdout. */
static void clip_arrived(const guint8 *data, gsize len) {
    if (app.clip_print) {
        fwrite(data, 1, len, stdout);
        fflush(stdout);
        if (app.loop) g_main_loop_quit(app.loop);
        return;
    }
    if (!len || !g_atomic_int_get(&app.attached) || !app.clip_share) return;
    char *text = g_utf8_make_valid((const char *)data, (gssize)len);
    GString *s = g_string_sized_new(len + 32);
    g_string_append(s, "{\"t\":\"clip\",\"text\":");
    json_string(s, text);
    g_string_append_c(s, '}');
    send_frame(KIND_TEXT, s->str, s->len, NULL, 0);
    say("clipboard: %zu bytes copied on the phone, to the computer", len);
    g_string_free(s, TRUE);
    g_free(text);
}

static gboolean clip_read_more(gint fd, GIOCondition cond, gpointer d) {
    struct clip_read *r = d;
    guint8 chunk[65536];
    for (;;) {
        ssize_t n = read(fd, chunk, sizeof chunk);
        if (n > 0) {
            if (r->buf->len + (gsize)n > CLIP_MAX) {
                say("clipboard: what was copied on the phone is over %d MB; it stays there", CLIP_MAX >> 20);
                break;
            }
            g_byte_array_append(r->buf, chunk, (guint)n);
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return G_SOURCE_CONTINUE;
        if (n == 0) clip_arrived(r->buf->data, r->buf->len);
        break;
    }
    r->watch = 0; /* this source, which ends with this call */
    clip_read_stop();
    return G_SOURCE_REMOVE;
}

static gboolean clip_read_late(gpointer d) {
    struct clip_read *r = d;
    say("clipboard: the app the phone's clipboard is from didn't hand it over");
    r->timeout = 0;
    clip_read_stop();
    if (app.clip_print && app.loop) g_main_loop_quit(app.loop);
    return G_SOURCE_REMOVE;
}

/* The phone's clipboard is something new: its text, if it is text the computer didn't put there. */
static void clip_changed(void) {
    clip_read_stop();
    struct clip_offer *o = app.clip_now;
    if (!o) return; /* emptied: the app it came from has gone; the computer keeps its own */
    /* Nothing leaves the phone unless a window is there and its switch is on. */
    if (!app.clip_print && (clip_has(o, CLIP_MINE) || !g_atomic_int_get(&app.attached) || !app.clip_share)) return;
    const char *mime = NULL;
    for (int k = 0; CLIP_TYPES[k] && !mime; k++)
        if (clip_has(o, CLIP_TYPES[k])) mime = CLIP_TYPES[k];
    if (!mime) {
        /* A picture, or files: not shared. */
        if (app.clip_print) {
            GString *all = g_string_new(NULL);
            for (guint i = 0; i < o->types->len; i++)
                g_string_append_printf(all, "%s%s", i ? ", " : "", (char *)g_ptr_array_index(o->types, i));
            say("clip: the clipboard has no text, only %s", all->len ? all->str : "nothing");
            g_string_free(all, TRUE);
        }
        return;
    }
    int p[2];
    if (pipe2(p, O_CLOEXEC | O_NONBLOCK) != 0) return;
    if (app.dc_ext) ext_data_control_offer_v1_receive((struct ext_data_control_offer_v1 *)o->wl, mime, p[1]);
    else zwlr_data_control_offer_v1_receive((struct zwlr_data_control_offer_v1 *)o->wl, mime, p[1]);
    close(p[1]); /* the request has its own copy */
    wl_display_flush(app.display);
    struct clip_read *r = g_new0(struct clip_read, 1);
    r->fd = p[0];
    r->buf = g_byte_array_new();
    r->watch = g_unix_fd_add(p[0], G_IO_IN | G_IO_HUP | G_IO_ERR, clip_read_more, r);
    r->timeout = g_timeout_add_seconds(5, clip_read_late, r);
    app.clip_reading = r;
}

static void clip_offered(struct wl_proxy *wl) {
    struct clip_offer *o = g_new0(struct clip_offer, 1);
    o->wl = wl;
    o->types = g_ptr_array_new_with_free_func(g_free);
    if (app.dc_ext) ext_data_control_offer_v1_add_listener((struct ext_data_control_offer_v1 *)wl, &ext_offer_listener, o);
    else zwlr_data_control_offer_v1_add_listener((struct zwlr_data_control_offer_v1 *)wl, &wlr_offer_listener, o);
}

static void clip_selection(struct wl_proxy *wl) {
    struct clip_offer *o = wl ? wl_proxy_get_user_data(wl) : NULL;
    if (app.clip_now && app.clip_now != o && app.clip_now != app.clip_primary) clip_offer_free(app.clip_now);
    app.clip_now = o;
    clip_changed();
}

static void clip_primary_selection(struct wl_proxy *wl) {
    struct clip_offer *o = wl ? wl_proxy_get_user_data(wl) : NULL;
    if (app.clip_primary && app.clip_primary != o && app.clip_primary != app.clip_now) clip_offer_free(app.clip_primary);
    app.clip_primary = o;
}

static void clip_finished(void) {
    say("clipboard: the phone's desktop has stopped telling this program about its clipboard");
    clip_read_stop();
    if (app.dc_ext) ext_data_control_device_v1_destroy((struct ext_data_control_device_v1 *)app.dc_device);
    else zwlr_data_control_device_v1_destroy((struct zwlr_data_control_device_v1 *)app.dc_device);
    app.dc_device = NULL;
}

static void ext_dev_offer(void *d, struct ext_data_control_device_v1 *dev, struct ext_data_control_offer_v1 *o) {
    clip_offered((struct wl_proxy *)o);
}
static void ext_dev_selection(void *d, struct ext_data_control_device_v1 *dev, struct ext_data_control_offer_v1 *o) {
    clip_selection((struct wl_proxy *)o);
}
static void ext_dev_finished(void *d, struct ext_data_control_device_v1 *dev) { clip_finished(); }
static void ext_dev_primary(void *d, struct ext_data_control_device_v1 *dev, struct ext_data_control_offer_v1 *o) {
    clip_primary_selection((struct wl_proxy *)o);
}
static const struct ext_data_control_device_v1_listener ext_device_listener = {
    .data_offer = ext_dev_offer,
    .selection = ext_dev_selection,
    .finished = ext_dev_finished,
    .primary_selection = ext_dev_primary,
};
static void wlr_dev_offer(void *d, struct zwlr_data_control_device_v1 *dev, struct zwlr_data_control_offer_v1 *o) {
    clip_offered((struct wl_proxy *)o);
}
static void wlr_dev_selection(void *d, struct zwlr_data_control_device_v1 *dev, struct zwlr_data_control_offer_v1 *o) {
    clip_selection((struct wl_proxy *)o);
}
static void wlr_dev_finished(void *d, struct zwlr_data_control_device_v1 *dev) { clip_finished(); }
static void wlr_dev_primary(void *d, struct zwlr_data_control_device_v1 *dev, struct zwlr_data_control_offer_v1 *o) {
    clip_primary_selection((struct wl_proxy *)o);
}
static const struct zwlr_data_control_device_v1_listener wlr_device_listener = {
    .data_offer = wlr_dev_offer,
    .selection = wlr_dev_selection,
    .finished = wlr_dev_finished,
    .primary_selection = wlr_dev_primary,
};

static void clip_source_free(struct clip_source *s) {
    if (!s) return;
    if (app.dc_ext) ext_data_control_source_v1_destroy((struct ext_data_control_source_v1 *)s->wl);
    else zwlr_data_control_source_v1_destroy((struct zwlr_data_control_source_v1 *)s->wl);
    g_bytes_unref(s->text);
    g_free(s);
}

static gboolean clip_write_more(gint fd, GIOCondition cond, gpointer d) {
    struct clip_write *w = d;
    gsize len = 0;
    const guint8 *data = g_bytes_get_data(w->text, &len);
    while (w->off < len) {
        ssize_t n = write(fd, data + w->off, len - w->off);
        if (n > 0) {
            w->off += (gsize)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return G_SOURCE_CONTINUE;
        break; /* the app pasting has gone (EPIPE) */
    }
    close(fd);
    g_bytes_unref(w->text);
    g_free(w);
    return G_SOURCE_REMOVE;
}

/* An app on the phone pastes: the computer's text, as much at once as the pipe takes, the rest as it reads. */
static void clip_send(struct clip_source *s, const char *mime, int fd) {
    if (strcmp(mime, CLIP_MINE) == 0) {
        close(fd);
        return;
    }
    struct clip_write *w = g_new0(struct clip_write, 1);
    w->text = g_bytes_ref(s->text);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    if (clip_write_more(fd, G_IO_OUT, w) == G_SOURCE_CONTINUE)
        g_unix_fd_add(fd, G_IO_OUT | G_IO_ERR | G_IO_HUP, clip_write_more, w);
}

/* `clip --set`: it holds the clipboard until something else is copied. */
static gboolean clip_holding;

/* Something else was copied on the phone: the computer's text is no longer there. */
static void clip_cancelled(struct clip_source *s) {
    if (app.clip_mine == s) app.clip_mine = NULL;
    clip_source_free(s);
    if (clip_holding && app.loop) g_main_loop_quit(app.loop);
}

static void ext_src_send(void *d, struct ext_data_control_source_v1 *src, const char *mime, int32_t fd) {
    clip_send(d, mime, fd);
}
static void ext_src_cancelled(void *d, struct ext_data_control_source_v1 *src) { clip_cancelled(d); }
static const struct ext_data_control_source_v1_listener ext_source_listener = {
    .send = ext_src_send,
    .cancelled = ext_src_cancelled,
};
static void wlr_src_send(void *d, struct zwlr_data_control_source_v1 *src, const char *mime, int32_t fd) {
    clip_send(d, mime, fd);
}
static void wlr_src_cancelled(void *d, struct zwlr_data_control_source_v1 *src) { clip_cancelled(d); }
static const struct zwlr_data_control_source_v1_listener wlr_source_listener = {
    .send = wlr_src_send,
    .cancelled = wlr_src_cancelled,
};

/* The computer's text, onto the phone's clipboard; none takes back what it gave, if it is still there. */
static void clip_give(const char *text, gsize len) {
    if (!app.dc_device) return;
    if (!len) {
        if (app.clip_mine) {
            if (app.dc_ext) ext_data_control_device_v1_set_selection((struct ext_data_control_device_v1 *)app.dc_device, NULL);
            else zwlr_data_control_device_v1_set_selection((struct zwlr_data_control_device_v1 *)app.dc_device, NULL);
            clip_source_free(app.clip_mine);
            app.clip_mine = NULL;
            say("clipboard: the computer took its text back");
        }
        wl_display_flush(app.display);
        return;
    }
    struct clip_source *s = g_new0(struct clip_source, 1);
    s->text = g_bytes_new(text, len);
    if (app.dc_ext) {
        struct ext_data_control_source_v1 *src = ext_data_control_manager_v1_create_data_source(app.dc_ext);
        ext_data_control_source_v1_add_listener(src, &ext_source_listener, s);
        s->wl = (struct wl_proxy *)src;
        ext_data_control_source_v1_offer(src, CLIP_MINE);
        for (int k = 0; CLIP_TYPES[k]; k++) ext_data_control_source_v1_offer(src, CLIP_TYPES[k]);
        ext_data_control_device_v1_set_selection((struct ext_data_control_device_v1 *)app.dc_device, src);
    } else {
        struct zwlr_data_control_source_v1 *src = zwlr_data_control_manager_v1_create_data_source(app.dc_wlr);
        zwlr_data_control_source_v1_add_listener(src, &wlr_source_listener, s);
        s->wl = (struct wl_proxy *)src;
        zwlr_data_control_source_v1_offer(src, CLIP_MINE);
        for (int k = 0; CLIP_TYPES[k]; k++) zwlr_data_control_source_v1_offer(src, CLIP_TYPES[k]);
        zwlr_data_control_device_v1_set_selection((struct zwlr_data_control_device_v1 *)app.dc_device, src);
    }
    /* After the new one is set, so the clipboard is never empty between the two. */
    if (app.clip_mine) clip_source_free(app.clip_mine);
    app.clip_mine = s;
    wl_display_flush(app.display);
    say("clipboard: %zu bytes from the computer, on the phone's clipboard", len);
}

static void clip_start(void) {
    if (!app.seat || app.dc_device) return;
    if (app.dc_ext) {
        struct ext_data_control_device_v1 *dev = ext_data_control_manager_v1_get_data_device(app.dc_ext, app.seat);
        ext_data_control_device_v1_add_listener(dev, &ext_device_listener, NULL);
        app.dc_device = (struct wl_proxy *)dev;
    } else if (app.dc_wlr) {
        struct zwlr_data_control_device_v1 *dev = zwlr_data_control_manager_v1_get_data_device(app.dc_wlr, app.seat);
        zwlr_data_control_device_v1_add_listener(dev, &wlr_device_listener, NULL);
        app.dc_device = (struct wl_proxy *)dev;
    } else {
        say("clipboard: this desktop has no data-control, so the clipboard stays the phone's");
    }
}

static void on_clip(const char *json) {
    GString *text = json_text(json, "text");
    if (!text) return;
    clip_give(text->str, text->len);
    g_string_free(text, TRUE);
}

/* The window's switch: off, and what is copied on the phone stays on the phone, not read, not sent. */
static void on_clipshare(const char *json) {
    char v[16];
    gboolean on = json_raw(json, "on", v, sizeof v) && strcmp(v, "true") == 0;
    if (on != app.clip_share) say("clipboard: sharing %s", on ? "on" : "off");
    app.clip_share = on;
    if (!on) clip_read_stop(); /* one on its way goes nowhere */
}

/* ── Files dropped on the window ───────────────────────────────────────── */

/*
 * The computer copies a drop into the phone's Downloads first (`receive`,
 * below), then asks for it to be dropped where it was let go of:
 * {"t":"drop","x":X,"y":Y,"uris":"file:///…\r\n"}, X and Y in the picture's
 * pixels. Wayland lets a program start a drag only from a button pressed on a
 * surface of its own. So this program puts eight pixels of its own over
 * everything at that point (a layer-shell surface), presses the button on them
 * with its own pointer, starts a drag there carrying the files' URIs, has them
 * let the pointer through, moves the pointer a pixel and back so the
 * compositor looks for what is under it, and lets go. The app there takes the
 * files as it would from its own file manager (a browser's upload, a chat, a
 * terminal typing the path), or doesn't; either way they are in Downloads.
 * It answers {"t":"dropped","taken":…,"why":…}.
 *
 * The pointer is borrowed for a moment and put back where the computer has
 * it. This runs on the main loop, so the computer's own messages wait
 * meanwhile; with a button held on the computer it isn't tried.
 */
#define DROP_SIZE 8

static struct {
    struct wl_surface *surface;
    struct zwlr_layer_surface_v1 *layer;
    struct wl_buffer *buffer;
    struct wl_data_source *source;
    const char *uris;
    uint32_t serial, action;
    gboolean configured, entered, pressed, accepted, agreed, sent, dropped, finished, cancelled, over;
} drag;

static void dp_enter(void *d, struct wl_pointer *p, uint32_t serial, struct wl_surface *s, wl_fixed_t x, wl_fixed_t y) {
    if (s && s == drag.surface) drag.entered = TRUE;
}
static void dp_leave(void *d, struct wl_pointer *p, uint32_t serial, struct wl_surface *s) {
    if (s && s == drag.surface) drag.entered = FALSE;
}
static void dp_motion(void *d, struct wl_pointer *p, uint32_t t, wl_fixed_t x, wl_fixed_t y) {}
static void dp_button(void *d, struct wl_pointer *p, uint32_t serial, uint32_t t, uint32_t button, uint32_t state) {
    if (drag.entered && button == BTN_LEFT && state == WL_POINTER_BUTTON_STATE_PRESSED) {
        drag.serial = serial;
        drag.pressed = TRUE;
    }
}
static void dp_axis(void *d, struct wl_pointer *p, uint32_t t, uint32_t axis, wl_fixed_t v) {}
static void dp_frame(void *d, struct wl_pointer *p) {}
static void dp_axis_source(void *d, struct wl_pointer *p, uint32_t source) {}
static void dp_axis_stop(void *d, struct wl_pointer *p, uint32_t t, uint32_t axis) {}
static void dp_axis_discrete(void *d, struct wl_pointer *p, uint32_t axis, int32_t discrete) {}
static const struct wl_pointer_listener drop_pointer_listener = {
    .enter = dp_enter,
    .leave = dp_leave,
    .motion = dp_motion,
    .button = dp_button,
    .axis = dp_axis,
    .frame = dp_frame,
    .axis_source = dp_axis_source,
    .axis_stop = dp_axis_stop,
    .axis_discrete = dp_axis_discrete,
};

/* The compositor's offers to this program's own surface (the drag passing over it): let go of. */
static void dd_forget(void) {
    if (app.dnd_offer) wl_data_offer_destroy(app.dnd_offer);
    app.dnd_offer = NULL;
}
static void dd_offer(void *d, struct wl_data_device *dd, struct wl_data_offer *o) {
    dd_forget();
    app.dnd_offer = o;
}
static void dd_enter(void *d, struct wl_data_device *dd, uint32_t serial, struct wl_surface *s, wl_fixed_t x, wl_fixed_t y,
                     struct wl_data_offer *o) {}
static void dd_leave(void *d, struct wl_data_device *dd) { dd_forget(); }
static void dd_motion(void *d, struct wl_data_device *dd, uint32_t t, wl_fixed_t x, wl_fixed_t y) {}
static void dd_drop(void *d, struct wl_data_device *dd) { dd_forget(); }
static void dd_selection(void *d, struct wl_data_device *dd, struct wl_data_offer *o) {
    if (o && o == app.dnd_offer) dd_forget();
}
static const struct wl_data_device_listener drop_device_listener = {
    .data_offer = dd_offer,
    .enter = dd_enter,
    .leave = dd_leave,
    .motion = dd_motion,
    .drop = dd_drop,
    .selection = dd_selection,
};

/* The URIs to whoever took the drop: a few hundred bytes, waited on for a moment if the pipe is full. */
static void ds_send(void *d, struct wl_data_source *s, const char *mime, int32_t fd) {
    const char *p = drag.uris ? drag.uris : "";
    gsize left = strlen(p);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    gint64 until = g_get_monotonic_time() + 2 * G_USEC_PER_SEC;
    while (left && g_get_monotonic_time() < until) {
        ssize_t n = write(fd, p, left);
        if (n > 0) {
            p += n;
            left -= (gsize)n;
        } else if (n < 0 && (errno == EAGAIN || errno == EINTR)) {
            struct pollfd w = {fd, POLLOUT, 0};
            poll(&w, 1, 100);
        } else {
            break;
        }
    }
    close(fd);
    drag.sent = left == 0;
}
static void ds_target(void *d, struct wl_data_source *s, const char *mime) {
    drag.accepted = mime != NULL;
    drag.agreed = drag.accepted && (app.ddm_version < 3 || drag.action);
}
static void ds_cancelled(void *d, struct wl_data_source *s) { drag.cancelled = drag.over = TRUE; }
static void ds_drop_performed(void *d, struct wl_data_source *s) { drag.dropped = TRUE; }
static void ds_finished(void *d, struct wl_data_source *s) { drag.finished = drag.over = TRUE; }
static void ds_action(void *d, struct wl_data_source *s, uint32_t a) {
    drag.action = a;
    drag.agreed = drag.accepted && a;
}
static const struct wl_data_source_listener drop_source_listener = {
    .target = ds_target,
    .send = ds_send,
    .cancelled = ds_cancelled,
    .dnd_drop_performed = ds_drop_performed,
    .dnd_finished = ds_finished,
    .action = ds_action,
};

static void dl_configure(void *d, struct zwlr_layer_surface_v1 *l, uint32_t serial, uint32_t w, uint32_t h) {
    zwlr_layer_surface_v1_ack_configure(l, serial);
    drag.configured = TRUE;
}
static void dl_closed(void *d, struct zwlr_layer_surface_v1 *l) {}
static const struct zwlr_layer_surface_v1_listener drop_layer_listener = {.configure = dl_configure, .closed = dl_closed};

/* A buffer of nothing: fully transparent pixels, zeroes. */
static struct wl_buffer *clear_buffer(int w, int h) {
    int fd = memfd_create("openandroiddex-drop", MFD_CLOEXEC);
    if (fd < 0) return NULL;
    struct wl_buffer *b = NULL;
    if (ftruncate(fd, (off_t)w * h * 4) == 0) {
        struct wl_shm_pool *pool = wl_shm_create_pool(app.shm, fd, w * h * 4);
        b = wl_shm_pool_create_buffer(pool, 0, w, h, w * 4, WL_SHM_FORMAT_ARGB8888);
        wl_shm_pool_destroy(pool);
    }
    close(fd);
    return b;
}

/* The compositor's events until *flag, the drag is over, or `ms` have passed. */
static gboolean drag_wait(gboolean *flag, int ms) {
    gint64 until = g_get_monotonic_time() + (gint64)ms * 1000;
    while (!*flag && !drag.over) {
        gint64 left = (until - g_get_monotonic_time()) / 1000;
        if (left <= 0 || dispatch_for((int)MAX(left, 1)) < 0) break;
    }
    return *flag;
}

/* This program's own pointer, borrowed: fake input on KWin, the virtual pointer on wlroots. */
static void drag_move(int x, int y) {
    int pw = g_atomic_int_get(&app.pw), ph = g_atomic_int_get(&app.ph);
    if (app.wlr) {
        if (!app.vpointer || pw <= 0 || ph <= 0) return;
        zwlr_virtual_pointer_v1_motion_absolute(app.vpointer, ms32(), (uint32_t)CLAMP(x, 0, pw - 1),
                                                (uint32_t)CLAMP(y, 0, ph - 1), (uint32_t)pw, (uint32_t)ph);
        zwlr_virtual_pointer_v1_frame(app.vpointer);
    } else {
        double gx, gy;
        if (app.fake && to_global(x, y, &gx, &gy))
            org_kde_kwin_fake_input_pointer_motion_absolute(app.fake, wl_fixed_from_double(gx), wl_fixed_from_double(gy));
    }
    wl_display_flush(app.display);
}

static void drag_button(gboolean down) {
    uint32_t state = down ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED;
    if (app.wlr) {
        zwlr_virtual_pointer_v1_button(app.vpointer, ms32(), BTN_LEFT, state);
        zwlr_virtual_pointer_v1_frame(app.vpointer);
    } else {
        org_kde_kwin_fake_input_button(app.fake, BTN_LEFT, state);
    }
    wl_display_flush(app.display);
}

static gboolean can_carry(void) {
    return app.compositor && app.shm && app.layer_shell && app.ddm && app.seat &&
           (app.wlr ? app.vpointer != NULL : app.fake != NULL);
}

static gboolean carry(int x, int y, const char *uris, GString *why) {
    struct output *o = our_output();
    int pw = g_atomic_int_get(&app.pw), ph = g_atomic_int_get(&app.ph);
    if (!can_carry() || !o || pw <= 0 || ph <= 0) {
        g_string_assign(why, "this phone's desktop can't hand files to its apps");
        return FALSE;
    }
    if (!(app.seat_caps & WL_SEAT_CAPABILITY_POINTER)) {
        g_string_assign(why, "the phone's desktop has no pointer to drop with");
        return FALSE;
    }
    if (app.buttons) {
        g_string_assign(why, "a mouse button was held down on the computer");
        return FALSE;
    }
    x = CLAMP(x, 0, pw - 1);
    y = CLAMP(y, 0, ph - 1);
    if (!app.wl_pointer) {
        app.wl_pointer = wl_seat_get_pointer(app.seat);
        wl_pointer_add_listener(app.wl_pointer, &drop_pointer_listener, NULL);
    }
    if (!app.data_device) {
        app.data_device = wl_data_device_manager_get_data_device(app.ddm, app.seat);
        wl_data_device_add_listener(app.data_device, &drop_device_listener, NULL);
    }
    memset(&drag, 0, sizeof drag);
    drag.uris = uris;
    gint64 t0 = g_get_monotonic_time();
    gboolean ok = FALSE;

    /* Eight pixels of this program's own over everything, around the point (in the screen's logical units). */
    double lw = o->logical && o->lw > 0 ? o->lw : pw / app.scale;
    double lh = o->logical && o->lh > 0 ? o->lh : ph / app.scale;
    int cx = (int)((x + 0.5) * lw / pw), cy = (int)((y + 0.5) * lh / ph);
    int left = CLAMP(cx - DROP_SIZE / 2, 0, MAX(0, (int)lw - DROP_SIZE));
    int top = CLAMP(cy - DROP_SIZE / 2, 0, MAX(0, (int)lh - DROP_SIZE));
    drag.surface = wl_compositor_create_surface(app.compositor);
    drag.layer = zwlr_layer_shell_v1_get_layer_surface(app.layer_shell, drag.surface, o->wl,
                                                       ZWLR_LAYER_SHELL_V1_LAYER_OVERLAY, "openandroiddex-drop");
    zwlr_layer_surface_v1_add_listener(drag.layer, &drop_layer_listener, NULL);
    zwlr_layer_surface_v1_set_size(drag.layer, DROP_SIZE, DROP_SIZE);
    zwlr_layer_surface_v1_set_anchor(drag.layer, ZWLR_LAYER_SURFACE_V1_ANCHOR_TOP | ZWLR_LAYER_SURFACE_V1_ANCHOR_LEFT);
    zwlr_layer_surface_v1_set_margin(drag.layer, top, 0, 0, left);
    zwlr_layer_surface_v1_set_exclusive_zone(drag.layer, -1);
    zwlr_layer_surface_v1_set_keyboard_interactivity(drag.layer, ZWLR_LAYER_SURFACE_V1_KEYBOARD_INTERACTIVITY_NONE);
    wl_surface_commit(drag.surface);
    if (!drag_wait(&drag.configured, 1000)) {
        g_string_assign(why, "the phone's desktop didn't place the drop");
        goto out;
    }
    drag.buffer = clear_buffer(DROP_SIZE, DROP_SIZE);
    if (!drag.buffer) {
        g_string_assign(why, "no memory for the drop");
        goto out;
    }
    wl_surface_attach(drag.surface, drag.buffer, 0, 0);
    wl_surface_damage(drag.surface, 0, 0, DROP_SIZE, DROP_SIZE);
    wl_surface_commit(drag.surface);
    wl_display_roundtrip(app.display);

    /* The pointer onto them, the button pressed there. */
    int nx = x + 1 < pw ? x + 1 : x - 1;
    drag_move(nx, y);
    drag_move(x, y);
    if (!drag_wait(&drag.entered, 600)) {
        g_string_assign(why, "the drop didn't get the pointer");
        goto out;
    }
    drag_button(TRUE);
    if (!drag_wait(&drag.pressed, 600)) {
        drag_button(FALSE);
        g_string_assign(why, "the drop didn't get the button");
        goto out;
    }

    /* The drag, from them, carrying the URIs; then they let the pointer through. */
    drag.source = wl_data_device_manager_create_data_source(app.ddm);
    wl_data_source_add_listener(drag.source, &drop_source_listener, NULL);
    wl_data_source_offer(drag.source, "text/uri-list");
    if (app.ddm_version >= 3) wl_data_source_set_actions(drag.source, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);
    wl_data_device_start_drag(app.data_device, drag.source, drag.surface, NULL, drag.serial);
    struct wl_region *none = wl_compositor_create_region(app.compositor);
    wl_surface_set_input_region(drag.surface, none);
    wl_region_destroy(none);
    wl_surface_commit(drag.surface);
    wl_display_roundtrip(app.display);

    /* A pixel and back: the compositor looks for what is under the pointer as it moves. Apps say
     * whether they take it as the pointer comes over them, a browser after asking its page. */
    for (int tries = 0; tries < 3 && !drag.agreed && !drag.over; tries++) {
        drag_move(nx, y);
        drag_wait(&drag.agreed, 60);
        drag_move(x, y);
        drag_wait(&drag.agreed, tries == 0 ? 500 : 300);
    }
    drag_button(FALSE);
    drag_wait(&drag.over, 3000);
    ok = drag.dropped && (drag.finished || drag.sent);
    if (!ok) g_string_assign(why, drag.agreed ? "the app there didn't take it" : "nothing there takes files");
    say("drop: %s, %d ms", ok ? "taken by the app under it" : why->str, (int)((g_get_monotonic_time() - t0) / 1000));
out:
    if (drag.source) wl_data_source_destroy(drag.source);
    if (drag.layer) zwlr_layer_surface_v1_destroy(drag.layer);
    if (drag.surface) wl_surface_destroy(drag.surface);
    if (drag.buffer) wl_buffer_destroy(drag.buffer);
    memset(&drag, 0, sizeof drag);
    /* The pointer back where the computer has it (if it has said yet: no pushing into a corner). */
    if (app.last_x >= 0) drag_move(app.last_x, app.last_y);
    wl_display_roundtrip(app.display);
    return ok;
}

static void on_drop(const char *json) {
    char v[32];
    int x = json_raw(json, "x", v, sizeof v) ? atoi(v) : -1;
    int y = json_raw(json, "y", v, sizeof v) ? atoi(v) : -1;
    GString *uris = json_text(json, "uris");
    GString *why = g_string_new(NULL);
    gboolean taken = FALSE;
    if (!uris || !g_str_has_prefix(uris->str, "file://") || x < 0 || y < 0)
        g_string_assign(why, "the drop came without its files or its place");
    else taken = carry(x, y, uris->str, why);
    GString *s = g_string_new("{\"t\":\"dropped\",\"taken\":");
    g_string_append(s, taken ? "true" : "false");
    if (!taken) {
        g_string_append(s, ",\"why\":");
        json_string(s, why->str);
    }
    g_string_append_c(s, '}');
    send_text(s->str);
    g_string_free(s, TRUE);
    g_string_free(why, TRUE);
    if (uris) g_string_free(uris, TRUE);
}

/* ── Files from the computer: receive ──────────────────────────────────── */

/*
 * The computer starts `receive` for each drop on its window, on a second
 * channel of its SSH connection, and hands it the files on stdin in frames
 * like serve's, whose kind says what each one is:
 *
 *   'I' [u8 folder][name]                       an item dropped: a file, or a folder with all in it
 *   'D' [path]                                  a folder in the item ("photos/raw"), parents first
 *   'F' [u64 size][i64 mtime][u32 mode][path]   a file in it ("" for an item that is a file),
 *                                               then its bytes in 'B' frames, `size` in all
 *   'G' (as 'F')                                the same, answering {"t":"opened"} once the file is made
 *   'B' [bytes]
 *   'S'                                         the file's bytes came another way (below): all there now
 *   'E' [i64 mtime]                             the item is whole
 *   'X'                                         take the item back (cancelled, or unreadable there)
 *   'Q'                                         that was all
 *
 * A big file's bytes come faster over several channels at once: the phone's
 * SSH server (dropbear) gives each channel a window of 24 KB, which on a cable
 * is about 10 MB/s a channel; four together made 30 (S24, 2026-11-16). So the
 * computer may send a big file's 'G' here, and once it is "opened" (made: the
 * channels are separate, nothing orders them), its bytes through `receive
 * --part` processes on channels of their own, each writing its share where it
 * goes, and then 'S'. Those take:
 *
 *   'O' [path]                                  a file this receive made, under Downloads ("trip (1)/a.mp4")
 *   'P' [u64 offset][bytes]                     bytes of it, at that offset
 *   'C'                                         that file is done for now: answers {"t":"done"}
 *   'Q'                                         that was all
 *
 * All of it goes into the user's Downloads (XDG_DOWNLOAD_DIR), never over
 * anything there: an item whose name is taken is called "name (1).ext", as a
 * browser names a second download, and every file and folder is made with
 * O_EXCL or mkdir, which refuse a name taken meanwhile, and nothing is
 * followed through a link. Names are checked a part at a time. An item that
 * doesn't finish (stdin ending first, 'X', a write that fails) is taken back
 * whole. It answers in serve's JSON frames: {"t":"ready","dir","free"},
 * {"t":"claimed","name"} for each item as it is named, {"t":"landed","name",
 * "uri"} once it is whole, {"t":"undone"}, {"t":"error","message"},
 * {"t":"bye"}.
 */
static struct {
    int dir_fd;
    char *dir;
    char *name;             /* the item's name in Downloads, once it has one */
    gboolean is_dir;
    int item_fd;            /* the item's folder */
    GPtrArray *files, *dirs; /* what the item has made, under Downloads, for taking it back */
    int file_fd;            /* the file being written */
    char *file_name;        /* its path in the item, for a message */
    guint64 size, left;     /* its size, and its bytes still to come here */
    gint64 mtime;
} rx = {.dir_fd = -1, .item_fd = -1, .file_fd = -1};

/* A name's extension, where a copy's number goes in front of it: Thunar's rule, as sftp.rs's copy_name. */
static const char *dot_before(const char *name, const char *before) {
    for (const char *p = before - 1; p > name; p--)
        if (*p == '.') return p;
    return NULL;
}
static const char *extension_of(const char *name) {
    const char *dot = strrchr(name, '.');
    if (!dot || dot == name || !dot[1]) return NULL;
    const char *ext = dot + 1;
    static const char *const packed[] = {"gz", "bz2", "lzma", "lrz", "rpm", "lzo", "xz", "z", NULL};
    for (int i = 0; packed[i]; i++) {
        if (g_ascii_strcasecmp(ext, packed[i]) != 0) continue;
        const char *d2 = dot_before(name, dot);
        if (d2 && dot - d2 - 1 >= 2 && dot - d2 - 1 <= 5) dot = d2; /* backup.tar.gz */
        return dot;
    }
    if (g_ascii_strcasecmp(ext, "in") == 0) {
        for (int i = 0; i < 3; i++) {
            const char *d2 = dot_before(name, dot);
            if (!d2 || dot - d2 - 1 < 2) break;
            gboolean another_in = dot - d2 - 1 == 2 && g_ascii_strncasecmp(d2 + 1, "in", 2) == 0;
            dot = d2;
            if (!another_in) break;
        }
    }
    return dot;
}

/* The nth other name for an item already in Downloads: "report (1).pdf", "photos (2)". */
static char *numbered_name(const char *name, int n, gboolean is_dir) {
    const char *ext = is_dir ? NULL : extension_of(name);
    if (!ext) return g_strdup_printf("%s (%d)", name, n);
    return g_strdup_printf("%.*s (%d)%s", (int)(ext - name), name, n, ext);
}

static gboolean part_ok(const char *s, gsize n) {
    if (n == 0 || n > 255 || memchr(s, '/', n) || memchr(s, 0, n)) return FALSE;
    return !(n == 1 && s[0] == '.') && !(n == 2 && s[0] == '.' && s[1] == '.');
}

/* A path inside the item, from the computer: parts that are names, never "." or "..", never starting at /. */
static gboolean path_ok(const char *s) {
    if (!*s || strlen(s) > 4000) return FALSE;
    for (const char *p = s;;) {
        const char *slash = strchr(p, '/');
        if (!part_ok(p, slash ? (gsize)(slash - p) : strlen(p))) return FALSE;
        if (!slash) return TRUE;
        p = slash + 1;
    }
}

static void rx_say(const char *t, const char *key, const char *value) {
    GString *s = g_string_new("{\"t\":");
    json_string(s, t);
    if (key) {
        g_string_append_printf(s, ",\"%s\":", key);
        json_string(s, value);
    }
    g_string_append_c(s, '}');
    send_text(s->str);
    g_string_free(s, TRUE);
}

static void rx_clear(void) {
    if (rx.file_fd >= 0) close(rx.file_fd);
    if (rx.item_fd >= 0) close(rx.item_fd);
    rx.file_fd = rx.item_fd = -1;
    g_ptr_array_set_size(rx.files, 0);
    g_ptr_array_set_size(rx.dirs, 0);
    g_clear_pointer(&rx.name, g_free);
    g_clear_pointer(&rx.file_name, g_free);
    rx.left = 0;
}

/* The item in progress, taken back: its files, then its folders, deepest first. */
static void rx_undo(void) {
    if (rx.file_fd >= 0) close(rx.file_fd);
    if (rx.item_fd >= 0) close(rx.item_fd);
    rx.file_fd = rx.item_fd = -1;
    for (guint i = 0; i < rx.files->len; i++) unlinkat(rx.dir_fd, g_ptr_array_index(rx.files, i), 0);
    for (guint i = rx.dirs->len; i > 0; i--) unlinkat(rx.dir_fd, g_ptr_array_index(rx.dirs, i - 1), AT_REMOVEDIR);
    if (rx.name) say("receive: %s taken back", rx.name);
    rx_clear();
}

static int rx_fail(const char *what, int err) {
    char *msg = g_strdup_printf("%s couldn't be written on the phone: %s", what, g_strerror(err));
    say("receive: %s", msg);
    rx_say("error", "message", msg);
    g_free(msg);
    rx_undo();
    return -1;
}

static void rx_set_time(int fd, gint64 mtime) {
    if (fd < 0 || mtime <= 0) return;
    struct timespec ts[2] = {{.tv_sec = (time_t)mtime}, {.tv_sec = (time_t)mtime}};
    futimens(fd, ts);
}

/* The current file has all its bytes. */
static void rx_file_done(void) {
    rx_set_time(rx.file_fd, rx.mtime);
    close(rx.file_fd);
    rx.file_fd = -1;
}

static guint64 be64(const guint8 *p) {
    guint64 v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | p[i];
    return v;
}

/* One frame from the computer: 0 to go on, 1 when it is all done, -1 after a failure (said and undone). */
static int rx_frame(guint8 kind, const guint8 *p, gsize n) {
    if (kind == 'B') {
        if (rx.file_fd < 0 || n > rx.left) return rx_fail(rx.name ? rx.name : "a file", EPROTO);
        for (gsize off = 0; off < n;) {
            ssize_t w = write(rx.file_fd, p + off, n - off);
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) return rx_fail(rx.file_name ? rx.file_name : rx.name, w < 0 ? errno : EIO);
            off += (gsize)w;
        }
        rx.left -= n;
        if (!rx.left) rx_file_done();
        return 0;
    }
    if (kind == 'Q') {
        if (rx.name) rx_undo();
        rx_say("bye", NULL, NULL);
        return 1;
    }
    if (kind == 'X') {
        rx_undo();
        rx_say("undone", NULL, NULL);
        return 0;
    }
    char *text = NULL;
    gsize head = kind == 'I' ? 1 : kind == 'F' || kind == 'G' ? 20 : 0;
    if (kind == 'I' || kind == 'D' || kind == 'F' || kind == 'G') {
        if (n < head) return rx_fail("a file", EPROTO);
        text = g_strndup((const char *)p + head, n - head);
        if (strlen(text) != n - head || !g_utf8_validate(text, -1, NULL)) {
            g_free(text);
            return rx_fail("a file with an unusable name", EINVAL);
        }
    }
    int result = 0;
    if (kind == 'I') {
        if (rx.name) rx_undo(); /* the last one never finished */
        gboolean is_dir = p[0] != 0;
        if (!part_ok(text, strlen(text))) {
            result = rx_fail(text, EINVAL);
        } else {
            int err = EEXIST;
            for (int i = 0; i < 1000 && err == EEXIST; i++) {
                char *cand = i ? numbered_name(text, i, is_dir) : g_strdup(text);
                int r = is_dir ? mkdirat(rx.dir_fd, cand, 0755)
                               : openat(rx.dir_fd, cand, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0644);
                err = r < 0 ? errno : 0;
                if (r >= 0) {
                    rx.name = cand;
                    rx.is_dir = is_dir;
                    g_ptr_array_add(is_dir ? rx.dirs : rx.files, g_strdup(cand));
                    if (is_dir) rx.item_fd = openat(rx.dir_fd, cand, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                    else rx.file_fd = r;
                    if (is_dir && rx.item_fd < 0) err = errno;
                } else {
                    g_free(cand);
                }
            }
            if (err) result = rx_fail(text, err);
            else rx_say("claimed", "name", rx.name); /* where --part finds its files */
        }
    } else if (kind == 'S') {
        /* A big file's bytes, written by `receive --part` processes: all of them, or the item fails. */
        struct stat st;
        if (rx.file_fd < 0 || !rx.left) result = rx_fail(rx.name ? rx.name : "a file", EPROTO);
        else if (fstat(rx.file_fd, &st) != 0 || (guint64)st.st_size != rx.size)
            result = rx_fail(rx.file_name ? rx.file_name : rx.name, EIO);
        else {
            rx.left = 0;
            rx_file_done();
        }
    } else if (kind == 'D') {
        if (!rx.name || !rx.is_dir || !path_ok(text)) result = rx_fail(text, EINVAL);
        else if (mkdirat(rx.item_fd, text, 0755) != 0) result = rx_fail(text, errno);
        else g_ptr_array_add(rx.dirs, g_build_filename(rx.name, text, NULL));
    } else if (kind == 'F' || kind == 'G') {
        guint64 size = be64(p);
        gint64 mtime = (gint64)be64(p + 8);
        guint32 mode = ((guint32)p[16] << 24) | ((guint32)p[17] << 16) | ((guint32)p[18] << 8) | p[19];
        mode = (mode & 0777) | 0600;
        if (!rx.name || (rx.is_dir ? !path_ok(text) : (*text || rx.file_fd < 0)) || (rx.is_dir && rx.file_fd >= 0)) {
            result = rx_fail(*text ? text : rx.name ? rx.name : "a file", EPROTO);
        } else {
            if (rx.is_dir) {
                int fd = openat(rx.item_fd, text, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, mode);
                if (fd < 0) result = rx_fail(text, errno);
                else {
                    rx.file_fd = fd;
                    g_ptr_array_add(rx.files, g_build_filename(rx.name, text, NULL));
                }
            } else {
                fchmod(rx.file_fd, mode);
            }
            if (!result) {
                g_free(rx.file_name);
                rx.file_name = g_strdup(*text ? text : rx.name);
                rx.size = rx.left = size;
                rx.mtime = mtime;
                if (!size) rx_file_done();
                /* The file is there now: --part may open it. */
                if (kind == 'G') rx_say("opened", NULL, NULL);
            }
        }
    } else if (kind == 'E') {
        if (!rx.name || rx.left || (!rx.is_dir && rx.file_fd >= 0)) {
            result = rx_fail(rx.name ? rx.name : "an item", EPROTO);
        } else {
            if (rx.is_dir && n >= 8) rx_set_time(rx.item_fd, (gint64)be64(p));
            char *path = g_build_filename(rx.dir, rx.name, NULL);
            char *uri = g_filename_to_uri(path, NULL, NULL);
            GString *s = g_string_new("{\"t\":\"landed\",\"name\":");
            json_string(s, rx.name);
            g_string_append(s, ",\"uri\":");
            json_string(s, uri ? uri : "");
            g_string_append_c(s, '}');
            send_text(s->str);
            say("receive: %s", path);
            g_string_free(s, TRUE);
            g_free(uri);
            g_free(path);
            rx_clear();
        }
    } else {
        result = rx_fail(rx.name ? rx.name : "a file", EPROTO);
    }
    g_free(text);
    return result;
}

/* Downloads, as the user's folders name it (GLib gives the home folder for one switched off), opened. */
static gboolean rx_open_downloads(void) {
    const char *dl = g_get_user_special_dir(G_USER_DIRECTORY_DOWNLOAD);
    rx.dir = dl && *dl && g_strcmp0(dl, g_get_home_dir()) != 0 ? g_strdup(dl)
                                                              : g_build_filename(g_get_home_dir(), "Downloads", NULL);
    g_mkdir_with_parents(rx.dir, 0755);
    rx.dir_fd = open(rx.dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (rx.dir_fd >= 0) return TRUE;
    char *msg = g_strdup_printf("The phone's Downloads (%s) can't be opened: %s", rx.dir, g_strerror(errno));
    rx_say("error", "message", msg);
    say("receive: %s", msg);
    g_free(msg);
    return FALSE;
}

/* The frames on stdin, each handed to `frame` until it says to stop: its 0 goes on, 1 is done, -1 failed. */
static int rx_read_frames(int (*frame)(guint8, const guint8 *, gsize), void (*cut_short)(void)) {
    GByteArray *in = g_byte_array_new();
    guint8 buf[65536];
    int status = 0;
    for (;;) {
        gsize off = 0;
        int r = 0;
        while (r == 0 && in->len - off >= 5) {
            const guint8 *h = in->data + off;
            guint32 len = ((guint32)h[1] << 24) | ((guint32)h[2] << 16) | ((guint32)h[3] << 8) | h[4];
            if (len > (1u << 22)) {
                r = rx_fail("a file", EMSGSIZE);
                break;
            }
            if (in->len - off - 5 < len) break;
            r = frame(h[0], h + 5, len);
            off += 5 + len;
        }
        if (off) g_byte_array_remove_range(in, 0, (guint)off);
        if (r) {
            status = r < 0 ? 1 : 0;
            break;
        }
        ssize_t n = read(STDIN_FILENO, buf, sizeof buf);
        if (n > 0) g_byte_array_append(in, buf, (guint)n);
        else if (n < 0 && errno == EINTR) continue;
        else {
            /* The computer went part way. */
            if (cut_short) cut_short();
            break;
        }
    }
    g_byte_array_free(in, TRUE);
    return status;
}

static void rx_cut_short(void) {
    if (rx.name) rx_undo(); /* what was in progress goes */
}

/*
 * A file under Downloads that receive made, for --part: each folder on the way
 * opened without following a link, the file too, and nothing created.
 */
static int rx_open_part(const char *path) {
    if (!path_ok(path)) {
        errno = EINVAL;
        return -1;
    }
    char *copy = g_strdup(path);
    int at = dup(rx.dir_fd);
    char *p = copy;
    for (char *slash; at >= 0 && (slash = strchr(p, '/')); p = slash + 1) {
        *slash = 0;
        int next = openat(at, p, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(at);
        at = next;
    }
    int fd = at >= 0 ? openat(at, p, O_WRONLY | O_NOFOLLOW | O_CLOEXEC) : -1;
    if (at >= 0) close(at);
    g_free(copy);
    return fd;
}

static int part_fd = -1;

static int part_frame(guint8 kind, const guint8 *p, gsize n) {
    if (kind == 'O') {
        char *path = g_strndup((const char *)p, n);
        if (part_fd >= 0) close(part_fd);
        errno = EINVAL;
        part_fd = strlen(path) == n ? rx_open_part(path) : -1;
        int result = part_fd < 0 ? rx_fail(path, errno) : 0;
        g_free(path);
        return result;
    }
    if (kind == 'P') {
        if (part_fd < 0 || n < 8) return rx_fail("a part of a file", EPROTO);
        off_t at = (off_t)be64(p);
        for (gsize done = 8; done < n;) {
            ssize_t w = pwrite(part_fd, p + done, n - done, at + (off_t)(done - 8));
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) return rx_fail("a part of a file", w < 0 ? errno : EIO);
            done += (gsize)w;
        }
        return 0;
    }
    if (kind == 'C') {
        if (part_fd >= 0) close(part_fd);
        part_fd = -1;
        rx_say("done", NULL, NULL);
        return 0;
    }
    if (kind == 'Q') {
        rx_say("bye", NULL, NULL);
        return 1;
    }
    return rx_fail("a part of a file", EPROTO);
}

/* `receive --part`: a big file's share of bytes, beside the receive that made it (see above). */
static int cmd_receive_part(void) {
    signal(SIGPIPE, SIG_IGN);
    rx.files = g_ptr_array_new_with_free_func(g_free);
    rx.dirs = g_ptr_array_new_with_free_func(g_free);
    if (!rx_open_downloads()) return 1;
    rx_say("ready", NULL, NULL);
    int status = rx_read_frames(part_frame, NULL);
    if (part_fd >= 0) close(part_fd);
    return status;
}

static int cmd_receive(void) {
    signal(SIGPIPE, SIG_IGN);
    rx.files = g_ptr_array_new_with_free_func(g_free);
    rx.dirs = g_ptr_array_new_with_free_func(g_free);
    if (!rx_open_downloads()) return 1;
    struct statvfs fs;
    guint64 room = fstatvfs(rx.dir_fd, &fs) == 0 ? (guint64)fs.f_bavail * fs.f_frsize : 0;
    GString *s = g_string_new("{\"t\":\"ready\",\"dir\":");
    json_string(s, rx.dir);
    g_string_append_printf(s, ",\"free\":%" G_GUINT64_FORMAT "}", room);
    send_text(s->str);
    g_string_free(s, TRUE);
    return rx_read_frames(rx_frame, rx_cut_short);
}

/* ── clip: the phone's clipboard, by hand ──────────────────────────────── */

static gboolean clip_cli_wayland(gint fd, GIOCondition cond, gpointer d) {
    if ((cond & (G_IO_ERR | G_IO_HUP | G_IO_NVAL)) || wl_display_dispatch(app.display) < 0) {
        g_main_loop_quit(app.loop);
        return G_SOURCE_REMOVE;
    }
    wl_display_flush(app.display);
    return G_SOURCE_CONTINUE;
}

static gboolean clip_cli_enough(gpointer d) {
    g_main_loop_quit(app.loop);
    return G_SOURCE_REMOVE;
}

static const char *arg_value(int argc, char **argv, const char *name);

static int cmd_clip(int argc, char **argv) {
    const char *set = arg_value(argc, argv, "--set");
    GString *why = g_string_new(NULL);
    clipboard_only = TRUE;
    if (!wayland_connect(why)) {
        say("%s", why->str);
        return 1;
    }
    clip_start();
    if (!app.dc_device) return 1;
    app.loop = g_main_loop_new(NULL, FALSE);
    g_unix_fd_add(wl_display_get_fd(app.display), G_IO_IN | G_IO_HUP | G_IO_ERR, clip_cli_wayland, NULL);
    if (set) {
        clip_holding = TRUE;
        clip_give(set, strlen(set));
        g_timeout_add_seconds(600, clip_cli_enough, NULL);
    } else {
        app.clip_print = TRUE;
        wl_display_roundtrip(app.display); /* the clipboard as it is, which starts the read */
        if (!app.clip_reading) {
            say("clip: there is no text on the phone's clipboard");
            return 1;
        }
        g_timeout_add_seconds(6, clip_cli_enough, NULL);
    }
    g_main_loop_run(app.loop);
    wl_display_disconnect(app.display);
    return 0;
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
    /* A window that comes says where its clipboard switch is once it shows the picture; not before. */
    app.clip_share = FALSE;
    g_atomic_int_set(&app.attached, 1);
    send_state("running", NULL);
    force_keyframe();
    /* Phosh's screen is copied only while a window watches it; its last frame meanwhile goes again. A
     * headless output has no cursor plane, so phoc draws its pointer into the picture: the window's
     * own goes (a cursor message of no size), or there would be two. */
    if (app.wlr) {
        static const guint8 no_cursor[9] = {0x02};
        send_frame(KIND_BINARY, no_cursor, sizeof no_cursor, NULL, 0);
        capture_next();
        if (app.last_buf) push_buffer(gst_buffer_ref(app.last_buf));
    }
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
        app.clip_share = FALSE;
        clip_read_stop();
        release_all();
    } else if (strcmp(t, "stop") == 0) {
        finish(0);
    } else if (strcmp(t, "resize") == 0) {
        on_resize(json);
    } else if (strcmp(t, "clip") == 0) {
        on_clip(json);
    } else if (strcmp(t, "clipshare") == 0) {
        on_clipshare(json);
    } else if (strcmp(t, "drop") == 0) {
        on_drop(json);
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
        /* For the log: every ten seconds that had more than the still screen's resends, what went out. */
        static int sum, best, kb, ticks;
        sum += frames;
        best = MAX(best, frames);
        kb += bytes / 125;
        if (++ticks == 10) {
            if (best > 1000 / KEEPALIVE_MS + 1)
                say("video: %.1f frames a second over 10 s, %d in the busiest second, %d kb/s", sum / 10.0, best,
                    kb / 10);
            sum = best = kb = ticks = 0;
        }
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
    /* Phosh's way (wlroots): its screens, and the headless one that would be the computer's. */
    struct head *hl = headless_head();
    g_string_append_printf(s, ",\"wlr\":{\"outputManagement\":%s,\"screencopy\":%u,\"virtualPointer\":%u,"
                              "\"virtualKeyboard\":%s,\"headless\":",
                           app.om ? "true" : "false", app.copier_version, app.vp_version,
                           app.vk_manager ? "true" : "false");
    json_string(s, hl ? hl->name : "");
    g_string_append_printf(s, ",\"headlessOn\":%s,\"heads\":[", hl && hl->enabled ? "true" : "false");
    for (GList *l = app.heads; l; l = l->next) {
        struct head *hd = l->data;
        g_string_append_printf(s, "%s{\"name\":", l == app.heads ? "" : ",");
        json_string(s, hd->name);
        g_string_append_printf(s, ",\"on\":%s,\"w\":%d,\"h\":%d,\"scale\":%.3f,\"x\":%d,\"y\":%d}",
                               hd->enabled ? "true" : "false", hd->current ? hd->current->w : 0,
                               hd->current ? hd->current->h : 0, hd->scale, hd->x, hd->y);
    }
    g_string_append(s, "]}");
    /* The clipboard (data-control) and handing dropped files to apps (layer shell, drag and drop). */
    g_string_append_printf(s, ",\"dataControl\":\"%s\",\"layerShell\":%s,\"dataDevice\":%u",
                           app.dc_ext ? "ext" : app.dc_wlr ? "wlr" : "", app.layer_shell ? "true" : "false",
                           app.ddm ? app.ddm_version : 0);
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
    if (!wayland_connect(why)) {
        say("%s", why->str);
        return 1;
    }
    if (!app.screencast && app.om) {
        say("snapshot is for KWin's virtual output; on Phosh, grim -o HEADLESS-1 shows the computer's screen");
        return 2;
    }
    if (!make_screen(why)) {
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
    app.last_x = app.last_y = -1; /* the computer's pointer: not said yet */
    GString *why = g_string_new(NULL);
    if (!wayland_connect(why) || !make_screen(why)) {
        say("%s", why->str);
        send_state("failed", why->str);
        return 1;
    }
    if (!app.wlr) keep_scale();
    /* The stream starts at the size the screen was made at. */
    app.t0 = g_get_monotonic_time();
    ask_stream_size(app.width, app.height);
    if (!app.wlr) start_sizer();
    /* Phosh's frames are XRGB8888 on every renderer seen so far; another kind rebuilds the pipeline (push_shot). */
    app.src_w = app.width;
    app.src_h = app.height;
    app.src_format = WL_SHM_FORMAT_XRGB8888;
    app.candidates = encoder_candidates();
    if (!start_video(why)) {
        say("%s", why->str);
        send_state("failed", why->str);
        stop_sizer();
        close_screen();
        return 1;
    }
    if (!app.wlr) dock();
    inhibit();
    here_start();
    clip_start();
    {
        /* What the window can count on: typing, the clipboard, files dropped handed to the app under them. */
        struct output *o = our_output();
        GString *s = g_string_new("{\"t\":\"ready\",\"output\":");
        json_string(s, o && o->name ? o->name : "");
        g_string_append_printf(s, ",\"w\":%d,\"h\":%d,\"scale\":%.4f,\"encoder\":\"%s\",\"hw\":%s,\"keys\":%s",
                               app.width, app.height, app.scale, app.encoder, app.hw ? "true" : "false",
                               (app.wlr ? app.vkeyboard != NULL : app.fake && app.fake_version >= 4) ? "true" : "false");
        g_string_append_printf(s, ",\"clipboard\":%s,\"carry\":%s}", app.dc_device ? "true" : "false",
                               can_carry() ? "true" : "false");
        send_text(s->str);
        g_string_free(s, TRUE);
    }
    app.loop = g_main_loop_new(NULL, FALSE);
    fcntl(STDIN_FILENO, F_SETFL, fcntl(STDIN_FILENO, F_GETFL) | O_NONBLOCK);
    g_unix_fd_add(STDIN_FILENO, G_IO_IN | G_IO_HUP | G_IO_ERR, on_stdin, NULL);
    wl_display_flush(app.display);
    g_unix_fd_add(wl_display_get_fd(app.display), G_IO_IN | G_IO_HUP | G_IO_ERR, on_wayland, NULL);
    g_timeout_add_seconds(1, on_tick, NULL);
    if (app.wlr) g_timeout_add(KEEPALIVE_MS / 5, keepalive, NULL);
    if (app.wlr && app.primary_wanted && !app.primary_moved) g_timeout_add_seconds(1, retry_primary, NULL);
    g_unix_signal_add(SIGTERM, on_signal, NULL);
    g_unix_signal_add(SIGINT, on_signal, NULL);
    g_unix_signal_add(SIGHUP, on_signal, NULL);
    g_main_loop_run(app.loop);

    /* Before the screen goes: its windows come to the phone's, and that is no one's asking. */
    here_stop();
    release_all();
    g_atomic_int_set(&app.attached, 0);
    stop_video();
    stop_sizer();
    close_screen();
    /* After the screen has gone, so its windows come to the phone's screen as phone apps. */
    undock();
    /* And a keyboard laid out for the phone alone (see restart_keyboard); the guard needn't, then. */
    if (!app.wlr) restart_keyboard(app.bus, "the computer's screen has gone");
    if (app.guard_fd > 0 && write(app.guard_fd, "k", 1) != 1) say("guard: couldn't tell it the keyboard is done");
    wl_display_disconnect(app.display);
    say("done");
    return app.status;
}

/*
 * The phone as it was before a computer: Plasma Mobile's docked mode as the
 * phone had it, and Phosh's headless output off with its shell on the
 * phone's panel. The session's autostart runs this at login (the output
 * comes on with the session), and the guard after serve has gone, however
 * it went.
 */
static int cmd_rest(void) {
    /* The script a serve that died left in KWin; no session bus, or no KWin (Phosh), is nothing to do. */
    GDBusConnection *bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
    if (bus) {
        if (unload_here(bus)) say("rest: KWin's script stopped");
        g_object_unref(bus);
    }
    undock();
    GString *why = g_string_new(NULL);
    if (!wayland_connect(why)) {
        say("rest: %s", why->str);
        return 1;
    }
    struct head *hd = headless_head();
    if (!hd) say("rest: this desktop has no headless output");
    else if (!hd->enabled) say("rest: %s is off already", hd->name);
    else {
        /* Off at the size it had last: Phosh turns it on at that size next time, so
         * the same computer's window needs no new one (see wlr_make_screen). */
        int w, h;
        double s;
        if (remembered_size(&w, &h, &s) &&
            (!hd->current || hd->current->w != w || hd->current->h != h || fabs(hd->scale - s) > 0.01) &&
            apply_sync(hd, TRUE, w, h, s))
            say("rest: %s at %dx%d for the next computer", hd->name, w, h);
        hd = headless_head();
        if (hd) put_phone_back(hd);
    }
    wl_display_disconnect(app.display);
    g_string_free(why, TRUE);
    return 0;
}

/* start_guard's process: waits on fd 3 for serve to end, then rests. It logs to a file: it has no stderr. */
static int cmd_guard(void) {
    char *dir = g_build_filename(g_get_user_cache_dir(), "openandroiddex", NULL);
    char *log = g_build_filename(dir, "guard.log", NULL);
    struct stat st;
    g_mkdir_with_parents(dir, 0700);
    int fd = open(log, O_WRONLY | O_CREAT | O_CLOEXEC | (stat(log, &st) == 0 && st.st_size > 262144 ? O_TRUNC : O_APPEND),
                  0600);
    if (fd >= 0) {
        dup2(fd, 2);
        close(fd);
    }
    g_free(log);
    g_free(dir);
    char c;
    gboolean keyboard_done = FALSE; /* serve ended cleanly and saw to the keyboard ('k') */
    for (;;) {
        ssize_t n = read(3, &c, 1);
        if (n > 0 && c == 'k') keyboard_done = TRUE;
        if (n > 0 || (n < 0 && errno == EINTR)) continue;
        break;
    }
    close(3);
    GDateTime *now = g_date_time_new_now_local();
    char *when = g_date_time_format(now, "%F %T");
    say("guard: serve ended at %s", when);
    g_free(when);
    g_date_time_unref(now);
    int status = cmd_rest();
    /* serve died with the computer's screen up: the keyboard as serve would have left it (restart_keyboard). */
    GDBusConnection *bus = keyboard_done ? NULL : g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
    if (bus) {
        restart_keyboard(bus, "guard");
        g_object_unref(bus);
    }
    return status;
}

int main(int argc, char **argv) {
    g_mutex_init(&app.out_lock);
    const char *cmd = argc > 1 ? argv[1] : "";
    if (strcmp(cmd, "version") == 0) {
        printf("openandroiddex-pmos %s\n", VERSION);
        return 0;
    }
    if (strcmp(cmd, "rest") == 0 || strcmp(cmd, "guard") == 0) {
        adopt_session();
        return strcmp(cmd, "rest") == 0 ? cmd_rest() : cmd_guard();
    }
    /* Files into Downloads: no desktop needed. */
    if (strcmp(cmd, "receive") == 0)
        return argc > 2 && strcmp(argv[2], "--part") == 0 ? cmd_receive_part() : cmd_receive();
    if (strcmp(cmd, "clip") == 0) {
        adopt_session();
        return cmd_clip(argc, argv);
    }
    if (strcmp(cmd, "probe") != 0 && strcmp(cmd, "serve") != 0 && strcmp(cmd, "snapshot") != 0) {
        fprintf(stderr, "usage: openandroiddex-pmos version|probe|serve|snapshot|rest|receive|clip [--width W --height H "
                        "--scale S]\n");
        return 2;
    }
    if (!parse_args(argc, argv)) return 2;
    /* Before any thread starts (adopt_session's bus has one): they all take this process's cores. */
    if (strcmp(cmd, "serve") == 0) prefer_fast_cores();
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

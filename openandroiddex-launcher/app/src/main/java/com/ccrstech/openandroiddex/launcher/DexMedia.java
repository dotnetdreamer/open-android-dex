package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/**
 * Whatever the phone is playing, and the volume it is playing at — the half of
 * the quick-settings panel that is about the phone rather than about its
 * radios.
 *
 * Two different mechanisms behind one surface, and they answer to different
 * gates, which is worth knowing when only half of the panel works:
 *
 * <ul>
 * <li><b>Volume</b> is {@link AudioManager} and needs nothing. It works the
 *     moment the desktop comes up, with or without the notification grant.</li>
 * <li><b>Transport</b> is {@link MediaSessionManager}, and reading other apps'
 *     sessions is gated on an ENABLED NOTIFICATION LISTENER — not on a
 *     permission of its own. {@link DexNotifications} is that listener, so the
 *     one grant carries both features and neither can be had alone.</li>
 * </ul>
 *
 * Nothing here holds a controller between calls. A media session outlives our
 * popup, gets replaced when the user switches from a podcast to a video, and
 * dies when the app does; looking it up per call is a handful of binder
 * round-trips against a panel that is open for seconds at a time, and it
 * removes any chance of driving a session that has already gone.
 */
final class DexMedia {

    private DexMedia() {
    }

    /**
     * The session the buttons drive: the first one the platform hands back.
     *
     * That ordering is not arbitrary — {@code getActiveSessions} returns them
     * in priority order, with whatever most recently held audio focus at the
     * front, so this is the same session the phone's own volume keys would
     * reach. Null when nothing is playing, or when the listener grant is
     * missing, and callers must treat those two as the same thing: both mean
     * "no transport to offer".
     */
    static MediaController controller(Context ctx) {
        if (!DexNotifications.connected()) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> sessions =
                    msm.getActiveSessions(DexNotifications.component(ctx));
            if (sessions == null || sessions.isEmpty()) return null;
            return sessions.get(0);
        } catch (SecurityException e) {
            // The grant went away between the listener binding and this call —
            // a revoke, or a profile switch. Not worth a warning on a path that
            // runs every time the panel opens.
            return null;
        } catch (Exception e) {
            DexLog.warn("media", "cannot read the active media sessions", e);
            return null;
        }
    }

    /** What the panel draws: the track, who is playing it, and whether it is. */
    static final class Now {
        String title = "";
        String artist = "";
        /** The posting app, for the icon beside the card. */
        String pkg = "";
        Bitmap art;
        boolean playing;
        boolean canSkipNext;
        boolean canSkipPrevious;
    }

    /** A snapshot of the current session, or null when there is nothing to show. */
    static Now now(Context ctx) {
        MediaController c = controller(ctx);
        if (c == null) return null;
        Now now = new Now();
        now.pkg = c.getPackageName();
        MediaMetadata meta = c.getMetadata();
        if (meta != null) {
            now.title = str(meta, MediaMetadata.METADATA_KEY_TITLE);
            if (now.title.isEmpty()) {
                now.title = str(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }
            now.artist = str(meta, MediaMetadata.METADATA_KEY_ARTIST);
            if (now.artist.isEmpty()) {
                now.artist = str(meta, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            if (now.artist.isEmpty()) {
                now.artist = str(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            }
            now.art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (now.art == null) now.art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (now.art == null) {
                now.art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
        }
        PlaybackState state = c.getPlaybackState();
        if (state != null) {
            now.playing = state.getState() == PlaybackState.STATE_PLAYING
                    || state.getState() == PlaybackState.STATE_BUFFERING;
            long actions = state.getActions();
            now.canSkipNext = (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
            now.canSkipPrevious = (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0;
        }
        // A session with no metadata at all is a session with nothing to say —
        // some apps keep one alive long after playback ended. Showing a card
        // titled after the package name is worse than showing no card.
        if (now.title.isEmpty() && now.artist.isEmpty() && !now.playing) return null;
        if (now.title.isEmpty()) now.title = appLabel(ctx, now.pkg);
        return now;
    }

    private static String str(MediaMetadata meta, String key) {
        CharSequence value = meta.getText(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String appLabel(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ── transport ──────────────────────────────────────────────────────────

    /** @return the state playback is now IN, so the caller can restyle its glyph. */
    static boolean togglePlay(Context ctx) {
        MediaController c = controller(ctx);
        if (c == null) return false;
        PlaybackState state = c.getPlaybackState();
        boolean playing = state != null
                && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
        try {
            if (playing) {
                c.getTransportControls().pause();
            } else {
                c.getTransportControls().play();
            }
            return !playing;
        } catch (Exception e) {
            DexLog.warn("media", "cannot toggle playback", e);
            return playing;
        }
    }

    static void next(Context ctx) {
        MediaController c = controller(ctx);
        if (c == null) return;
        try {
            c.getTransportControls().skipToNext();
        } catch (Exception e) {
            DexLog.warn("media", "cannot skip forward", e);
        }
    }

    static void previous(Context ctx) {
        MediaController c = controller(ctx);
        if (c == null) return;
        try {
            c.getTransportControls().skipToPrevious();
        } catch (Exception e) {
            DexLog.warn("media", "cannot skip back", e);
        }
    }

    // ── where the sound comes out ──────────────────────────────────────────

    /**
     * The three answers to "where does sound play", as one value.
     *
     * A single choice in the UI because that is how a person thinks about it,
     * even though it is two scrcpy arguments underneath — see
     * {@link DexPrefs#KEY_AUDIO_DUP}. Keeping the mapping in one place is what
     * stops the two keys drifting into a state with no name, like "not
     * forwarding, but duplicating".
     */
    static final String AUDIO_COMPUTER = "computer";
    static final String AUDIO_BOTH = "both";
    static final String AUDIO_PHONE = "phone";

    /** The three, in the order Settings lists them. */
    static final String[] AUDIO_MODES = {AUDIO_COMPUTER, AUDIO_BOTH, AUDIO_PHONE};

    static String audioMode(Context ctx) {
        if (!DexPrefs.getBool(ctx, DexPrefs.KEY_AUDIO, DexPrefs.DEF_AUDIO)) return AUDIO_PHONE;
        return DexPrefs.getBool(ctx, DexPrefs.KEY_AUDIO_DUP, DexPrefs.DEF_AUDIO_DUP)
                ? AUDIO_BOTH : AUDIO_COMPUTER;
    }

    /**
     * Choose where sound plays.
     *
     * Writes BOTH underlying keys every time, rather than only the one that
     * changed. "Phone only" is forwarding off, and leaving the duplicate flag
     * set from a previous choice would silently pick it up again the next time
     * forwarding came back on.
     *
     * The values are pushed to the desktop app, which cycles its audio-only
     * scrcpy companion to match — the desktop itself carries no audio — so
     * the change is audible within a couple of seconds, no restart involved.
     *
     * The wire key must be the cfg key WITHOUT the {@code stream_} prefix:
     * the PC stores what it receives verbatim and reads "audio"/"audiodup".
     * This method once pushed the prefixed pref key, which the PC filed under
     * a name nothing reads — the chooser looked like it worked (the pref, and
     * so the UI, changed) while every session kept the default. That is the
     * bug to think of before "simplifying" these two lines back.
     */
    static void setAudioMode(Context ctx, String mode) {
        boolean forward = !AUDIO_PHONE.equals(mode);
        boolean duplicate = AUDIO_BOTH.equals(mode);
        DexPrefs.put(ctx, DexPrefs.KEY_AUDIO, forward);
        DexPrefs.put(ctx, DexPrefs.KEY_AUDIO_DUP, duplicate);
        RequestProvider.enqueue("cfg", cfgKey(DexPrefs.KEY_AUDIO)
                + "." + (forward ? "on" : "off"));
        RequestProvider.enqueue("cfg", cfgKey(DexPrefs.KEY_AUDIO_DUP)
                + "." + (duplicate ? "on" : "off"));
        DexLog.step("media", "sound will play on: " + mode + " (live)");
    }

    /** A pref key as the PC expects it on the wire: the PC_PREFIX stripped. */
    private static String cfgKey(String prefKey) {
        return prefKey.substring(DexPrefs.PC_PREFIX.length());
    }

    /**
     * The value the tray's "Media output" row shows: the chosen mode, named by
     * where the sound actually comes out. For "phone" that is the phone's own
     * active route — "Galaxy Buds" says more than "Phone only" does.
     */
    static String modeLabel(Context ctx) {
        switch (audioMode(ctx)) {
            case AUDIO_COMPUTER:
                return ctx.getString(R.string.lx_audio_computer);
            case AUDIO_BOTH:
                return ctx.getString(R.string.lx_audio_both);
            default:
                return outputName(ctx);
        }
    }

    /**
     * What the PHONE is currently playing through — its speaker, a headset, a
     * Bluetooth device.
     *
     * A different question from {@link #audioMode}, and both belong in the
     * panel: one decides whether the phone makes any sound at all, this one
     * decides which of the phone's own outputs it comes out of. Neither is the
     * other's answer, and "there is no sound" can be either.
     *
     * MediaRouter rather than AudioManager's device list because it gives a
     * NAME a person recognises ("Galaxy Buds") instead of a device-type
     * constant, and it reports the ACTIVE route rather than every route
     * available.
     */
    static String outputName(Context ctx) {
        try {
            android.media.MediaRouter router = (android.media.MediaRouter)
                    ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router != null) {
                android.media.MediaRouter.RouteInfo route = router.getSelectedRoute(
                        android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
                if (route != null && route.getName() != null) {
                    String name = route.getName().toString().trim();
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (Exception e) {
            DexLog.warn("media", "cannot read the current audio route", e);
        }
        return ctx.getString(R.string.lx_output_unknown);
    }

    /**
     * One of the phone's own sound outputs, as the media-output flyout lists
     * it. {@code type} and {@code address} are what the window daemon needs to
     * find the same device on its side of the socket — an AudioDeviceInfo id
     * is transient and per-process, so it never crosses the wire.
     */
    static final class Output {
        int type;
        String address = "";
        String name = "";
        boolean builtin;
    }

    /**
     * The phone's own sound outputs: its speaker, plus whatever is plugged in
     * or paired and connected — the rows of the media-output flyout.
     *
     * {@link AudioManager#getDevices} rather than MediaRouter because it is
     * the list that carries a TYPE and an ADDRESS, which is what the window
     * daemon needs to select one; MediaRouter names only the active route.
     * Needs no permission. The earpiece, FM, telephony and remote-submix sinks
     * are filtered out: they are outputs in the framework's accounting, not
     * places a person sends music.
     *
     * The builtin speaker is always first and always present (every phone has
     * one), so the flyout never draws an empty phone section.
     */
    static List<Output> outputs(Context ctx) {
        List<Output> found = new java.util.ArrayList<>();
        Output speaker = new Output();
        speaker.type = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        speaker.builtin = true;
        speaker.name = ctx.getString(R.string.lx_output_unknown);
        found.add(speaker);
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return found;
            for (android.media.AudioDeviceInfo dev
                    : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!listable(dev.getType())) continue;
                Output out = new Output();
                out.type = dev.getType();
                // getAddress is public API only from 28; below that it is a
                // NoSuchMethodError, which the catch around this loop would
                // not stop. Without an address a Bluetooth pick will come back
                // ERR from the daemon and fall through to the platform picker —
                // the right worst case for a phone too old to route at all.
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    out.address = dev.getAddress() == null ? "" : dev.getAddress();
                }
                CharSequence product = dev.getProductName();
                out.name = product == null ? "" : product.toString().trim();
                if (out.name.isEmpty()) out.name = fallbackName(ctx, dev.getType());
                // The same physical device shows up once per profile it offers
                // (a headset is an A2DP sink and a BLE one); one row is enough.
                boolean dup = false;
                for (Output have : found) {
                    if (have.name.equalsIgnoreCase(out.name)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) found.add(out);
            }
        } catch (Exception e) {
            DexLog.warn("media", "cannot list the phone's sound outputs", e);
        }
        return found;
    }

    /** Sinks a person would choose. The rest of GET_DEVICES_OUTPUTS is noise. */
    private static boolean listable(int type) {
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_HEARING_AID:
            case android.media.AudioDeviceInfo.TYPE_BLE_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return true;
            default:
                // The speaker is seeded by the caller; everything else —
                // earpiece, telephony, FM, the remote submix scrcpy itself
                // captures through — is not a listing.
                return false;
        }
    }

    private static String fallbackName(Context ctx, int type) {
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return ctx.getString(R.string.lx_output_wired);
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                return ctx.getString(R.string.lx_output_usb);
            default:
                return ctx.getString(R.string.lx_output_bluetooth);
        }
    }

    /**
     * Open the phone's own output picker — speaker, headset, Bluetooth, and on
     * many phones the computers it can cast to.
     *
     * <b>The fallback behind our own flyout, and the only door to casting.</b>
     * Selecting an audio route is MODIFY_AUDIO_ROUTING, a signature permission
     * no third-party app can hold — which is why this app draws no picker of
     * its own THAT SWITCHES ITSELF. The flyout in the tray gets away with one
     * by not doing the switching: the window daemon does, at uid 2000, which
     * holds the permission (see WmClient#audioRoute). When the daemon cannot —
     * gone, old, refused — or for the targets only the platform knows how to
     * reach (cast receivers), the job here is to FIND the real picker.
     *
     * There is no single way to open it, because there is no single picker:
     * <ul>
     * <li>AOSP 10+ has a Settings panel ({@code ACTION_MEDIA_OUTPUT}). Pixels
     *     and most OEMs have it — Samsung does not, verified on One UI 8.</li>
     * <li>AOSP 12+ has SystemUI's output dialog, reached by broadcast. Samsung
     *     has this one. It needs the package of an app with a LIVE media
     *     session, which is why the playing app is passed and why it is skipped
     *     when nothing is playing.</li>
     * <li>Samsung additionally has its own activity.</li>
     * <li>Everything else: Bluetooth settings, which is where a headset or a
     *     speaker is chosen on a phone with none of the above.</li>
     * </ul>
     *
     * Tried in that order, and the first one this phone actually has wins. The
     * candidates are declared in the manifest's {@code <queries>} — without
     * that, package visibility makes every one of these look absent and the
     * chain falls through to the last entry on phones that have the first.
     *
     * @return what happened, so the caller can say something useful rather than
     * open a screen the user did not ask for and leave them to guess.
     */
    static boolean openOutputSwitcher(Context ctx, android.os.Bundle launchOptions) {
        MediaController playing = controller(ctx);
        String playingPkg = playing == null ? null : playing.getPackageName();

        // 1. The Settings panel — the closest thing to a standard.
        Intent panel = new Intent(ACTION_MEDIA_OUTPUT_PANEL);
        if (playingPkg != null) {
            panel.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, playingPkg);
            panel.putExtra(EXTRA_PACKAGE_NAME, playingPkg);
        }
        if (start(ctx, panel, launchOptions, "the media output panel")) return true;

        // 2. SystemUI's dialog. A broadcast, so it cannot report failure — it
        //    is only attempted when the receiver is really there AND something
        //    is playing for it to switch.
        if (playingPkg != null && hasReceiver(ctx, ACTION_SYSTEMUI_OUTPUT_DIALOG)) {
            try {
                ctx.sendBroadcast(new Intent(ACTION_SYSTEMUI_OUTPUT_DIALOG)
                        .putExtra(EXTRA_PACKAGE_NAME, playingPkg));
                DexLog.step("media", "asked SystemUI for the output dialog for "
                        + playingPkg);
                return true;
            } catch (Exception e) {
                DexLog.warn("media", "SystemUI refused the output dialog", e);
            }
        }

        // 3. Samsung's own activity.
        Intent oem = new Intent(ACTION_SAMSUNG_OUTPUT);
        if (playingPkg != null) oem.putExtra(EXTRA_PACKAGE_NAME, playingPkg);
        if (start(ctx, oem, launchOptions, "the phone's output switcher")) return true;

        // 4. Bluetooth settings — every phone has this, and it is where a
        //    headset or a speaker is actually chosen when nothing better exists.
        return start(ctx, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                launchOptions, "Bluetooth settings");
    }

    /** AOSP's Settings panel, API 29+. Spelled out: Settings.Panel is API 29 and this is 26. */
    private static final String ACTION_MEDIA_OUTPUT_PANEL =
            "android.settings.panel.action.MEDIA_OUTPUT";
    /** SystemUI's own dialog, AOSP 12+ — a broadcast, not an activity. */
    private static final String ACTION_SYSTEMUI_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG";
    /** One UI's equivalent activity. */
    private static final String ACTION_SAMSUNG_OUTPUT =
            "com.android.systemui.action.OPEN_MEDIA_OUTPUT";
    /** The extra all three name the playing app with. */
    private static final String EXTRA_PACKAGE_NAME = "package_name";

    private static boolean hasReceiver(Context ctx, String action) {
        try {
            return !ctx.getPackageManager()
                    .queryBroadcastReceivers(new Intent(action), 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Start one candidate, with our desktop launch bounds where the caller has
     * them. Answering on the ACTIVITY NOT FOUND exception rather than on
     * resolveActivity: the two disagree under package visibility, and a
     * candidate wrongly judged absent sends the chain past a picker the phone
     * actually has.
     */
    private static boolean start(Context ctx, Intent intent, android.os.Bundle options,
                                 String what) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (options != null) {
                ctx.startActivity(intent, options);
            } else {
                ctx.startActivity(intent);
            }
            DexLog.step("media", "opened " + what);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            return false;   // this phone does not have that one — try the next
        } catch (Exception e) {
            DexLog.warn("media", "cannot open " + what, e);
            return false;
        }
    }

    // ── volume ─────────────────────────────────────────────────────────────

    /**
     * The streams the panel offers, in the order it draws them.
     *
     * Media and ring, and not the other eight. Everything else the platform
     * exposes is either an alias of one of these on a modern phone (system,
     * notification) or belongs to something that is not happening on a desktop
     * (in-call, alarm) — and a panel of eight sliders is a panel nobody reads.
     */
    static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING};

    static int max(Context ctx, int stream) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return Math.max(1, am.getStreamMaxVolume(stream));
        } catch (Exception e) {
            return 15;
        }
    }

    static int volume(Context ctx, int stream) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am.getStreamVolume(stream);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Set a stream's volume.
     *
     * @return false when the platform refused, which on the ring stream means
     * exactly one thing: Do Not Disturb is on, and changing the ringer while it
     * is takes ACCESS_NOTIFICATION_POLICY — a grant that has to be given on a
     * screen on the phone, and one this desktop does not ask for. The caller
     * says so rather than leaving a slider that silently springs back.
     */
    static boolean setVolume(Context ctx, int stream, int value) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(stream, Math.max(0, Math.min(value, am.getStreamMaxVolume(stream))),
                    // No FLAG_SHOW_UI: that would raise the PHONE's own volume
                    // panel, on the phone's own display, where nobody is
                    // looking — and on some builds it steals focus from the
                    // desktop while it is up.
                    0);
            return true;
        } catch (SecurityException e) {
            DexLog.warn("media", "the phone refused a volume change on stream " + stream
                    + " — Do Not Disturb is the usual cause", e);
            return false;
        } catch (Exception e) {
            DexLog.warn("media", "cannot set the volume on stream " + stream, e);
            return false;
        }
    }
}

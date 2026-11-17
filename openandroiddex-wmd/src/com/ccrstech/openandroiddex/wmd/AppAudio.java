package com.ccrstech.openandroiddex.wmd;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Where the phone's sound plays, for the computer's app windows
 * (doc/apps-on-pc.md, "Where the sound plays"): the phone's own outputs, the
 * one media is pinned to, and the one it plays on now.
 *
 * <p>The same list the desktop's media-output flyout draws (the launcher's
 * DexMedia.outputs), told to the computer, which draws it in each window's
 * title bar. Pinning one is {@link Audio}'s, the lever that flyout pulls
 * through the window daemon; this process is the shell too, and holds the
 * same permission.
 *
 * <p>Names are the products' own ("Galaxy Buds2"), or empty: the computer
 * words the rest, in its own language. Each device is known by its type and
 * address — what the audio service finds it by; an AudioDeviceInfo id means
 * nothing outside this process.
 *
 * <p>And the windows' Mute (doc/apps-on-pc.md, "Mute"): the phone's media
 * silenced while the computer wants its sound nowhere. The computer stops
 * capturing the sound then, and without this the phone's own speaker would
 * take it back. Media is all the computer's capture carries: a ringtone, an
 * alarm or a notification sounds as it does while the sound plays there. Its
 * rules are {@link AppMute}'s; this class carries them out on the phone.
 */
final class AppAudio {

    interface Listener {
        void changed();
    }

    /**
     * There while a mute a server set may hold, naming that server: one killed
     * meanwhile, or one that could not undo it, has it undone by the next
     * ({@link AppMute#recover}). Beside it, "….lock", which the servers take
     * turns on while they read it and change the stream.
     */
    private static final String MARK = "/data/local/tmp/oadx-apps-muted";

    private final AudioManager manager;
    private AppMute mute;

    private AppAudio(AudioManager manager) {
        this.manager = manager;
    }

    /**
     * The phone's outputs, and word of them coming and going — a headset
     * plugged in, a pair of earbuds connecting — so the computer's menus list
     * what is there; and the Mute, for the server named {@code owner}, a mute
     * an earlier server left undone first. Null where the shell may not ask;
     * the windows then offer the computer's half only.
     */
    static AppAudio open(Listener listener, String owner) {
        try {
            AudioManager am = (AudioManager) PhoneContext.get().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            AppAudio a = new AppAudio(am);
            a.mute = new AppMute(a.new OnThePhone(), new File(MARK), owner, AppMute.HOLD_MS, listener::changed);
            a.mute.recover();
            HandlerThread thread = new HandlerThread("apps-audio");
            thread.start();
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    listener.changed();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    listener.changed();
                }
            }, new Handler(thread.getLooper()));
            return a;
        } catch (Throwable t) {
            AppServer.log("no word of the phone's sound outputs (" + t + ")");
            return null;
        }
    }

    /**
     * {@code {"t":"audio", "outputs":[{type, address, name, builtin}…],
     * "canPin":bool, "pinned":{type, address}|null, "playing":{type, address}|null,
     * "muted":bool}}. The speaker first, always: every phone has one.
     * {@code muted} is the computer's Mute ({@link #mute}), not whatever
     * the phone's own volume says.
     *
     * <p>{@code canPin} says whether the shell may pin media to an output at
     * all. MODIFY_AUDIO_ROUTING is not the shell's on every phone: a Galaxy
     * A13 on Android 12 and a Redmi 9A on 11 refuse it, and the desktop's
     * flyout falls back to the platform's picker there for the same reason.
     * Without it the computer offers "the phone", which plays where the
     * phone itself sends it.
     */
    JSONObject state() throws JSONException {
        JSONArray outputs = new JSONArray();
        outputs.put(output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "", true));
        List<String[]> seen = new ArrayList<>();
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (!listable(type)) continue;
            CharSequence product = d.getProductName();
            String name = product == null ? "" : product.toString().trim();
            String address = Build.VERSION.SDK_INT >= 28 && d.getAddress() != null ? d.getAddress() : "";
            // One headset is an A2DP sink and a BLE one under the same name:
            // Bluetooth collapses by name. Anything else keeps its address in
            // its identity, so two nameless USB cards are both listed.
            boolean dup = false;
            for (String[] s : seen) {
                if (!s[1].equalsIgnoreCase(name)) continue;
                boolean bothBluetooth = bluetooth(Integer.parseInt(s[0])) && bluetooth(type);
                if (bothBluetooth || s[2].equals(address)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            seen.add(new String[]{String.valueOf(type), name, address});
            outputs.put(output(type, address, name, false));
        }
        JSONObject j = new JSONObject().put("t", "audio").put("outputs", outputs).put("muted", muted());
        boolean canPin;
        try {
            List<Object[]> pinned = Audio.pinned();
            j.put("pinned", pinned.isEmpty() ? JSONObject.NULL : device(pinned.get(0)));
            canPin = true;
        } catch (Throwable t) {
            // Refused the permission, or a phone too old for pins at all.
            canPin = false;
        }
        j.put("canPin", canPin);
        try {
            List<Object[]> playing = Audio.playing();
            j.put("playing", playing.isEmpty() ? JSONObject.NULL : device(playing.get(0)));
        } catch (Throwable t) {
            // a phone that will not say: the computer reckons the speaker
        }
        return j;
    }

    // ── the windows' Mute (AppMute's rules) ──

    /** The computer's Mute is on. */
    boolean muted() {
        return mute.on();
    }

    /** On: null once the phone's media is muted, else why not. Only while {@code windowsOpen}. */
    String mute(BooleanSupplier windowsOpen) {
        return mute.mute(windowsOpen);
    }

    /** Off: null once the media plays again, or is held for the computer's capture ({@code afterCapture}). */
    String unmute(boolean afterCapture) {
        return mute.unmute(afterCapture);
    }

    /** The Mute ends without the computer asking: true if there was one. */
    boolean lift(String why) {
        return mute.lift(why);
    }

    /**
     * The phone's music stream, through the audio service as the volume keys
     * reach it ({@code ADJUST_MUTE}): the phone's own volume panel shows it,
     * and turning the volume up there undoes it. No flag to show the panel:
     * the phone may be in someone's pocket.
     */
    private final class OnThePhone implements AppMute.Phone {
        @Override
        public boolean muted() {
            return manager.isStreamMute(AudioManager.STREAM_MUSIC);
        }

        @Override
        public void mute() {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        }

        @Override
        public void unmute() {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        }

        @Override
        public Boolean capturing() {
            return submixActive();
        }

        @Override
        public void log(String line) {
            AppServer.log(line);
        }
    }

    /**
     * The computer's capture has the phone's output now: an input from the
     * remote submix is running — scrcpy's {@code output} source is one — and
     * while it does, the phone's media goes there, not to its speaker. Asked
     * of the audio policy itself (AudioSystem.isSourceActive, which takes no
     * permission); not of the recordings AudioManager lists, which leave this
     * source out as a system-only one (MediaRecorder.isSystemOnlyAudioSource:
     * a Galaxy S25 Ultra on Android 17 listed nothing while one ran). Else
     * where media plays now, if the phone will say: the submix itself while a
     * capture runs. Null if it will say neither.
     */
    static Boolean submixActive() {
        Boolean policy = policySaysSubmix();
        return policy != null ? policy : routeSaysSubmix();
    }

    /** AudioSystem.isSourceActive(REMOTE_SUBMIX); null where it is missing or refuses. */
    static Boolean policySaysSubmix() {
        try {
            Object on = Refl.callStatic(Refl.cls("android.media.AudioSystem"), "isSourceActive",
                    new Class<?>[]{int.class}, MediaRecorder.AudioSource.REMOTE_SUBMIX);
            return on instanceof Boolean ? (Boolean) on : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether media goes to the remote submix now; null where the phone will not say where it goes. */
    static Boolean routeSaysSubmix() {
        try {
            List<Object[]> playing = Audio.playing();
            if (playing.isEmpty()) return null;
            for (Object[] d : playing) {
                if (d[0] instanceof Integer && (Integer) d[0] == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) return true;
            }
            return false;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject output(int type, String address, String name, boolean builtin) throws JSONException {
        return new JSONObject().put("type", type).put("address", address).put("name", name).put("builtin", builtin);
    }

    private static JSONObject device(Object[] d) throws JSONException {
        return new JSONObject().put("type", d[0]).put("address", d[1]);
    }

    /** Sinks a person would send music to; the rest of the list is the framework's own. */
    private static boolean listable(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return true;
            default:
                // The speaker is put first by hand; the earpiece, telephony, FM
                // and the remote submix the computer's sound is captured through
                // are not places anyone plays music.
                return false;
        }
    }

    /** The types one physical device answers under several of. */
    private static boolean bluetooth(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            default:
                return false;
        }
    }
}

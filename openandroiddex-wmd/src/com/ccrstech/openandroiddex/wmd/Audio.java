package com.ccrstech.openandroiddex.wmd;

import android.media.AudioAttributes;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone's own media route, switched with this process's authority.
 *
 * Selecting where MEDIA comes out — speaker or a paired headset — is
 * MODIFY_AUDIO_ROUTING, a signature permission the launcher can never hold and
 * shell (uid 2000) does. The launcher draws the list; this side does the one
 * thing on it that needs privilege: pinning the media product strategy to a
 * device, which is the same lever the platform's own output switcher pulls
 * underneath, and which re-routes audio that is ALREADY playing.
 *
 * The device is addressed by AudioDeviceInfo type + address, never by id: an
 * id is handed out per process and means nothing across this socket.
 *
 * A pin set here deliberately outlives the session. It is the user's answer to
 * "where does the phone play", and the framework itself keeps routing choices
 * across disconnects — a pinned device that goes away is skipped, not silent.
 *
 * Everything here is reflection against @SystemApi surface that has kept its
 * shape since Android 12 (11 spells the calls in the singular, which is
 * probed for); a build where any of it is missing answers ERR on the wire and
 * the launcher falls back to opening the platform's own picker.
 */
final class Audio {

    private static Object audioService;    // IAudioService

    private Audio() {
    }

    private static Object svc() {
        if (audioService == null) {
            IBinder b = (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"),
                    "getService", new Class<?>[]{String.class}, "audio");
            if (b == null) throw new Refl.WmError("no audio service");
            audioService = Refl.callStatic(Refl.cls("android.media.IAudioService$Stub"),
                    "asInterface", new Class<?>[]{IBinder.class}, b);
        }
        return audioService;
    }

    /**
     * The id of the product strategy that carries USAGE_MEDIA — the framework's
     * name for "music and video", and the unit preferred-device routing works
     * in. Asked of the strategy list rather than hardcoded: the ids are
     * assigned by each build's audio policy configuration and differ by OEM.
     */
    private static int mediaStrategyId() {
        List<?> strategies = (List<?>) Refl.callStatic(
                Refl.cls("android.media.audiopolicy.AudioProductStrategy"),
                "getAudioProductStrategies", new Class<?>[]{});
        if (strategies == null || strategies.isEmpty()) {
            throw new Refl.WmError("no audio product strategies");
        }
        AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        for (Object s : strategies) {
            Object ok = Refl.callSig(s, "supportsAudioAttributes",
                    new Class<?>[]{AudioAttributes.class}, media);
            if (Boolean.TRUE.equals(ok)) {
                return (Integer) Refl.call(s, "getId");
            }
        }
        throw new Refl.WmError("no strategy supports media");
    }

    /** An AudioDeviceAttributes for one of the phone's outputs. */
    private static Object deviceAttributes(int type, String address) {
        Class<?> cls = Refl.cls("android.media.AudioDeviceAttributes");
        int role;
        try {
            role = cls.getField("ROLE_OUTPUT").getInt(null);
        } catch (Throwable t) {
            role = 2;   // AudioPort.ROLE_SINK, unchanged since the class exists
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    int.class, int.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(role, type, address == null ? "" : address);
        } catch (Throwable t) {
            throw new Refl.WmError("cannot build AudioDeviceAttributes: " + t);
        }
    }

    /** Pin the phone's media to one output. Takes effect mid-song. */
    static void route(int type, String address) {
        int strategy = mediaStrategyId();
        Object device = deviceAttributes(type, address);
        Object svc = svc();
        Object status;
        // Android 12+ takes a list ("Devices"); 11 took the one ("Device").
        if (Refl.hasMethod(svc.getClass(), "setPreferredDevicesForStrategy",
                int.class, List.class)) {
            List<Object> one = new ArrayList<>();
            one.add(device);
            status = Refl.callSig(svc, "setPreferredDevicesForStrategy",
                    new Class<?>[]{int.class, List.class}, strategy, one);
        } else {
            status = Refl.callSig(svc, "setPreferredDeviceForStrategy",
                    new Class<?>[]{int.class, device.getClass()}, strategy, device);
        }
        // The AIDL answers an AudioSystem status, not an exception — 0 is
        // SUCCESS, anything else is the policy refusing (a device it cannot
        // see, usually). Silence is what that refusal would otherwise be.
        checkStatus(status, "setPreferredDevicesForStrategy");
        System.out.println("audio: media pinned to type=" + type
                + (address == null || address.isEmpty() ? "" : " address=" + address));
    }

    /** Give the media route back to the framework's own policy. */
    static void clear() {
        int strategy = mediaStrategyId();
        Object svc = svc();
        Object status;
        if (Refl.hasMethod(svc.getClass(), "removePreferredDevicesForStrategy", int.class)) {
            status = Refl.callSig(svc, "removePreferredDevicesForStrategy",
                    new Class<?>[]{int.class}, strategy);
        } else {
            status = Refl.callSig(svc, "removePreferredDeviceForStrategy",
                    new Class<?>[]{int.class}, strategy);
        }
        checkStatus(status, "removePreferredDevicesForStrategy");
        System.out.println("audio: media route unpinned");
    }

    private static void checkStatus(Object status, String call) {
        if (status instanceof Integer && (Integer) status != 0) {
            throw new Refl.WmError(call + " answered " + status);
        }
    }
}

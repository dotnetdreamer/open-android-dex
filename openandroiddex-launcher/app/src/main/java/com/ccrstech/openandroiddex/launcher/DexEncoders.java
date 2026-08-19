package com.ccrstech.openandroiddex.launcher;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * The video encoders this phone actually has, for Settings → Scrcpy Config.
 *
 * scrcpy's {@code --video-encoder} takes a MediaCodec name, and which names
 * exist is a per-device fact — an Exynos phone offers
 * {@code c2.exynos.h264.encoder}, a Snapdragon one
 * {@code OMX.qcom.video.encoder.avc}, and picking a name the device does not
 * have makes scrcpy exit instead of starting the desktop. Asking the platform
 * is the only way to offer a list that is right on every device, and it costs
 * one call.
 *
 * The names go to the PC over the launcher's request queue, which only accepts
 * {@code [A-Za-z0-9._-]}; anything odder is dropped here rather than sent and
 * silently discarded on the other side.
 */
final class DexEncoders {

    private DexEncoders() {
    }

    /** Codec ids as the PC and scrcpy know them, with their MediaCodec mime. */
    static final String CODEC_AUTO = "auto";
    static final String CODEC_H264 = "h264";
    static final String CODEC_H265 = "h265";
    static final String CODEC_AV1 = "av1";

    static String mimeOf(String codec) {
        switch (codec) {
            case CODEC_H265:
                return "video/hevc";
            case CODEC_AV1:
                return "video/av01";
            case CODEC_H264:
                return "video/avc";
            default:
                return null;             // "auto": no encoder list to offer
        }
    }

    /** One encoder the device exposes for the selected codec. */
    static final class Encoder {
        final String name;
        /** "Hardware" / "Software" / "" when the platform will not say. */
        final boolean hardware;
        final boolean knownAcceleration;

        Encoder(String name, boolean hardware, boolean knownAcceleration) {
            this.name = name;
            this.hardware = hardware;
            this.knownAcceleration = knownAcceleration;
        }
    }

    /** Encoders for {@code codec}, hardware first; empty for "auto". */
    static List<Encoder> forCodec(String codec) {
        List<Encoder> out = new ArrayList<>();
        String mime = mimeOf(codec);
        if (mime == null) return out;
        MediaCodecInfo[] infos;
        try {
            infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } catch (Exception e) {
            return out;
        }
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) continue;
            String name = info.getName();
            if (name == null || !safe(name)) continue;
            boolean supports = false;
            for (String type : info.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) {
                    supports = true;
                    break;
                }
            }
            if (!supports) continue;
            boolean known = Build.VERSION.SDK_INT >= 29;
            boolean hardware = known && info.isHardwareAccelerated();
            out.add(new Encoder(name, hardware, known));
        }
        // hardware first: it is what anyone streaming a desktop wants, and it
        // is the half of the list that differs between phones
        out.sort((a, b) -> a.hardware == b.hardware
                ? a.name.compareToIgnoreCase(b.name)
                : (a.hardware ? -1 : 1));
        return out;
    }

    private static boolean safe(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return !name.isEmpty();
    }
}

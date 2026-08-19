package com.ccrstech.openandroiddex.launcher;

import android.content.Context;

import org.json.JSONObject;

/**
 * What a viewer is allowed to say.
 *
 * Every one of these arrives on the {@code ctl} data channel of a peer
 * connection. Keeping the vocabulary in one place rather than inside
 * {@link WebRtcPeer} is what makes "what can the far end actually do" a
 * question with one file for an answer.
 *
 * <p>Nothing here trusts the caller: the control switch is re-read from
 * settings on every message rather than captured when the viewer connected, so
 * turning control off in the window takes effect on the next click and not on
 * the next session.
 */
final class WebControl {

    private WebControl() {
    }

    interface Sender {
        void send(String json);
    }

    /**
     * Apply one message.
     *
     * @param authenticated false for a peer that has not yet proven it knows
     *                      the access code. Such a peer may ping and nothing
     *                      else — in particular it may not move the pointer or
     *                      touch the clipboard.
     */
    static void handle(Context ctx, WebServer.Host host, String text, Sender reply,
                       boolean authenticated) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("t");
            if ("ping".equals(type)) {
                reply.send("{\"t\":\"pong\",\"id\":" + msg.optLong("id") + "}");
                return;
            }
            if (!authenticated) return;
            if ("clip".equals(type)) {
                if (Web.control(ctx)) DexClipboard.set(ctx, msg.optString("v", ""));
                return;
            }
            if (!Web.control(ctx)) return;
            WebInput input = host.input();
            if (input == null) return;
            float x = (float) msg.optDouble("x", 0);
            float y = (float) msg.optDouble("y", 0);
            switch (type) {
                case "tap":
                    input.tap(x, y);
                    break;
                case "long":
                    input.longPress(x, y);
                    break;
                case "down":
                    input.down(x, y);
                    break;
                case "move":
                    input.move(x, y);
                    break;
                case "up":
                    input.up(x, y);
                    break;
                case "scroll":
                    input.scroll(x, y,
                            (float) msg.optDouble("dx", 0), (float) msg.optDouble("dy", 0));
                    break;
                case "text":
                    input.text(msg.optString("v", ""));
                    break;
                case "vk":
                    input.key(msg.optString("v", ""));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            DexLog.warn("web", "bad message from a viewer", e);
        }
    }

    /**
     * What the page needs once it is in: how big the screen is, and which of
     * the two halves of the feature are switched on.
     *
     * The geometry is not for the decoder — the video track carries its own
     * size — it is so the page can label the session. Clicks are sent as
     * fractions of the picture, so nothing here is on the input path.
     */
    static String formatJson(Context ctx) {
        android.graphics.Point size = Web.captureSize(ctx);
        // True when the desktop shell is on a SECONDARY display (a PC session):
        // the capture is always the DEFAULT display, so in that case the viewer
        // is showing the phone, not the desktop — and the page says so.
        boolean desktopElsewhere =
                CaptionService.desktopDisplay() > android.view.Display.DEFAULT_DISPLAY;
        return "{\"t\":\"format\",\"w\":" + size.x + ",\"h\":" + size.y
                + ",\"control\":" + Web.control(ctx)
                + ",\"files\":" + Web.files(ctx)
                + ",\"desktop\":" + desktopElsewhere + "}";
    }
}

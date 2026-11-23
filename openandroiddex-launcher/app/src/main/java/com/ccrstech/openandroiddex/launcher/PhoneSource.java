package com.ccrstech.openandroiddex.launcher;

/**
 * Where Remote desktop's picture comes from, for {@link PhoneStream}. Two
 * places, because Android allows two and each one fails where the other does
 * not:
 *
 * <ul>
 *   <li>{@link PhoneScreen}, screen sharing: the screen as it is drawn, 60
 *       pictures a second, on the hardware encoder. Android asks the user
 *       for it on the phone every time, and from Android 15 QPR1 ends it the
 *       moment the phone locks: a projection does not outlive the lock
 *       screen, and none can start under it.</li>
 *   <li>{@link PhoneShots}, the slow view: the control service's own
 *       screenshots, about three a second (Android allows one every 333 ms).
 *       Nobody has to be at the phone, and the lock screen is in them, so a
 *       computer can wake a locked phone, see its lock screen and unlock it.
 *       Windows an app marks secure are black in them, as in screen
 *       sharing.</li>
 * </ul>
 *
 * Both make the same H.264 for the same wire, so the computer's window does
 * not change between them; the video message says which it is.
 */
interface PhoneSource {

    /** The session's say over when a picture may go. */
    interface Gate {
        /** May a picture go to the encoder now? The source's thread; must not block. */
        boolean mayDraw();

        /** One did. */
        void drew();
    }

    /** Where the encoded units go, on the source's drain thread. */
    interface Sink extends LinuxStreamEncoder.Output {
        /** A new encoder, before its first unit. */
        void video(Encoding e);
    }

    /** One encoder's picture: what {@link PhoneStream} tells the computer. */
    final class Encoding {
        /** "screen" (screen sharing) or "shots" (the slow view). */
        String source;
        String codecName;
        boolean hardware;
        /** The coded picture. */
        int encW, encH;
        /** The screen's part of it, from the top left. */
        int w, h;
        /** The phone's screen, in its own pixels, as it is turned now. */
        int screenW, screenH;
        int rotation;
        int bitrate;
        int fps;
        int configAttempt;
    }

    /** Start making pictures for a session; the first unit follows {@link Sink#video}. */
    void start(Sink sink, Gate gate, PhoneRemote.Quality q, int bitrate, boolean keyWhenAsked);

    /** No more pictures for {@code of}. */
    void stop(Sink of);

    /** Another quality for the session that has the source. */
    void setQuality(Sink of, PhoneRemote.Quality q, int bitrate);

    /** The link's bitrate, from the next frames on. Any thread. */
    void setBitrate(int bps);

    /** A key frame next, with the last picture again if nothing new comes. Any thread. */
    void requestKeyFrame();

    /** The session has room again: a picture that waited may go. Any thread. */
    void kick();

    Encoding encoding();
}

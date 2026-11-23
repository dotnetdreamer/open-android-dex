package com.ccrstech.openandroiddex.launcher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Remote desktop's sound: what apps play on the phone, for the computer
 * that has the phone open, and the phone's own speaker kept quiet when the
 * person at the computer says so.
 *
 * <p><b>Where it plays</b> is the computer's choice, two switches on the
 * wire ({@code {"t":"audio","on":..,"phone":..}}, {@link PhoneStream}):
 * {@code on}, sent to the computer; {@code phone}, still played on the phone.
 * Sound on the phone alone (the default) and Sound off are nothing read and
 * nothing sent: the whole link is the picture's.
 *
 * <p><b>What it hears</b> is Android's playback capture (Android 10 on):
 * what apps play as media, games and unknown, through the screen sharing
 * this phone already gives the computer, so it comes with full speed and not
 * with the slow view. Calls, ringtones, alarms and notification sounds stay
 * on the phone, and so does any app that does not let itself be captured.
 * Android asks for the microphone permission for it (RECORD_AUDIO): the
 * microphone itself is never opened. Read through {@link LinuxStreamAudio}
 * as Linux's sound is read, so it is shaped, held back while silent and sent
 * as Opus the same way.
 *
 * <p><b>The phone kept quiet</b> is the media volume muted, which the
 * capture does not hear: Android captures what apps play before the volume
 * (a policy mix's output is at unity gain), measured on a Galaxy S25 Ultra,
 * Android 17. Muted only by this app and only while a session asks, then
 * unmuted, unless someone at the phone turned it back up first. A mark in
 * the app's files outlives a process that ended under a session, so the next
 * start puts the sound back ({@link #undoLeftOver}).
 */
final class PhoneSound {

    private static final int RATE = 48000;
    /** The capture's buffer: what can wait while the link is busy, and so the most the sound lags by. */
    private static final int BUFFER_BYTES = RATE * 4 / 10;
    private static final String CHANNEL_ASKS = "phone-remote-asks";
    private static final int NOTIF_SOUND = 0x61;

    private PhoneSound() {
    }

    /** Android 10 and newer: playback capture is there. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /** The permission Android asks for capture (the microphone's, which is not used). */
    static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** The phone's own playback, through {@code projection}, as a tap for {@link LinuxStreamAudio}. */
    static LinuxStreamAudio.Tap capture(MediaProjection projection) {
        return new Capture(projection);
    }

    private static final class Capture implements LinuxStreamAudio.Tap {
        private final MediaProjection projection;
        private volatile AudioRecord record;
        private volatile boolean closed;
        /** The thread that opened it and reads it: the only one that may release it. */
        private volatile Thread reader;

        Capture(MediaProjection projection) {
            this.projection = projection;
        }

        @Override
        public InputStream open() throws IOException {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("Android 10 or newer is needed");
            if (closed) throw new IOException("stopped");
            reader = Thread.currentThread();
            AudioRecord r;
            try {
                AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
                AudioFormat f = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build();
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                r = new AudioRecord.Builder()
                        .setAudioFormat(f)
                        .setBufferSizeInBytes(Math.max(min, BUFFER_BYTES))
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build();
            } catch (SecurityException | UnsupportedOperationException | IllegalArgumentException | IllegalStateException e) {
                throw new IOException("playback capture refused: " + e.getMessage(), e);
            }
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                throw new IOException("playback capture did not start");
            }
            try {
                r.startRecording();
            } catch (IllegalStateException e) {
                r.release();
                throw new IOException("playback capture did not start: " + e.getMessage(), e);
            }
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                throw new IOException("playback capture is not recording");
            }
            record = r;
            if (closed) {
                close();
                throw new IOException("stopped");
            }
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) <= 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    AudioRecord a = record;
                    if (a == null || closed) return -1;
                    int n = a.read(b, off, len);
                    if (n < 0) {
                        if (closed) return -1;
                        throw new IOException("playback capture: " + n);
                    }
                    // Stopped under the read: what it had, then the end.
                    return n == 0 && closed ? -1 : n;
                }
            };
        }

        /**
         * Stop wakes a read that waits; release follows on the thread that
         * reads, once it is out of the read ({@link #open}'s stream ends, the
         * pump returns, and LinuxStreamAudio closes again from there).
         */
        @Override
        public void close() {
            closed = true;
            AudioRecord r = record;
            if (r == null) return;
            try {
                r.stop();
            } catch (IllegalStateException ignored) {
                // stopped already
            }
            if (Thread.currentThread() == reader) {
                record = null;
                r.release();
            }
        }

        @Override
        public boolean retries() {
            return false;
        }

        @Override
        public String name() {
            return "the phone's playback";
        }
    }

    // ── the phone's own speaker ──

    /** The sessions that want the phone quiet now. */
    private static final Set<Object> quieting = new HashSet<>();
    /** The media volume was muted by this app, and is still its to unmute. */
    private static boolean mutedHere;

    /**
     * {@code who} wants the phone's own sound quiet, or no longer does. The
     * first one in mutes the media volume, the last one out unmutes it. Any
     * thread.
     */
    static synchronized void quiet(Context ctx, Object who, boolean on) {
        boolean was = !quieting.isEmpty();
        if (on) {
            quieting.add(who);
        } else {
            quieting.remove(who);
        }
        boolean now = !quieting.isEmpty();
        if (now == was) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        try {
            if (now) {
                // Quiet already (muted or at nothing by someone at the phone): theirs, left as it is.
                if (am.isStreamMute(AudioManager.STREAM_MUSIC) || am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    mutedHere = false;
                    return;
                }
                mark(ctx, true);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                mutedHere = am.isStreamMute(AudioManager.STREAM_MUSIC);
                if (!mutedHere) mark(ctx, false);
                DexLog.step("phone", mutedHere ? "the phone's own sound is quiet for the computer" : "the phone's sound could not be made quiet");
            } else if (mutedHere) {
                mutedHere = false;
                // Turned back up at the phone meanwhile: theirs, left as it is.
                if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                }
                mark(ctx, false);
                DexLog.step("phone", "the phone's own sound is back");
            }
        } catch (RuntimeException e) {
            DexLog.warn("phone", "the phone's sound could not be changed", e);
        }
    }

    /** The phone is quiet for a computer now. */
    static synchronized boolean quietNow() {
        return mutedHere;
    }

    /**
     * At the stream's start: a mute this app left when its process ended
     * under a session (killed, or the phone ran out of memory) is undone,
     * once, whatever has happened since but someone at the phone unmuting.
     */
    static synchronized void undoLeftOver(Context ctx) {
        if (!quieting.isEmpty() || !markFile(ctx).exists()) return;
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am != null && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                DexLog.step("phone", "the phone's own sound is back (left quiet by a session that ended with the app)");
            }
        } catch (RuntimeException e) {
            DexLog.warn("phone", "the phone's sound could not be put back", e);
        }
        mark(ctx, false);
    }

    private static File markFile(Context ctx) {
        return new File(PhoneRemote.dir(ctx), "sound-muted");
    }

    private static void mark(Context ctx, boolean on) {
        File f = markFile(ctx);
        if (!on) {
            f.delete();
            return;
        }
        try {
            PhoneRemote.dir(ctx).mkdirs();
            f.createNewFile();
        } catch (IOException e) {
            DexLog.warn("phone", "cannot record the phone's sound as quiet", e);
        }
    }

    // ── asking for the permission ──

    /**
     * A computer asked for the phone's sound and the permission is not there:
     * the phone asks, as it asks for screen sharing. With control on, the
     * question opens on the phone's screen, where the person at the computer
     * sees it too; a notification asks whoever holds the phone either way.
     */
    static void ask(Context ctx) {
        Context app = ctx.getApplicationContext();
        PhoneControlService control = PhoneControlService.live();
        Intent i = new Intent(app, PhoneSoundActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (control != null) {
            try {
                control.startActivity(i);
            } catch (Exception e) {
                DexLog.warn("phone", "cannot ask for sound on the screen", e);
            }
        }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ASKS) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ASKS, app.getString(R.string.pr_channel_asks),
                    NotificationManager.IMPORTANCE_HIGH);
            c.setShowBadge(true);
            nm.createNotificationChannel(c);
        }
        PendingIntent tap = PendingIntent.getActivity(app, 4, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL_ASKS)
                : new Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH);
        Notification n = b.setSmallIcon(R.drawable.hm_ic_remote)
                .setContentTitle(app.getString(R.string.pr_sound_notif))
                .setContentText(app.getString(R.string.pr_sound_notif_text))
                .setStyle(new Notification.BigTextStyle().bigText(app.getString(R.string.pr_sound_notif_text)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIF_SOUND, n);
        } catch (Exception ignored) {
            // no notification permission: the question on the screen and the Settings page
        }
    }

    static void cancelAsk(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_SOUND);
    }
}

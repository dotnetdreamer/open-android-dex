package com.ccrstech.openandroiddex.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Launcher → PC command channel. The launcher has no privilege to close
 * other apps' tasks or flip system toggles; the PC side does (adb). Requests
 * are queued here and the PC drains them on its poll loop.
 *
 * Two protocols share the queue:
 * - `content query --uri content://…/requests/v2`: rows carry a monotonic
 *   id and STAY queued; the PC acks with `content delete --where "id<=N"`
 *   after executing. A request survives an adb-shell death between query
 *   and readout (which used to eat quick-settings taps silently).
 * - legacy `content query --uri content://…/requests` (older PC builds):
 *   returns and clears the pending queue in one shot.
 */
public class RequestProvider extends ContentProvider {

    /** {id, cmd, arg} — id is monotonic so the PC can dedupe/ack. */
    private static final ArrayDeque<String[]> QUEUE = new ArrayDeque<>();
    /** Seeded from wall time: still increases across launcher restarts. */
    private static long nextId = System.currentTimeMillis();

    /** The fast channel. Created on first use; one socket for the process. */
    private static WmClient wm;

    public static void enqueue(String cmd, String arg) {
        final long id;
        synchronized (QUEUE) {
            id = ++nextId;
            QUEUE.add(new String[]{String.valueOf(id), cmd, arg});
            // The PC logs what it executes; this is the other end of that
            // pair, so a request that is raised but never drained (a dead
            // pump, a wedged adb shell) shows up as a gap rather than as
            // "the button does nothing".
            DexLog.step("request", cmd + " " + arg + " queued (" + QUEUE.size() + " pending)");
        }
        offerToDaemon(id, cmd, arg);
    }

    /**
     * Also hand the request to the window daemon, which is how the PC will actually see
     * it while that daemon is up.
     *
     * Reading this queue costs the PC an `content query`, and that is a whole app_process
     * VM per read — 537 ms measured on a Redmi Note 7, against 73 ms for a bare adb shell
     * round trip. Polled often enough to feel responsive it kept a JVM starting roughly
     * every 0.7 s for the life of the session, which is most of what made an idle desktop
     * warm, and it still put most of a second in front of every press. The daemon answers
     * the same question over a socket that is already open.
     *
     * The row stays in QUEUE regardless. This is an ADDITION, not a replacement: the
     * daemon is best-effort (it is absent on phones where it could not start, and it dies
     * with the session), and a press must never depend on it. The PC reads both channels
     * and skips anything at or below its watermark, which is why both copies carry the
     * same id.
     *
     * Posted rather than called: enqueue runs on the UI thread, and WmClient is blocking
     * socket I/O.
     */
    private static synchronized void offerToDaemon(long id, String cmd, String arg) {
        if (wm == null) wm = new WmClient();
        final WmClient client = wm;
        client.post(() -> client.reqPut(id, cmd, arg));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        boolean v2 = uri.getPath() != null && uri.getPath().contains("v2");
        synchronized (QUEUE) {
            if (v2) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"id", "cmd", "arg"});
                for (String[] row : QUEUE) {
                    cursor.addRow(row);
                }
                return cursor;
            }
            MatrixCursor cursor = new MatrixCursor(new String[]{"cmd", "arg"});
            while (!QUEUE.isEmpty()) {
                String[] row = QUEUE.poll();
                cursor.addRow(new String[]{row[1], row[2]});
            }
            return cursor;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.androiddex.request";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    /** Ack from the PC: `--where "id<=N"` drops the executed rows. */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (selection == null) return 0;
        long upTo;
        try {
            upTo = Long.parseLong(selection.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
        int removed = 0;
        synchronized (QUEUE) {
            Iterator<String[]> it = QUEUE.iterator();
            while (it.hasNext()) {
                String[] row = it.next();
                try {
                    if (Long.parseLong(row[0]) <= upTo) {
                        it.remove();
                        removed++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return removed;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

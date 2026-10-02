package com.ccrstech.openandroiddex.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The copy engine behind the File transfer window. Bytes only — no View, no
 * Activity, no broadcast this class does not own.
 *
 * <p>Everything the user sees comes back through {@link Listener}, and every
 * callback is posted to the main thread, so the window can write straight into
 * its own TextViews without a second hop. The engine holds an application
 * Context and nothing else of the activity's, which is what lets a copy keep
 * running while the window is being rebuilt for a configuration change.
 *
 * <p><b>One worker thread, one queue.</b> Two copies started a second apart do
 * not both finish sooner: they interleave writes onto one flash device, the
 * phone's storage is the bottleneck in both directions, and the second copy's
 * only visible effect is that the first one's progress row stalls. So a job is
 * appended to an {@link ArrayDeque} and drained by a single daemon thread.
 * {@link java.util.concurrent.Executors#newSingleThreadExecutor} — the shape
 * {@link WebRtcFiles} uses at its own I/O boundary — gives the same
 * serialisation, and was rejected here only because this class also has to
 * answer {@link #busy()} and to empty the pending work on {@link #cancel()};
 * both need the queue itself, not a handle to it.
 *
 * <p><b>Folders are flattened before a byte moves.</b> A folder the user picked
 * is expanded first — over the wire with {@code PLAN} for a copy off the
 * computer, with {@link File#listFiles()} for a copy off the phone — so the
 * progress row can say "14 of 231" rather than "1 of 1" for twenty minutes.
 * The expansion is a handful of control round trips and no data, so it costs a
 * moment of nothing-visible at the head of a big folder copy, which is a price
 * worth paying for an honest denominator.
 *
 * <p><b>The {@code .part} invariant holds in both directions.</b> A file being
 * written to the phone is written under its final name plus {@code .part} and
 * renamed only once the last byte has landed, so a copy killed by a cancel, a
 * pulled cable or a full disk leaves an obviously unfinished file rather than
 * a plausible truncated one — the same rule {@link WebFiles#receive} and
 * {@link WebRtcFiles} both keep, and {@code WebFiles.list} now hides
 * {@code .part} names so a pane refreshed mid-copy does not offer one back.
 * The host end of a {@code PUT} keeps the identical rule on its own side.
 *
 * <p><b>There is no MediaStore fallback.</b> Without the all-files grant this
 * engine fails the batch and says so. MediaStore can only ever write into
 * Downloads, and the whole point of this window is that a copy lands in the
 * folder the user navigated to — writing somewhere else and reporting success
 * would be a lie about where the file is. {@link WebRtcFiles} declined the same
 * fallback for the same reason.
 *
 * <p><b>Symlinks are never followed</b>, on either side. A link under
 * {@code /sdcard} pointing back up its own tree turns a folder copy into an
 * unbounded one, and a link copied as its target silently duplicates data the
 * user believed was shared. They are counted and the count is reported, so the
 * window can say a copy was not quite everything.
 */
final class FileTransfer {

    /**
     * Where a folder walk stops. Deliberately the same numbers the host's
     * {@code PLAN} caps at, so a folder that copies one way is a folder that
     * copies the other way, and neither side is the surprise.
     */
    private static final int WALK_FILES_CAP = 20_000;
    private static final int WALK_DIRS_CAP = 2_000;

    /**
     * Folders first, then case-insensitive name.
     *
     * Byte-identical to the order {@code WebFiles.list} sorts a listing into,
     * so the progress row walks a folder in the order the pane displayed it.
     * Nothing about the copy depends on the order; the user's sense that the
     * machine is working through what they can see does.
     */
    private static final Comparator<File> BY_KIND_THEN_NAME = (a, b) -> {
        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
    };

    /** One thing the user picked, on either side. */
    static final class Item {
        final String path;      // host path, or absolute phone path
        final String name;
        final boolean dir;
        final long size;

        Item(String path, String name, boolean dir, long size) {
            this.path = path;
            this.name = name;
            this.dir = dir;
            this.size = size;
        }
    }

    /** Every callback is delivered on the MAIN thread. */
    interface Listener {
        /**
         * @param name  the file being copied right now, leaf name only
         * @param index 1-based position in the flattened batch
         * @param total how many files the whole batch turned out to be
         * @param pct   0..100, or -1 when the sender would not say how big it is
         */
        void onProgress(String name, int index, int total, int pct);

        void onDone(int copied, int failed, int skippedLinks, boolean cancelled);
    }

    /**
     * One file, after every picked folder has been flattened into its contents.
     *
     * Both ends are always filled in; the direction of the job says which is
     * the source and which is the destination. Keeping one type rather than two
     * near-identical ones is what lets {@link #row} and the counting loop be
     * written once.
     */
    private static final class Unit {
        /** The path on the computer: source of a fetch, destination of a send. */
        final String hostPath;
        /**
         * The file on the phone: destination of a fetch, source of a send.
         *
         * Null exactly when {@link #destDir} is set, and read nowhere but
         * {@link FileTransfer#fetch} and {@link FileTransfer#send}, neither of
         * which can be reached with the other field's kind of Unit.
         */
        final File phoneFile;
        /**
         * Where a plain file fetched off the computer lands, with its leaf
         * name still undecided — see {@link FileTransfer#fetch}. Null for
         * everything else.
         */
        final File destDir;
        /** Leaf name, for the progress row. */
        final String name;

        Unit(String hostPath, File phoneFile, String name) {
            this(hostPath, phoneFile, null, name);
        }

        /**
         * A fetch whose destination name is chosen at write time.
         *
         * Keep-both used to be resolved for the whole batch before a byte
         * moved, and nothing on disk reserved the answer: two picks an instant
         * apart could both be handed "report (2).pdf", and the second
         * {@code .part} then renamed itself over the first — {@code rename(2)}
         * replaces a regular file without a word, so both fetches returned
         * true and the window said "2 files copied" for one file that arrived.
         * Same collision via {@code WebFiles.safeName}'s trim and its 180-char
         * truncation, and via /sdcard being case-insensitive.
         */
        static Unit into(String hostPath, File destDir, String name) {
            return new Unit(hostPath, null, destDir, name);
        }

        private Unit(String hostPath, File phoneFile, File destDir, String name) {
            this.hostPath = hostPath;
            this.phoneFile = phoneFile;
            this.destDir = destDir;
            this.name = name;
        }
    }

    /** What a finished batch has to report. */
    private static final class Tally {
        int copied;
        int failed;
        int skipped;
    }

    /** One queued copy. Exactly one of the two destinations is set. */
    private static final class Job {
        final List<Item> items;
        final File phoneDest;
        final String hostDest;

        Job(List<Item> items, File phoneDest, String hostDest) {
            this.items = items;
            this.phoneDest = phoneDest;
            this.hostDest = hostDest;
        }
    }

    /** One step of a phone-side folder walk: a directory and its twin on the host. */
    private static final class Step {
        final File local;
        final String hostDir;

        Step(File local, String hostDir) {
            this.local = local;
            this.hostDir = hostDir;
        }
    }

    private final Context ctx;
    private final HostFiles host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    /** Guarded by {@link #queue}: true from the moment a job is taken off it. */
    private boolean running;
    /** Guarded by {@link #queue}: set once by {@link #shutdown()}, never cleared. */
    private boolean stopped;

    /**
     * Read by every loop and by both pump callbacks, written by any thread.
     *
     * A cancel has to be observable inside a 64 KB read that is already in
     * flight, which is why this is a flag polled from the transfer rather than
     * a {@link Thread#interrupt()}: the interrupt would land somewhere
     * unpredictable in the socket code and leave the {@code .part} cleanup to
     * chance, and the cleanup is the whole invariant.
     */
    private volatile boolean cancelled;

    FileTransfer(Context ctx, HostFiles host, Listener listener) {
        // The application context, not the activity: a copy outlives a window
        // rebuild, and holding the Activity here would leak it for the length
        // of the largest file the user ever picked.
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.listener = listener;
        Thread worker = new Thread(this::loop, "file-transfer");
        worker.setDaemon(true);
        worker.start();
    }

    // ── what the window calls ───────────────────────────────────────────

    boolean busy() {
        synchronized (queue) {
            return running || !queue.isEmpty();
        }
    }

    /** Host -> phone. destDir must exist. */
    void toPhone(List<Item> items, File destDir) {
        enqueue(new Job(new ArrayList<>(items), destDir, null));
    }

    /** Phone -> host. destPath is a host directory path. */
    void toHost(List<Item> items, String destPath) {
        enqueue(new Job(new ArrayList<>(items), null, destPath));
    }

    /**
     * Stop the copy in flight and drop anything still queued.
     *
     * Both halves matter: cancelling only the running job would have the next
     * one start the moment the user pressed Cancel, which reads as the button
     * not working.
     */
    void cancel() {
        synchronized (queue) {
            queue.clear();
            cancelled = true;
            queue.notifyAll();
        }
    }

    /**
     * Give up the worker. Terminal — call it from {@code onDestroy} and
     * nowhere else.
     *
     * <p>There is no way back: {@code stopped} is never cleared, so after this
     * a job handed in is dropped without a {@link Listener#onDone} and the
     * window's buttons would stay disabled forever. Use {@link #cancel()} for
     * anything that is not the window closing.
     *
     * <p>The {@link HostFiles} handed in at construction is deliberately NOT
     * closed here: the window owns it, browses with it, and closes it in its
     * own {@code onDestroy}. Closing a socket this class only borrowed would
     * kill the left pane the instant a copy was cancelled. The window should
     * call this first and {@code HostFiles.shutdown()} second — that order
     * stops the engine touching a client it is about to lose, and
     * {@code HostFiles.shutdown()} aborts a data socket still waiting out its
     * read timeout against a computer that has already gone.
     */
    void shutdown() {
        synchronized (queue) {
            stopped = true;
            queue.clear();
            cancelled = true;
            queue.notifyAll();
        }
        // The backlog goes too. This is called from the window's onDestroy, and
        // a progress row already sitting on the main queue would otherwise run
        // a moment later against views that have been thrown away.
        main.removeCallbacksAndMessages(null);
    }

    private void enqueue(Job job) {
        synchronized (queue) {
            if (stopped) return;
            // Clearing the cancel only when nothing is in flight is what keeps
            // a job handed in during another one from silently un-cancelling
            // it: a second copy joins the batch already running and shares its
            // Cancel button, which is what the single queue means anyway.
            if (!running && queue.isEmpty()) cancelled = false;
            queue.addLast(job);
            queue.notifyAll();
        }
    }

    // ── the worker ──────────────────────────────────────────────────────

    private void loop() {
        while (true) {
            Job job;
            synchronized (queue) {
                while (queue.isEmpty() && !stopped) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stopped) return;
                job = queue.pollFirst();
                running = true;
            }
            try {
                if (job.phoneDest != null) {
                    runToPhone(job.items, job.phoneDest);
                } else {
                    runToHost(job.items, job.hostDest);
                }
            } catch (Throwable t) {
                // A copy engine that dies takes the window's Cancel button with
                // it and leaves busy() true forever. Whatever went wrong, the
                // batch gets an ending.
                DexLog.warn("files", "the copy engine threw", t);
                post(() -> listener.onDone(0, job.items.size(), 0, cancelled));
            } finally {
                synchronized (queue) {
                    running = false;
                }
            }
        }
    }

    // ── the computer -> the phone ───────────────────────────────────────

    private void runToPhone(List<Item> items, File destDir) {
        Tally t = new Tally();
        if (!destDir.isDirectory() || !destDir.canWrite()) {
            // Almost always the all-files grant: scoped storage refuses this
            // uid a plain open() under /sdcard, and there is no fallback worth
            // having (see the class doc). The window offers the grant button.
            DexLog.warn("files", "cannot write into " + destDir + " — nothing copied");
            post(() -> listener.onDone(0, items.size(), 0, false));
            return;
        }

        List<Unit> units = expandToPhone(items, destDir, t);
        final int count = units.size();
        HostFiles.Cancel cancel = () -> cancelled;

        String landedLeaf = null;
        int landedDirectly = 0;

        for (int i = 0; i < count; i++) {
            if (cancelled) break;
            Unit u = units.get(i);
            final int index = i + 1;
            post(() -> listener.onProgress(u.name, index, count, 0));
            // The landed file comes back from fetch rather than off the Unit:
            // a plain file's final name is not known until fetch picks it.
            File landedAt = fetch(u, row(u.name, index, count), cancel);
            if (landedAt != null) {
                t.copied++;
                File parent = landedAt.getParentFile();
                if (parent != null && parent.equals(destDir)) {
                    landedDirectly++;
                    landedLeaf = landedAt.getName();
                }
            } else if (!cancelled) {
                t.failed++;
            }
        }

        // ONE card at the end of the batch, never one per percent. The row in
        // the window is the live narration; a second progress surface fighting
        // it in the corner of the desktop is noise. What the card is actually
        // for is its "Open folder" button — which is why it has to name the
        // folder the user copied into rather than Downloads, and why the
        // five-argument overload of finished() exists.
        //
        // A cancel that copied nothing gets no card at all. The user pressed
        // Cancel a moment ago and already knows how it ended; a "Transfer
        // failed" card appearing in the corner afterwards contradicts the thing
        // they just did. A cancel that DID copy some still gets one, because
        // those files are real and the card is how the folder gets opened.
        if (t.copied > 0 || (!cancelled && t.failed > 0)) {
            // "landed" is only worth sending for a single file: TransferHud
            // shows its "Open" button solely when the scan comes back with one
            // URI, and for anything else it scans the folder instead — the
            // branch its own comment describes as "a drop too big to list in
            // one broadcast". Passing null takes that branch deliberately.
            String landed = landedDirectly == 1 ? landedLeaf : null;
            String card = items.size() == 1 ? items.get(0).name : destDir.getName();
            WebFiles.finished(ctx, destDir.getAbsolutePath(), card, landed, t.copied > 0);
        }
        done(t);
    }

    /**
     * Turn what the user picked into a flat list of files to fetch, creating
     * the folder skeleton on the phone as it goes.
     *
     * The recursion lives on the sending side — here that is the computer,
     * which walks the tree once and answers with {@code PLAN} — so a folder is
     * enumerated in exactly one place per direction and the two sides cannot
     * disagree about what is in it.
     */
    private List<Unit> expandToPhone(List<Item> items, File destDir, Tally t) {
        List<Unit> units = new ArrayList<>();
        for (Item item : items) {
            if (cancelled) break;
            if (!item.dir) {
                // The destination folder, not a destination file: the free
                // name is picked in fetch(), immediately before the stream
                // opens. See Unit.into.
                units.add(Unit.into(item.path, destDir, item.name));
                continue;
            }
            JSONObject plan;
            try {
                plan = host.plan(item.path);
            } catch (IOException e) {
                DexLog.warn("files", "cannot read the folder " + item.name
                        + " on the computer (" + why(e) + ")");
                t.failed++;
                continue;
            }
            if (plan == null) {
                // Belt and braces against a host that answers OK with nothing
                // in it. One counted failure beats an NPE that would abandon
                // every remaining item in the batch.
                DexLog.warn("files", "the computer sent no plan for " + item.name);
                t.failed++;
                continue;
            }
            // The folder root is uniqued; nothing inside it is. Once the root
            // is a name that did not exist, no child can collide — and a child
            // renamed to "config (2).json" would break every relative
            // reference the folder's own contents make to it.
            File folderRoot = WebFiles.uniqueIn(destDir, item.name);
            if (!folderRoot.mkdirs()) {
                DexLog.warn("files", "cannot create " + folderRoot);
                t.failed++;
                continue;
            }
            // The links, fifos and device nodes the host's own walk refused to
            // plan. The number was on the wire from the first day and nobody
            // read it, so a folder copied off the computer always reported
            // zero skipped however many entries the host had dropped;
            // onDone's skippedLinks is both sides' tally or it is a lie.
            t.skipped += plan.optInt("skipped");
            // Both of the loops below poll the cancel flag, and it is not
            // decoration: a 2000-directory plan is 2000 mkdirs() and a
            // 20000-file one is 20000 stat calls, all of it before a single
            // byte moves. Without the poll, Cancel pressed at the head of a big
            // folder copy does nothing at all for several seconds — which reads
            // as the button being broken, on the one screen where the user is
            // already waiting.
            JSONArray dirs = plan.optJSONArray("dirs");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                if (cancelled) return units;
                File sub = under(folderRoot, dirs.optString(i));
                if (sub == null) {
                    t.failed++;
                } else if (!sub.isDirectory() && !sub.mkdirs()) {
                    DexLog.warn("files", "cannot create " + sub);
                    t.failed++;
                }
            }
            JSONArray files = plan.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) {
                if (cancelled) return units;
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String hostPath = f.optString("path");
                File target = under(folderRoot, f.optString("rel"));
                if (target == null || hostPath.isEmpty()) {
                    // A plan entry with no path is a plan entry we cannot
                    // fetch. Counting it beats queueing a GET of "" and
                    // reading the failure back off the wire a minute later.
                    t.failed++;
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    DexLog.warn("files", "cannot create " + parent);
                    t.failed++;
                    continue;
                }
                units.add(new Unit(hostPath, target, target.getName()));
            }
            if (plan.optBoolean("truncated")) {
                // The host stopped walking. The copy will be short and the user
                // has to be told something, and a batch that reports a failure
                // is the only channel this engine has for "not all of it".
                DexLog.warn("files", "the folder " + item.name
                        + " is larger than the walk cap — copying what was listed");
                t.failed++;
            }
        }
        return units;
    }

    /**
     * A host-supplied relative path, resolved under the folder we created for
     * it and proven to still be inside it.
     *
     * {@code rel} arrives over a socket. It is a slash-separated relative path
     * by the protocol's promise, but a promise is not a check: {@code ../} is
     * the oldest bug in file serving, and one {@code rel} of
     * {@code "../../../../data/data/…"} would have this engine write into
     * another app's storage. {@link WebFiles#resolve(File, String)} is the same
     * canonical-containment test the web server has always run on a path from
     * the network, pointed at this folder instead of the viewer's root.
     *
     * @return null when the path escapes, which the caller counts as a failure.
     */
    private static File under(File folderRoot, String rel) {
        if (rel == null || rel.isEmpty()) return null;
        return WebFiles.resolve(folderRoot, new File(folderRoot, rel).getAbsolutePath());
    }

    /**
     * One file off the computer and onto the phone.
     *
     * The media scan at the end is not optional and not decoration. The phone's
     * own Files app lists shared storage out of MediaStore, and a file written
     * through the filesystem leaves no row there — so without the scan the copy
     * is genuinely on the phone and completely invisible in the folder the user
     * just watched it being copied into. The same sentence is written at
     * {@code WebFiles.scan}, in {@link TransferHud}'s class doc for the drag
     * from Windows, and in {@code Linux.scanShared} for the guest's folder;
     * this is the fourth way a file arrives on this phone, and it is the
     * fourth time the same sentence has had to be written.
     *
     * @return where it landed, or null if it did not — the caller cannot know
     *         the name in advance, because it is chosen here.
     */
    private File fetch(Unit u, HostFiles.Progress p, HostFiles.Cancel c) {
        // Keep-both is decided HERE, one statement before the .part opens, and
        // never at expand time: only the file renamed into place by the fetch
        // before this one makes its name taken, and the worker runs the batch
        // one file at a time so by now it has been. Resolved up front instead,
        // two picks got the same answer and the second silently replaced the
        // first (Unit.into). This is what the host does per PUT — files.rs
        // recomputes parent.join(unique_name(parent, wanted)) at write time
        // rather than trusting the UNIQUE the phone asked for earlier.
        File dest = u.destDir != null ? WebFiles.uniqueIn(u.destDir, u.name) : u.phoneFile;
        File part = new File(dest.getAbsolutePath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            host.get(u.hostPath, out, p, c);
        } catch (Exception e) {
            part.delete();
            if (!cancelled) {
                DexLog.warn("files", "could not copy " + u.name + " to the phone ("
                        + why(e) + ")");
            }
            return null;
        }
        if (cancelled) {
            part.delete();
            return null;
        }
        if (!part.renameTo(dest)) {
            part.delete();
            DexLog.warn("files", "could not rename " + part);
            return null;
        }
        WebFiles.scan(ctx, dest);
        return dest;
    }

    // ── the phone -> the computer ───────────────────────────────────────

    private void runToHost(List<Item> items, String destPath) {
        Tally t = new Tally();
        if (destPath == null || destPath.isEmpty()) {
            // The mirror of the destDir check going the other way. Without it
            // HostFiles.join answers the bare leaf name, every PUT goes to a
            // relative path the host resolves against its own working
            // directory, and the copy "succeeds" into somewhere nobody can
            // name. Failing the batch here is the only honest answer.
            DexLog.warn("files", "no destination folder on the computer — nothing copied");
            post(() -> listener.onDone(0, items.size(), 0, false));
            return;
        }
        List<Unit> units = expandToHost(items, destPath, t);
        final int count = units.size();
        HostFiles.Cancel cancel = () -> cancelled;

        for (int i = 0; i < count; i++) {
            if (cancelled) break;
            Unit u = units.get(i);
            final int index = i + 1;
            post(() -> listener.onProgress(u.name, index, count, 0));
            if (send(u, row(u.name, index, count), cancel)) {
                t.copied++;
            } else if (!cancelled) {
                t.failed++;
            }
        }
        // Nothing is broadcast in this direction, on purpose. ACTION_TRANSFER's
        // entire vocabulary is inbound — "Copying to %s", "Open folder" opening
        // the phone's Downloads — and a card on the phone's desktop announcing
        // a file that is now on the computer would point at a folder the user
        // cannot open from here.
        done(t);
    }

    /**
     * Walk what the user picked and build the folder skeleton on the computer.
     *
     * The sending side enumerates, so this direction's recursion is here rather
     * than on the host: {@link File#listFiles()} plus a {@code UNIQUE} and a
     * {@code MKDIR} per directory, against {@code PLAN} plus one
     * {@code mkdirs()} going the other way. Iterative, so a deep tree cannot
     * overflow the stack — the reason
     * {@code LinuxService.deleteTree} (:329) walks a rootfs with an explicit
     * deque rather than a call stack — and breadth-first on top of that, so
     * every directory is created on the computer before anything that goes
     * inside it.
     */
    private List<Unit> expandToHost(List<Item> items, String destPath, Tally t) {
        List<Unit> units = new ArrayList<>();
        char sep = host.sep();
        for (Item item : items) {
            if (cancelled) break;
            File local = new File(item.path);
            if (isLink(local)) {
                t.skipped++;
                continue;
            }
            String name;
            try {
                // The host picks the free name: "report (2).pdf" has to be
                // decided where the collision is, and only the computer can see
                // its own folder.
                name = host.unique(destPath, item.name);
            } catch (IOException e) {
                DexLog.warn("files", "the computer refused a name for " + item.name
                        + " (" + why(e) + ")");
                t.failed++;
                continue;
            }
            if (name == null || name.isEmpty()) {
                DexLog.warn("files", "the computer named no target for " + item.name);
                t.failed++;
                continue;
            }
            String target = HostFiles.join(destPath, name, sep);
            if (!item.dir) {
                units.add(new Unit(target, local, item.name));
                continue;
            }
            try {
                host.mkdir(target);
            } catch (IOException e) {
                DexLog.warn("files", "cannot create " + item.name
                        + " on the computer (" + why(e) + ")");
                t.failed++;
                continue;
            }
            if (!walk(new Step(local, target), sep, units, t)) {
                // Hit a cap. Same treatment as the host's truncated PLAN: the
                // copy will be short, and a counted failure is the only way
                // this engine can say so.
                DexLog.warn("files", "the folder " + item.name
                        + " is larger than the walk cap — copying what was listed");
                t.failed++;
            }
        }
        return units;
    }

    /** @return false when a cap was hit and the tree was not fully listed. */
    private boolean walk(Step root, char sep, List<Unit> units, Tally t) {
        ArrayDeque<Step> pending = new ArrayDeque<>();
        pending.addLast(root);
        int dirs = 1;
        while (!pending.isEmpty()) {
            if (cancelled) return true;
            Step step = pending.pollFirst();
            File[] kids = step.local.listFiles();
            if (kids == null) {
                DexLog.warn("files", "cannot list " + step.local);
                t.failed++;
                continue;
            }
            Arrays.sort(kids, BY_KIND_THEN_NAME);
            for (File kid : kids) {
                if (cancelled) return true;
                // The same two skips both panes already apply — WebFiles.list
                // here, list() on the computer — and the same two the host's
                // PLAN walk applies going the other way. Without them a folder
                // copied off the phone carried files the user had never seen
                // and never picked: DCIM on Android 11+ uploaded the
                // ".trashed-<expiry>-*" entries, photos the user had DELETED,
                // and the host pane hides dot-names too so they were then
                // findable only in Explorer. A folder that copies one way has
                // to be the same folder that copies the other way.
                if (kid.isHidden() || kid.getName().endsWith(".part")) continue;
                if (isLink(kid)) {
                    t.skipped++;
                    continue;
                }
                if (kid.isDirectory()) {
                    if (++dirs > WALK_DIRS_CAP) return false;
                    String child;
                    try {
                        // Every directory goes through UNIQUE, not just the
                        // one the user picked. A name legal on the phone is
                        // not always legal on the computer — a subfolder
                        // called "12:30 meeting" is ordinary here and
                        // create_dir_all cannot make it on Windows, so the
                        // whole subtree used to vanish on one MKDIR error.
                        // UNIQUE answers with the name the computer can
                        // actually hold, and the recursion continues under
                        // THAT name: sanitising inside the host's MKDIR
                        // instead would leave the phone still saying
                        // "12:30 meeting" for a folder created as
                        // "12_30 meeting", and every PUT below it would fail
                        // put()'s parent is_dir check.
                        String named = host.unique(step.hostDir, kid.getName());
                        if (named == null || named.isEmpty()) {
                            DexLog.warn("files", "the computer named no target for "
                                    + kid.getName());
                            t.failed += lost(kid);
                            continue;
                        }
                        child = HostFiles.join(step.hostDir, named, sep);
                        host.mkdir(child);
                    } catch (IOException e) {
                        DexLog.warn("files", "cannot create " + kid.getName()
                                + " on the computer (" + why(e) + ")");
                        t.failed += lost(kid);
                        continue;
                    }
                    pending.addLast(new Step(kid, child));
                } else {
                    if (units.size() >= WALK_FILES_CAP) return false;
                    units.add(new Unit(HostFiles.join(step.hostDir, kid.getName(), sep),
                            kid, kid.getName()));
                }
            }
        }
        return true;
    }

    /**
     * How many files go missing when a directory could not be made on the
     * computer and its whole subtree is dropped.
     *
     * A dropped subtree used to be a single {@code t.failed}, so a folder of
     * 400 photos the computer could not name reported "1 failed" — the window
     * said the copy was one file short of perfect when in truth none of that
     * folder arrived. Counting costs a listing of a tree this walk was about
     * to list anyway, and both caps are the walk's own, so the arithmetic
     * cannot cost more than the copy would have.
     *
     * The two skips are {@link #walk}'s own, and applying them here is what
     * keeps the number honest: a subtree whose only casualties were a
     * {@code .git} and a {@code .DS_Store} lost nothing the copy was ever
     * going to carry, and counting those would report files as failed that
     * would never have been sent.
     *
     * Links are stepped over rather than followed, and not for tidiness: a
     * link back up its own tree would otherwise keep the deque fed with
     * directories forever without ever reaching either cap.
     *
     * @return at least one, so a directory too broken even to list still says
     *         something went wrong.
     */
    private static int lost(File dir) {
        int files = 0;
        int dirs = 0;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.addLast(dir);
        while (!pending.isEmpty() && files < WALK_FILES_CAP && dirs < WALK_DIRS_CAP) {
            File[] kids = pending.pollFirst().listFiles();
            if (kids == null) continue;
            for (File kid : kids) {
                if (kid.isHidden() || kid.getName().endsWith(".part")) continue;
                if (isLink(kid)) continue;
                if (kid.isDirectory()) {
                    dirs++;
                    pending.addLast(kid);
                } else {
                    files++;
                }
            }
        }
        return Math.max(files, 1);
    }

    /**
     * One file off the phone and onto the computer.
     *
     * The length is re-read here rather than trusted from the walk: a
     * {@code PUT} declares its size up front and then has to deliver exactly
     * that many bytes, and a file still being written when the folder was
     * listed would otherwise desynchronise the socket for every file after it.
     */
    private boolean send(Unit u, HostFiles.Progress p, HostFiles.Cancel c) {
        long size = u.phoneFile.length();
        try (InputStream in = new FileInputStream(u.phoneFile)) {
            String landed = host.put(u.hostPath, size, in, p, c);
            if (landed != null && !landed.equals(u.name)) {
                DexLog.step("files", "sent " + u.name + " — the computer stored it as " + landed);
            }
            return !cancelled;
        } catch (Exception e) {
            if (!cancelled) {
                DexLog.warn("files", "could not copy " + u.name + " to the computer ("
                        + why(e) + ")");
            }
            return false;
        }
    }

    // ── narration ───────────────────────────────────────────────────────

    /**
     * A progress sink for one file that speaks only when the integer
     * percentage actually changes.
     *
     * Not a micro-optimisation: the callback fires once per 64 KB, so a 2 GB
     * film would post thirty thousand messages to the main thread and every one
     * of them would set the same text. {@code WebFiles.pump} makes exactly this
     * choice, and {@code WebRtcFiles.onBinary} makes it again for the data
     * channel.
     */
    private HostFiles.Progress row(final String name, final int index, final int count) {
        return new HostFiles.Progress() {
            /** The 0% row went up when the file started; do not repeat it. */
            private int last;

            @Override
            public void onBytes(long done, long total) {
                // A zero-byte file is finished, not unknown: HostFiles reports
                // its one and only tick as (0, 0), and answering -1 there would
                // leave an indeterminate bar spinning for a file that has
                // already landed. -1 is reserved for a size nobody declared.
                int pct = total > 0 ? (int) (done * 100 / total) : (done == 0 ? 100 : -1);
                if (pct == last) return;
                last = pct;
                post(() -> listener.onProgress(name, index, count, pct));
            }
        };
    }

    private void done(Tally t) {
        final boolean wasCancelled = cancelled;
        DexLog.step("files", "batch finished: " + t.copied + " copied, " + t.failed
                + " failed, " + t.skipped + " link(s) skipped"
                + (wasCancelled ? ", cancelled" : ""));
        post(() -> listener.onDone(t.copied, t.failed, t.skipped, wasCancelled));
    }

    /**
     * Hand a callback to the window, or drop it if the window has gone.
     *
     * The {@code stopped} test is done on the main thread inside the posted
     * runnable, not at the call site, and the difference matters: the worker
     * cannot test a flag and post atomically, so a callback handed to the queue
     * a microsecond before {@link #shutdown()} ran would clear a check made
     * here and still be delivered into a destroyed window. Tested where it is
     * about to run, on the one thread {@code shutdown()} is also called from,
     * there is no such gap. {@link #shutdown()} clears the backlog; this
     * catches the stragglers behind it.
     */
    private void post(Runnable r) {
        main.post(() -> {
            synchronized (queue) {
                if (stopped) return;
            }
            r.run();
        });
    }

    // ── small truths ────────────────────────────────────────────────────

    /**
     * Whether this is a symlink, by the only test available without a syscall
     * wrapper — and it fails safe.
     *
     * <p><b>Not the obvious form of it.</b> {@code LinuxService.isLink} (:350)
     * compares the canonical path against the absolute path, and copying that
     * here verbatim would have silently broken the entire phone→computer
     * direction: that comparison answers "link" when <em>any</em> component of
     * the path is one, and on Android {@code /sdcard} is itself a link to
     * {@code /storage/self/primary}. Every ordinary file the phone pane can
     * show lives under it, so every file the user picked would have been
     * counted as a skipped shortcut and nothing would ever have been sent.
     * LinuxService gets away with the short form because it walks a guest
     * rootfs under the app's own data directory, where no ancestor is a link.
     * So the parent is canonicalised first and only the last component is
     * tested — the same shape {@code FileUtils.isSymlink} has always had.
     *
     * <p>A path that cannot be canonicalised at all is treated as a link and
     * never descended into, exactly as LinuxService does when it is deleting a
     * rootfs full of them.
     */
    private static boolean isLink(File f) {
        try {
            File parent = f.getParentFile();
            File probe = parent == null ? f : new File(parent.getCanonicalFile(), f.getName());
            return !probe.getCanonicalFile().equals(probe.getAbsoluteFile());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * What to put in the log line for a failure.
     *
     * A {@link HostFiles.HostException} already carries the protocol's own word
     * for what went wrong — {@code denied}, {@code space}, {@code notfound} —
     * and that word is worth more than the stack trace it came wrapped in,
     * because it is the same word the host wrote down.
     */
    private static String why(Exception e) {
        if (e instanceof HostFiles.HostException) {
            return ((HostFiles.HostException) e).code;
        }
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }
}

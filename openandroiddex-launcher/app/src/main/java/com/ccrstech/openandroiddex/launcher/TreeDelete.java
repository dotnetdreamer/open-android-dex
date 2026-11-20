package com.ccrstech.openandroiddex.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;

/**
 * Deletes a directory tree, and says exactly what it could not.
 *
 * <p>Written for the Linux container, which is the hardest tree this app ever
 * deletes: a few hundred thousand files, symlinks everywhere (absolute ones,
 * which read from outside the guest point at /data, /sdcard, anywhere), and
 * directories a package left without write or even read permission for their
 * owner. Every entry belongs to the app, so nothing here needs more than the
 * app has. What it needs is care, and an honest answer:
 * <ul>
 *   <li>A symlink is unlinked, never followed. Each entry is lstat'ed itself,
 *       so a parent that is a symlink (/data/user/0 is one on many phones)
 *       changes nothing. The old walk compared canonical and absolute paths,
 *       which every path under such a parent fails.</li>
 *   <li>A directory gets u+rwx back before it is listed and emptied. The
 *       entries of a 0555 directory cannot be unlinked, and those of a 0000
 *       one cannot even be listed, which used to leave them in place without
 *       a word.</li>
 *   <li>The answer is what is LEFT, counted after the walk ({@link #measure}),
 *       not what failed along the way: a straggling process can add to a tree
 *       during a walk, and a tree only counts as deleted when it is gone.</li>
 * </ul>
 *
 * <p>No Android classes, so the same code is tested on a plain JVM, as a
 * non-root user on Linux, where permissions mean what they mean on a phone.
 */
final class TreeDelete {

    private TreeDelete() {
    }

    private static final Set<PosixFilePermission> OWNER_RWX = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /** What a delete left behind. {@link #clean()} when nothing was. */
    static final class Result {
        /** Entries still there: files, links and directories, the top included. */
        int left;
        /** Their size, as lstat counts it. */
        long bytesLeft;
        /** The first thing that went wrong, as "path: reason", or null. */
        String firstError;

        boolean clean() {
            return left == 0;
        }

        void add(Result o) {
            if (o == null) return;
            left += o.left;
            bytesLeft += o.bytesLeft;
            if (firstError == null) firstError = o.firstError;
        }

        void note(Object where, Object why) {
            if (firstError == null) firstError = where + ": " + why;
        }

        @Override
        public String toString() {
            return clean() ? "nothing left"
                    : left + " entries (" + bytesLeft + " bytes) left; first: " + firstError;
        }
    }

    /** Delete {@code top} and everything under it; what is left comes back. */
    static Result delete(File top) {
        Path root = top.toPath();
        Result r = new Result();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return r;
        // Iterative: a rootfs is deep enough for recursion to be a real risk.
        // Directories are deleted in the reverse of the order they were met,
        // which puts every one after everything inside it.
        ArrayDeque<Path> todo = new ArrayDeque<>();
        ArrayDeque<Path> dirs = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            Path p = todo.pop();
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException gone) {
                continue;
            } catch (IOException e) {
                r.note(p, e);
                continue;
            }
            if (!a.isDirectory()) {
                // A file, a symlink (to anything at all), a fifo, a socket.
                unlink(p, a.isRegularFile(), r);
                continue;
            }
            openUp(p);
            dirs.push(p);
            try (DirectoryStream<Path> kids = Files.newDirectoryStream(p)) {
                for (Path k : kids) todo.push(k);
            } catch (NoSuchFileException gone) {
                // nothing to list
            } catch (IOException e) {
                r.note(p, e);
            }
        }
        while (!dirs.isEmpty()) {
            Path d = dirs.pop();
            try {
                Files.delete(d);
            } catch (NoSuchFileException gone) {
                // fine
            } catch (IOException e) {
                r.note(d, e);
            }
        }
        Result left = measure(top);
        left.firstError = left.clean() ? null : r.firstError;
        return left;
    }

    /** What is under {@code top} now, top included, without following a single link. */
    static Result measure(File top) {
        Result r = new Result();
        Path root = top.toPath();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return r;
        ArrayDeque<Path> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            Path p = todo.pop();
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException gone) {
                continue;
            } catch (IOException e) {
                r.left++; // there, but not even stat-able
                continue;
            }
            r.left++;
            if (!a.isDirectory()) {
                r.bytesLeft += a.size();
                continue;
            }
            try (DirectoryStream<Path> kids = Files.newDirectoryStream(p)) {
                for (Path k : kids) todo.push(k);
            } catch (IOException e) {
                // counted as itself; what is inside cannot be seen
            }
        }
        return r;
    }

    private static void unlink(Path p, boolean regular, Result r) {
        try {
            Files.delete(p);
            return;
        } catch (NoSuchFileException gone) {
            return;
        } catch (IOException e) {
            // On a phone only the directory's permission matters, and that was
            // given back on the way down. Elsewhere (Windows' read-only
            // attribute) the file's own can be in the way. Never for a link:
            // chmod follows it, to whatever it points at.
            if (!regular) {
                r.note(p, e);
                return;
            }
        }
        try {
            p.toFile().setWritable(true, true);
            Files.delete(p);
        } catch (NoSuchFileException gone) {
            // fine
        } catch (IOException e) {
            r.note(p, e);
        }
    }

    /** Give the owner read, write and search on a directory before it is emptied. */
    private static void openUp(Path dir) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            if (perms.containsAll(OWNER_RWX)) return;
            perms.addAll(OWNER_RWX);
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException notPosix) {
            File f = dir.toFile();
            f.setReadable(true, true);
            f.setWritable(true, true);
            f.setExecutable(true, true);
        } catch (IOException ignored) {
            // What cannot be opened stays, and measure() counts it.
        }
    }
}

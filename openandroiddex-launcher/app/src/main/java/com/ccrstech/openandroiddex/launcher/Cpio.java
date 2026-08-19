package com.ccrstech.openandroiddex.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * A one-file cpio writer, which is all the Docker VM needs to get its own
 * {@code /init} into Alpine's initramfs.
 *
 * The trick this class exists for: the Linux kernel accepts an initramfs that
 * is SEVERAL cpio archives concatenated, each independently compressed, and it
 * unpacks them in order with later entries overwriting earlier ones. So the
 * app never has to read, modify or repack Alpine's 9 MB initramfs-virt — it
 * appends a few hundred bytes carrying one file, and that file lands on top of
 * Alpine's /init while every binary, module and modules.dep they shipped stays
 * exactly where it was.
 *
 * The format is "newc" (SVR4, no checksum): a fixed 110-byte ASCII-hex header,
 * the NUL-terminated name padded to a 4-byte boundary, the data padded the
 * same way, and a final entry named TRAILER!!! with no data.
 */
final class Cpio {

    private Cpio() {}

    private static final String MAGIC = "070701";
    private static final int C_ISREG = 0100000;

    /**
     * Write {@code boot.img} = {@code base} followed by a gzipped cpio holding
     * one executable file.
     *
     * The two halves are concatenated as bytes, NOT merged: the base keeps its
     * own compression and is copied through untouched, so a corrupt result can
     * only ever be our own few hundred bytes.
     */
    static void appendSingleFile(File base, File out, String name, byte[] content)
            throws Exception {
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try (OutputStream fo = new FileOutputStream(tmp)) {
            try (InputStream in = new FileInputStream(base)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            fo.write(gzippedEntry(name, content));
        }
        out.delete();
        if (!tmp.renameTo(out)) throw new java.io.IOException("cannot rename " + tmp);
    }

    private static byte[] gzippedEntry(String name, byte[] content) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeEntry(raw, name, content, 0755);
        writeEntry(raw, "TRAILER!!!", new byte[0], 0);

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(raw.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void writeEntry(ByteArrayOutputStream out, String name,
                                   byte[] data, int perm) throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int nameSize = nameBytes.length + 1; // the NUL counts

        StringBuilder h = new StringBuilder(110);
        h.append(MAGIC);
        // The trailer is the one entry with no mode and no inode; giving it a
        // real one makes some unpackers treat it as a file called TRAILER!!!.
        hex(h, perm == 0 ? 0 : 1);                       // ino
        hex(h, perm == 0 ? 0 : (C_ISREG | perm));        // mode
        hex(h, 0);                                       // uid  — root
        hex(h, 0);                                       // gid  — root
        hex(h, 1);                                       // nlink
        hex(h, 0);                                       // mtime, fixed for reproducibility
        hex(h, data.length);                             // filesize
        hex(h, 0);                                       // devmajor
        hex(h, 0);                                       // devminor
        hex(h, 0);                                       // rdevmajor
        hex(h, 0);                                       // rdevminor
        hex(h, nameSize);                                // namesize
        hex(h, 0);                                       // check — unused in newc

        byte[] header = h.toString().getBytes(StandardCharsets.US_ASCII);
        out.write(header);
        out.write(nameBytes);
        out.write(0);
        pad(out, header.length + nameSize);
        out.write(data);
        pad(out, data.length);
    }

    private static void hex(StringBuilder sb, int v) {
        String s = Integer.toHexString(v);
        for (int i = s.length(); i < 8; i++) sb.append('0');
        sb.append(s);
    }

    /** newc aligns both the name and the data to a 4-byte boundary. */
    private static void pad(ByteArrayOutputStream out, int written) {
        for (int i = (4 - (written & 3)) & 3; i > 0; i--) out.write(0);
    }
}

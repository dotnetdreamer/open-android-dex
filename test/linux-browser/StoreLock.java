import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * LinuxBrowser's lock between processes (its {@code .lock} file), held by a
 * process of its own, as the Settings page's process holds it, for
 * test/linux-browser-test.mjs: {@code lock} answers {@code LOCKED} once it is
 * held, {@code unlock} answers {@code UNLOCKED}. Run as {@code java
 * StoreLock.java <dir>}.
 *
 * <p>With it held, a pairing waits inside its code's check and a sign-out
 * waits behind that pairing; let go, the two run in that order, and whether
 * the sign-out can fall between the code and the token is the store's to say.
 */
public final class StoreLock {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(new File(args[0], ".lock"), "rw")) {
            FileLock held = null;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = in.readLine()) != null; ) {
                line = line.trim();
                if (line.equals("lock") && held == null) {
                    held = raf.getChannel().lock();
                    System.out.println("LOCKED");
                } else if (line.equals("unlock") && held != null) {
                    held.release();
                    held = null;
                    System.out.println("UNLOCKED");
                }
                System.out.flush();
            }
        }
    }
}

package com.ccrstech.openandroiddex.wmd;

/**
 * The window daemon's Shapes — SHAPE and SHAPEWAIT — on a computer's JVM: the newest
 * shape, a wait that ends when it changes or when it runs out, and a shape that goes back
 * to the arrow when the connection that set it does. For test/window-resize-test.mjs.
 */
public final class ShapesCheck {

    private static int checks;
    private static int failures;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "ok " : "FAIL ") + what);
    }

    public static void main(String[] args) throws Exception {
        Object launcher = new Object();
        Object other = new Object();

        Shapes.reset();
        String[] first = Shapes.await(7, -1, 0);
        check("0".equals(first[0]) && "default".equals(first[1]),
                "a display nobody has spoken about is the arrow, at once");

        long one = Shapes.set(7, "ew-resize", launcher);
        String[] now = Shapes.await(7, 0, 0);
        check(one == 1 && "1".equals(now[0]) && "ew-resize".equals(now[1]),
                "a shape set is the shape, under a new number");
        check(Shapes.set(7, "ew-resize", launcher) == one, "the same shape again is not a change");

        boolean refused = false;
        try {
            Shapes.set(7, "rm -rf", launcher);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "a name that is not a cursor's is refused");

        // A wait that something answers.
        Thread later = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
            }
            Shapes.set(7, "ns-resize", launcher);
        });
        long t0 = System.currentTimeMillis();
        later.start();
        String[] woke = Shapes.await(7, one, 1000);
        long took = System.currentTimeMillis() - t0;
        later.join();
        check("ns-resize".equals(woke[1]) && took >= 60 && took < 800,
                "a wait ends when the shape changes (" + took + " ms)");

        // A wait nothing answers.
        long seq = Long.parseLong(woke[0]);
        t0 = System.currentTimeMillis();
        String[] same = Shapes.await(7, seq, 150);
        took = System.currentTimeMillis() - t0;
        check(Long.parseLong(same[0]) == seq && "ns-resize".equals(same[1])
                && took >= 140 && took < 700, "a wait runs out with the shape unchanged (" + took + " ms)");

        t0 = System.currentTimeMillis();
        Shapes.await(7, seq, 60_000);
        took = System.currentTimeMillis() - t0;
        check(took < Shapes.WAIT_MAX_MS + 600,
                "no wait holds a connection longer than " + Shapes.WAIT_MAX_MS + " ms (" + took + ")");

        Shapes.set(8, "nwse-resize", other);
        check("ns-resize".equals(Shapes.await(7, -1, 0)[1])
                        && "nwse-resize".equals(Shapes.await(8, -1, 0)[1]),
                "each display has its own shape");

        Shapes.dropOwnedBy(other);
        check("ns-resize".equals(Shapes.await(7, -1, 0)[1]),
                "another connection going leaves this display's shape alone");
        check("default".equals(Shapes.await(8, -1, 0)[1]),
                "…and takes its own display's back to the arrow");

        long before = Long.parseLong(Shapes.await(7, -1, 0)[0]);
        Shapes.dropOwnedBy(launcher);
        String[] gone = Shapes.await(7, before, 0);
        check("default".equals(gone[1]) && Long.parseLong(gone[0]) > before,
                "the launcher going takes its shape back to the arrow, as a change the PC sees");

        Shapes.set(7, "ew-resize", launcher);
        Shapes.set(7, "default", launcher);
        long after = Long.parseLong(Shapes.await(7, -1, 0)[0]);
        Shapes.dropOwnedBy(launcher);
        check(Long.parseLong(Shapes.await(7, -1, 0)[0]) == after,
                "an arrow belongs to nobody: a connection going after it changes nothing");

        System.out.println("done " + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}

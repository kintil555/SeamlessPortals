package com.warwa.seamlessportals;

/** Temporary diagnostics: per-second counters of Axiom-related events. Remove once fixed. */
public final class AxiomDiag {
    private static final int N = 6;
    private static final String[] NAMES = {"setBlock", "revertBlock", "invalidateSection", "loadBlocks", "packets", "remeshResend"};
    private static final int[] counts = new int[N];
    private static long last = System.nanoTime();

    public static void hit(int i) { counts[i]++; }
    public static final int SET = 0, REVERT = 1, INVAL = 2, LOAD = 3, PKT = 4, REMESH = 5;

    /** Call once per frame (render thread). */
    public static void flush() {
        long now = System.nanoTime();
        if (now - last < 1_000_000_000L) return;
        last = now;
        StringBuilder sb = null;
        for (int i = 0; i < N; i++) {
            if (counts[i] > 0) {
                if (sb == null) sb = new StringBuilder("[AXIOM-DIAG] last 1s:");
                sb.append(' ').append(NAMES[i]).append('=').append(counts[i]);
                counts[i] = 0;
            }
        }
        if (sb != null) SeamlessPortalsConstants.LOGGER.info(sb.toString());
    }

    private AxiomDiag() {}
}

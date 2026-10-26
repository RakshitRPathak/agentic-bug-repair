package dev.rakshit.dsa;

import java.util.Random;

/** Seeded random inputs. Fixed seeds make every failure reproducible: same seed, same input, same failure. */
final class Gen {
    private Gen() {}

    static int[] ints(Random rnd, int maxLen, int lo, int hi) {
        int[] a = new int[rnd.nextInt(maxLen + 1)];
        for (int i = 0; i < a.length; i++) a[i] = lo + rnd.nextInt(hi - lo + 1);
        return a;
    }

    static String string(Random rnd, int maxLen, int alphabet) {
        char[] cs = new char[rnd.nextInt(maxLen + 1)];
        for (int i = 0; i < cs.length; i++) cs[i] = (char) ('a' + rnd.nextInt(alphabet));
        return new String(cs);
    }
}

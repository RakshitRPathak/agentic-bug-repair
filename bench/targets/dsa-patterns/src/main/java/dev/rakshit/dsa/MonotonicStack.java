package dev.rakshit.dsa;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * PATTERN: monotonic stack.
 *
 * <p><b>Recognise it when:</b> for each element you need "the nearest element to the left/right that is
 * larger/smaller" ("next greater", "days until warmer", "how far can this bar extend").
 *
 * <p><b>Why it works:</b> keep a stack of indices whose values are in order (here, decreasing from bottom to top).
 * When a new value arrives it is the answer for every stack element smaller than it, so pop them and record the
 * answer. Whatever remains is still waiting for a bigger value. Every index is pushed once and popped once, so the
 * total is O(n) even though there is a loop inside the loop.
 */
public final class MonotonicStack {
    private MonotonicStack() {}

    /** For each element, the next strictly greater value to its right, or -1. */
    public static int[] nextGreater(int[] a) {
        int[] out = new int[a.length];
        Arrays.fill(out, -1);
        Deque<Integer> waiting = new ArrayDeque<>(); // indices still looking for a greater value
        for (int i = 0; i < a.length; i++) {
            while (!waiting.isEmpty() && a[waiting.peek()] < a[i]) out[waiting.pop()] = a[i];
            waiting.push(i);
        }
        return out;
    }

    /** For each day, how many days until a strictly warmer one (0 if never). Same idea, storing distance. */
    public static int[] dailyTemperatures(int[] t) {
        int[] out = new int[t.length];
        Deque<Integer> waiting = new ArrayDeque<>();
        for (int i = 0; i < t.length; i++) {
            while (!waiting.isEmpty() && t[waiting.peek()] < t[i]) {
                int j = waiting.pop();
                out[j] = i - j;
            }
            waiting.push(i);
        }
        return out;
    }

    /**
     * Area of the largest rectangle in a histogram.
     *
     * <p>The best rectangle has some bar as its height, extending left and right until a shorter bar. Keep
     * a stack of bars in increasing height. When a shorter bar arrives it is the right edge for every taller bar on
     * the stack; the new top of the stack (or the start) is each popped bar's left edge. A sentinel height 0 at the
     * end flushes the stack.
     */
    public static long largestRectangle(int[] heights) {
        Deque<Integer> stack = new ArrayDeque<>(); // indices with increasing heights
        long best = 0;
        for (int i = 0; i <= heights.length; i++) {
            int h = i == heights.length ? 0 : heights[i];
            while (!stack.isEmpty() && heights[stack.peek()] >= h) {
                int height = heights[stack.pop()];
                int leftEdge = stack.isEmpty() ? -1 : stack.peek(); // first bar to the left that is shorter
                best = Math.max(best, (long) height * (i - leftEdge - 1));
            }
            stack.push(i);
        }
        return best;
    }
}

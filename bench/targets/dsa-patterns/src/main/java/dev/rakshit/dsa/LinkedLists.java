package dev.rakshit.dsa;

/**
 * PATTERN: linked-list pointer manipulation.
 *
 * <p><b>Recognise it when:</b> the input is a linked list. Three tools solve most problems:
 * <ol>
 *   <li><b>Dummy head node.</b> A fake node before the real head removes the "what if the head changes or is
 *       deleted" special case: the answer is always {@code dummy.next}.</li>
 *   <li><b>Slow and fast pointers.</b> Fast moves two steps per slow's one: the middle, a cycle, the n-th from
 *       the end (give one pointer an n-step head start) all fall out of their relative positions.</li>
 *   <li><b>Rewire, do not copy.</b> Save {@code next} before you overwrite it, or you lose the rest of the list.</li>
 * </ol>
 */
public final class LinkedLists {
    private LinkedLists() {}

    /** Reverses the list in place. At each step: remember next, point this node backwards, advance. */
    public static ListNode reverse(ListNode head) {
        ListNode prev = null, cur = head;
        while (cur != null) {
            ListNode next = cur.next;
            cur.next = prev;
            prev = cur;
            cur = next;
        }
        return prev;
    }

    /** True if the list has a cycle (Floyd): if there is one, the fast pointer eventually laps the slow one. */
    public static boolean hasCycle(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
            if (slow == fast) return true;
        }
        return false;
    }

    /**
     * The node where the cycle begins, or null. After slow and fast meet, a pointer starting at the head and
     * one starting at the meeting point, both moving one step at a time, meet exactly at the cycle's entrance.
     * (Distance head-&gt;entrance equals distance meeting point-&gt;entrance, going round the loop, because fast has
     * travelled twice as far as slow.)
     */
    public static ListNode cycleStart(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
            if (slow == fast) {
                ListNode p = head;
                while (p != slow) {
                    p = p.next;
                    slow = slow.next;
                }
                return p;
            }
        }
        return null;
    }

    /** Middle node; for an even length, the second of the two middles. */
    public static ListNode middle(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
        }
        return slow;
    }

    /** Merges two sorted lists into one sorted list, reusing the nodes. Stable: ties take from {@code a} first. */
    public static ListNode mergeTwoSorted(ListNode a, ListNode b) {
        ListNode dummy = new ListNode(0), tail = dummy;
        while (a != null && b != null) {
            if (b.val < a.val) {
                tail.next = b;
                b = b.next;
            } else {
                tail.next = a;
                a = a.next;
            }
            tail = tail.next;
        }
        tail.next = a != null ? a : b;
        return dummy.next;
    }

    /**
     * Removes the n-th node from the end (n = 1 removes the last) in one pass.
     * Advance {@code fast} n steps ahead, then move both until fast reaches the last node: slow is then just
     * before the node to remove. The dummy head handles removing the first node.
     */
    public static ListNode removeNthFromEnd(ListNode head, int n) {
        ListNode dummy = new ListNode(0, head), fast = dummy, slow = dummy;
        for (int i = 0; i < n; i++) {
            if (fast.next == null) return head; // n larger than the list: nothing to remove
            fast = fast.next;
        }
        while (fast.next != null) {
            fast = fast.next;
            slow = slow.next;
        }
        slow.next = slow.next.next;
        return dummy.next;
    }

    /** True if the list reads the same forwards and backwards, in O(1) extra space: reverse the second half, compare. */
    public static boolean isPalindrome(ListNode head) {
        if (head == null || head.next == null) return true;
        ListNode mid = middle(head);
        ListNode second = reverse(mid);           // the first half still ends at the node before mid
        ListNode a = head, b = second;
        boolean ok = true;
        while (b != null) {                       // second half is the same length or one shorter
            if (a.val != b.val) {
                ok = false;
                break;
            }
            a = a.next;
            b = b.next;
        }
        reverse(second);                          // restore the list: callers should not see it changed
        return ok;
    }
}

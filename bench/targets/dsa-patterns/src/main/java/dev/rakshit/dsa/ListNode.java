package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.List;

/** Singly linked list node, with helpers for building and inspecting lists in tests and examples. */
public final class ListNode {
    public int val;
    public ListNode next;

    public ListNode(int val) {
        this.val = val;
    }

    public ListNode(int val, ListNode next) {
        this.val = val;
        this.next = next;
    }

    /** Builds a list from values; returns null for no values. */
    public static ListNode of(int... values) {
        ListNode dummy = new ListNode(0), tail = dummy;
        for (int v : values) {
            tail.next = new ListNode(v);
            tail = tail.next;
        }
        return dummy.next;
    }

    /** Values from this node to the end. Do not call on a list with a cycle. */
    public List<Integer> toList() {
        List<Integer> out = new ArrayList<>();
        for (ListNode n = this; n != null; n = n.next) out.add(n.val);
        return out;
    }

    public static List<Integer> toList(ListNode head) {
        return head == null ? new ArrayList<>() : head.toList();
    }
}

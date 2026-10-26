package dev.rakshit.repair.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic finite state machine: the next state depends only on the current state and the event, through a fixed
 * table. An event with no entry for the current state is a programming error and throws, so the agent cannot wander into
 * a state the design does not allow. Every transition is recorded with its reason and timing, which is what the
 * dashboard draws.
 */
public final class StateMachine<S extends Enum<S>, E extends Enum<E>> {
    public record Transition<S, E>(S from, E event, S to, String reason, long atMs, long msInFrom) {}

    public static final class Builder<S extends Enum<S>, E extends Enum<E>> {
        private final Class<S> stateType;
        private final Map<S, Map<E, S>> table;
        private final S initial;
        private final Class<E> eventType;

        public Builder(Class<S> stateType, Class<E> eventType, S initial) {
            this.stateType = stateType;
            this.eventType = eventType;
            this.initial = initial;
            this.table = new EnumMap<>(stateType);
        }

        public Builder<S, E> on(S from, E event, S to) {
            Map<E, S> row = table.computeIfAbsent(from, k -> new EnumMap<>(eventType));
            if (row.put(event, to) != null) throw new IllegalArgumentException("duplicate transition " + from + " on " + event);
            return this;
        }

        public StateMachine<S, E> build() {
            return new StateMachine<>(initial, table, stateType);
        }
    }

    private final Map<S, Map<E, S>> table;
    private final List<Transition<S, E>> history = new ArrayList<>();
    private final Class<S> stateType;
    private S current;
    private long enteredAt;

    private StateMachine(S initial, Map<S, Map<E, S>> table, Class<S> stateType) {
        this.current = initial;
        this.table = table;
        this.stateType = stateType;
        this.enteredAt = System.currentTimeMillis();
    }

    public S state() {
        return current;
    }

    public synchronized S fire(E event, String reason) {
        Map<E, S> row = table.get(current);
        S next = row == null ? null : row.get(event);
        if (next == null) throw new IllegalStateException("no transition from " + current + " on " + event);
        long now = System.currentTimeMillis();
        history.add(new Transition<>(current, event, next, reason, now, now - enteredAt));
        current = next;
        enteredAt = now;
        return next;
    }

    public synchronized List<Transition<S, E>> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** The full table as (from, event, to) rows, in state order. */
    public List<Transition<S, E>> definition() {
        List<Transition<S, E>> rows = new ArrayList<>();
        for (S s : stateType.getEnumConstants()) {
            Map<E, S> row = table.get(s);
            if (row == null) continue;
            for (Map.Entry<E, S> e : row.entrySet()) rows.add(new Transition<>(s, e.getKey(), e.getValue(), "", 0, 0));
        }
        return rows;
    }

    /** The repair agent's design, in one place. */
    public static StateMachine<RepairState, RepairEvent> repairAgent() {
        return new Builder<>(RepairState.class, RepairEvent.class, RepairState.INGEST)
                .on(RepairState.INGEST, RepairEvent.READY, RepairState.LOCALIZE)
                .on(RepairState.INGEST, RepairEvent.NOTHING_TO_FIX, RepairState.UNRESOLVED)
                .on(RepairState.LOCALIZE, RepairEvent.SUSPECTS_FOUND, RepairState.SYNTHESIZE)
                .on(RepairState.LOCALIZE, RepairEvent.NO_SUSPECTS, RepairState.UNRESOLVED)
                .on(RepairState.SYNTHESIZE, RepairEvent.CANDIDATES_READY, RepairState.VERIFY)
                .on(RepairState.SYNTHESIZE, RepairEvent.NO_CANDIDATES, RepairState.CRITIC)
                .on(RepairState.VERIFY, RepairEvent.ALL_TESTS_PASS, RepairState.PUBLISH)
                .on(RepairState.VERIFY, RepairEvent.STILL_FAILING, RepairState.CRITIC)
                .on(RepairState.CRITIC, RepairEvent.RETRY, RepairState.SYNTHESIZE)
                .on(RepairState.CRITIC, RepairEvent.OUT_OF_BUDGET, RepairState.UNRESOLVED)
                .on(RepairState.PUBLISH, RepairEvent.PUBLISHED, RepairState.RESOLVED)
                .on(RepairState.PUBLISH, RepairEvent.PUBLISH_FAILED, RepairState.UNRESOLVED)
                // an unexpected failure (I/O error, model API down) ends the run from any working state
                .on(RepairState.INGEST, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .on(RepairState.LOCALIZE, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .on(RepairState.SYNTHESIZE, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .on(RepairState.VERIFY, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .on(RepairState.CRITIC, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .on(RepairState.PUBLISH, RepairEvent.ERROR, RepairState.UNRESOLVED)
                .build();
    }
}

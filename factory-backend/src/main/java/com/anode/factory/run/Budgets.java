package com.anode.factory.run;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a run may be dispatched now. A run reserves its budget when claimed; a finished run counts
 * for its reported cost, or for its reservation when the cost is unknown (lost worker). Work held
 * back by a budget or by the pause stays queued: it continues once the budget allows again.
 */
@Service
public class Budgets {

    /** What is decided and spent now. */
    public record State(boolean paused, BigDecimal dailyUsd, BigDecimal workOrderUsd, BigDecimal spentTodayUsd,
                        String updatedBy, Instant updatedAt) {}

    private final FactoryStateRepository states;
    private final RunRepository runs;
    private final Clock clock;

    Budgets(FactoryStateRepository states, RunRepository runs, Clock clock) {
        this.states = states;
        this.runs = runs;
        this.clock = clock;
    }

    public State state() {
        FactoryState state = row();
        return new State(state.paused(), state.dailyUsd(), state.workOrderUsd(), spentToday(), state.updatedBy(),
                state.updatedAt());
    }

    boolean paused() {
        return row().paused();
    }

    /** Why a run reserving {@code reservation} cannot be dispatched for the work order now, if it cannot. */
    Optional<String> refusal(UUID workOrderId, BigDecimal reservation) {
        FactoryState state = row();
        if (state.paused()) {
            return Optional.of("factory paused");
        }
        if (exceeds(state.workOrderUsd(), runs.spentBy(workOrderId), reservation)) {
            return Optional.of("work-order budget of " + state.workOrderUsd() + " USD reached");
        }
        if (exceeds(state.dailyUsd(), spentToday(), reservation)) {
            return Optional.of("daily budget of " + state.dailyUsd() + " USD reached");
        }
        return Optional.empty();
    }

    @Transactional
    public State pause(boolean paused, String by) {
        row().pause(paused, by, clock.instant());
        return state();
    }

    @Transactional
    public State budgets(BigDecimal dailyUsd, BigDecimal workOrderUsd, String by) {
        row().budgets(dailyUsd, workOrderUsd, by, clock.instant());
        return state();
    }

    private static boolean exceeds(BigDecimal cap, BigDecimal spent, BigDecimal reservation) {
        return cap != null && spent.add(reservation).compareTo(cap) > 0;
    }

    /** Since midnight in the clock's zone. */
    private BigDecimal spentToday() {
        return runs.spentSince(LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant());
    }

    private FactoryState row() {
        return states.findById(FactoryState.ID).orElseThrow();
    }
}

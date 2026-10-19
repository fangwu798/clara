package com.clara.workflow.planner;

import com.clara.core.planner.IBudgetLedger;
import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.BudgetReservation;
import com.clara.dto.planner.BudgetSnapshot;
import com.clara.enums.planner.BudgetReservationStatusEnum;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算账本的内存实现：仅按运行（runId）维度管理预算。
 */
public final class InMemoryBudgetLedger implements IBudgetLedger {

    private static final String FIELD_RUN_ID = "runId";

    private final Map<String, RunAccount> runs = new LinkedHashMap<>();
    private final Map<String, BudgetReservation> reservations =
            new LinkedHashMap<>();

    @Override
    public synchronized void openRun(String runId, BudgetAmount limit) {
        requireId(runId, FIELD_RUN_ID);
        if (limit == null) {
            throw new IllegalArgumentException("limit must not be null");
        }
        RunAccount existing = runs.get(runId);
        if (existing != null && !existing.limit.equals(limit)) {
            invalid();
        }
        if (existing == null) {
            runs.put(runId, new RunAccount(limit));
        }
    }

    @Override
    public synchronized BudgetReservation reserve(String runId, String taskId, BudgetAmount maximum) {
        requireId(runId, FIELD_RUN_ID);
        requireId(taskId, "taskId");
        if (maximum == null) {
            throw new IllegalArgumentException("maximum must not be null");
        }
        RunAccount run = runs.get(runId);
        if (run == null) {
            invalid();
        }
        String reservationId = runId + ":" + taskId;
        BudgetReservation existing = reservations.get(reservationId);
        if (existing != null) {
            if (!existing.getMaximum().equals(maximum)) {
                invalid();
            }
            return existing;
        }
        ensureCapacity(run.limit, run.used, run.reserved, maximum);
        run.reserved = run.reserved.plus(maximum);
        BudgetReservation created = new BudgetReservation(reservationId, runId, taskId,
                maximum, null, BudgetReservationStatusEnum.RESERVED);
        reservations.put(reservationId, created);
        return created;
    }

    @Override
    public synchronized BudgetReservation settle(String reservationId, BudgetAmount actual) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatusEnum.SETTLED) {
            if (current.getActual() == null || !current.getActual().equals(actual)) {
                invalid();
            }
            return current;
        }
        if (current.getStatus() != BudgetReservationStatusEnum.RESERVED || actual == null) {
            invalid();
        }
        RunAccount run = runs.get(current.getRunId());
        run.reserved = run.reserved.minus(current.getMaximum());
        run.used = run.used.plus(actual);
        BudgetReservation settled = replacement(current, actual, BudgetReservationStatusEnum.SETTLED);
        reservations.put(reservationId, settled);
        if (run.limit.isExceededBy(run.used.plus(run.reserved))) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategoryEnum.RESOURCE_LIMIT);
        }
        return settled;
    }

    @Override
    public synchronized BudgetReservation release(String reservationId) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatusEnum.RELEASED) {
            return current;
        }
        if (current.getStatus() != BudgetReservationStatusEnum.RESERVED) {
            invalid();
        }
        RunAccount run = runs.get(current.getRunId());
        run.reserved = run.reserved.minus(current.getMaximum());
        BudgetReservation released = replacement(current, null, BudgetReservationStatusEnum.RELEASED);
        reservations.put(reservationId, released);
        return released;
    }

    @Override
    public synchronized BudgetReservation markUnknown(String reservationId) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatusEnum.UNKNOWN) {
            return current;
        }
        if (current.getStatus() != BudgetReservationStatusEnum.RESERVED) {
            invalid();
        }
        BudgetReservation unknown = replacement(current, null, BudgetReservationStatusEnum.UNKNOWN);
        reservations.put(reservationId, unknown);
        return unknown;
    }

    @Override
    public synchronized BudgetSnapshot runSnapshot(String runId) {
        requireId(runId, FIELD_RUN_ID);
        RunAccount run = runs.get(runId);
        if (run == null) {
            invalid();
        }
        return new BudgetSnapshot(run.limit, run.reserved, run.used);
    }

    private BudgetReservation required(String id) {
        requireId(id, "reservationId");
        BudgetReservation value = reservations.get(id);
        if (value == null) {
            invalid();
        }
        return value;
    }

    private static BudgetReservation replacement(BudgetReservation current, BudgetAmount actual,
            BudgetReservationStatusEnum status) {
        return new BudgetReservation(current.getReservationId(), current.getRunId(),
                current.getTaskId(), current.getMaximum(), actual, status);
    }

    private static void ensureCapacity(BudgetAmount limit, BudgetAmount used,
            BudgetAmount reserved, BudgetAmount requested) {
        if (limit.isExceededBy(used.plus(reserved).plus(requested))) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategoryEnum.RESOURCE_LIMIT);
        }
    }

    private static void invalid() {
        throw PlannerErrors.error(ErrorCodes.BUDGET_RESERVATION_INVALID, ErrorCategoryEnum.CONFLICT);
    }

    private static void requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class RunAccount {
        final BudgetAmount limit;
        BudgetAmount reserved = BudgetAmount.ZERO;
        BudgetAmount used = BudgetAmount.ZERO;

        RunAccount(BudgetAmount limit) {
            this.limit = limit;
        }
    }
}

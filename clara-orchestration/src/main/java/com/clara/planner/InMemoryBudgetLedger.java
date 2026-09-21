package com.clara.planner;

import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供预算账本的内存实现。
 */
public final class InMemoryBudgetLedger implements BudgetLedger {
    private final BudgetAmount tenantLimit;
    private final Map<String, RunAccount> runs = new LinkedHashMap<String, RunAccount>();
    private final Map<String, BudgetReservation> reservations =
            new LinkedHashMap<String, BudgetReservation>();
    private BudgetAmount tenantReserved = BudgetAmount.ZERO;
    private BudgetAmount tenantUsed = BudgetAmount.ZERO;

    public InMemoryBudgetLedger(BudgetAmount tenantLimit) {
        if (tenantLimit == null) { throw new IllegalArgumentException("tenantLimit must not be null"); }
        this.tenantLimit = tenantLimit;
    }
    public synchronized void openRun(String runId, BudgetAmount limit) {
        requireId(runId, "runId");
        if (limit == null || !tenantLimit.covers(limit)) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        RunAccount existing = runs.get(runId);
        if (existing != null && !existing.limit.equals(limit)) invalid();
        if (existing == null) runs.put(runId, new RunAccount(limit));
    }
    public synchronized BudgetReservation reserve(String runId, String taskId, BudgetAmount maximum) {
        requireId(runId, "runId"); requireId(taskId, "taskId");
        if (maximum == null) { throw new IllegalArgumentException("maximum must not be null"); }
        RunAccount run = runs.get(runId); if (run == null) invalid();
        String reservationId = runId + ":" + taskId;
        BudgetReservation existing = reservations.get(reservationId);
        if (existing != null) {
            if (!existing.getMaximum().equals(maximum)) invalid();
            return existing;
        }
        ensureCapacity(tenantLimit, tenantUsed, tenantReserved, maximum);
        ensureCapacity(run.limit, run.used, run.reserved, maximum);
        tenantReserved = tenantReserved.plus(maximum); run.reserved = run.reserved.plus(maximum);
        BudgetReservation created = new BudgetReservation(reservationId, runId, taskId,
                maximum, null, BudgetReservationStatus.RESERVED);
        reservations.put(reservationId, created); return created;
    }
    public synchronized BudgetReservation settle(String reservationId, BudgetAmount actual) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatus.SETTLED) {
            if (!current.getActual().equals(actual)) invalid(); return current;
        }
        if (current.getStatus() != BudgetReservationStatus.RESERVED || actual == null) invalid();
        RunAccount run = runs.get(current.getRunId());
        tenantReserved = tenantReserved.minus(current.getMaximum());
        run.reserved = run.reserved.minus(current.getMaximum());
        tenantUsed = tenantUsed.plus(actual); run.used = run.used.plus(actual);
        BudgetReservation settled = replacement(current, actual, BudgetReservationStatus.SETTLED);
        reservations.put(reservationId, settled);
        if (!tenantLimit.covers(tenantUsed.plus(tenantReserved))
                || !run.limit.covers(run.used.plus(run.reserved))) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        return settled;
    }
    public synchronized BudgetReservation release(String reservationId) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatus.RELEASED) return current;
        if (current.getStatus() != BudgetReservationStatus.RESERVED) invalid();
        RunAccount run = runs.get(current.getRunId());
        tenantReserved = tenantReserved.minus(current.getMaximum());
        run.reserved = run.reserved.minus(current.getMaximum());
        BudgetReservation released = replacement(current, null, BudgetReservationStatus.RELEASED);
        reservations.put(reservationId, released); return released;
    }
    public synchronized BudgetReservation markUnknown(String reservationId) {
        BudgetReservation current = required(reservationId);
        if (current.getStatus() == BudgetReservationStatus.UNKNOWN) return current;
        if (current.getStatus() != BudgetReservationStatus.RESERVED) invalid();
        BudgetReservation unknown = replacement(current, null, BudgetReservationStatus.UNKNOWN);
        reservations.put(reservationId, unknown); return unknown;
    }
    public synchronized BudgetSnapshot tenantSnapshot() {
        return new BudgetSnapshot(tenantLimit, tenantReserved, tenantUsed);
    }
    public synchronized BudgetSnapshot runSnapshot(String runId) {
        requireId(runId, "runId"); RunAccount run = runs.get(runId); if (run == null) invalid();
        return new BudgetSnapshot(run.limit, run.reserved, run.used);
    }
    private BudgetReservation required(String id) {
        requireId(id, "reservationId"); BudgetReservation value = reservations.get(id);
        if (value == null) invalid(); return value;
    }
    private static BudgetReservation replacement(BudgetReservation current, BudgetAmount actual,
            BudgetReservationStatus status) {
        return new BudgetReservation(current.getReservationId(), current.getRunId(),
                current.getTaskId(), current.getMaximum(), actual, status);
    }
    private static void ensureCapacity(BudgetAmount limit, BudgetAmount used,
            BudgetAmount reserved, BudgetAmount requested) {
        if (!limit.covers(used.plus(reserved).plus(requested))) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
    }
    private static void invalid() {
        throw PlannerErrors.error(ErrorCodes.BUDGET_RESERVATION_INVALID, ErrorCategory.CONFLICT);
    }
    private static void requireId(String value, String name) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(name + " must not be blank"); }
    }
    /**
     * 表示。
     */
    private static final class RunAccount {
        final BudgetAmount limit;
        BudgetAmount reserved = BudgetAmount.ZERO;
        BudgetAmount used = BudgetAmount.ZERO;
        RunAccount(BudgetAmount limit) { this.limit = limit; }
    }
}

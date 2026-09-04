package com.clara.planner;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.io.Closeable;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskPlanEngine implements Closeable {
    private final PlanValidator validator;
    private final BudgetLedger budgets;
    private final ExecutionLedger executions;
    private final ExecutorService schedulers;
    private final ExecutorService calls;

    public TaskPlanEngine(PlanValidator validator, BudgetLedger budgets,
            ExecutionLedger executions, int callThreads) {
        if (validator == null || budgets == null || executions == null || callThreads <= 0) {
            throw new IllegalArgumentException("engine dependencies are invalid");
        }
        this.validator = validator; this.budgets = budgets; this.executions = executions;
        this.schedulers = Executors.newFixedThreadPool(callThreads, new PlannerThreadFactory("schedule"));
        this.calls = Executors.newFixedThreadPool(callThreads, new PlannerThreadFactory());
    }

    public TaskPlanResult execute(TaskPlan draft, String runId, Map<String, Object> inputs) {
        TaskPlan plan = validator.validate(draft);
        budgets.openRun(runId, validator.getLimits().getTotalBudget());
        Map<String, Object> values = inputs == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(inputs);
        Map<String, TaskNodeStatus> statuses = new LinkedHashMap<String, TaskNodeStatus>();
        for (TaskNode task : plan.getTasks()) statuses.put(task.getTaskId(), TaskNodeStatus.PENDING);
        AtomicInteger steps = new AtomicInteger();
        long deadline = deadline(validator.getLimits().getTotalTimeoutMillis());
        while (!allSucceeded(statuses)) {
            List<TaskNode> ready = readyTasks(plan, statuses, validator.getLimits().getMaxParallelism());
            if (ready.isEmpty()) throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategory.VALIDATION);
            final Map<String, Object> batchValues = new LinkedHashMap<String, Object>(values);
            List<Future<TaskOutcome>> futures = new ArrayList<Future<TaskOutcome>>();
            List<BudgetReservation> reservations = new ArrayList<BudgetReservation>();
            try {
                for (TaskNode task : ready) {
                    statuses.put(task.getTaskId(), TaskNodeStatus.READY);
                    reservations.add(budgets.reserve(runId, task.getTaskId(), task.getMaximumBudget()));
                }
            } catch (RuntimeException failure) {
                for (BudgetReservation reservation : reservations) {
                    if (reservation.getStatus() == BudgetReservationStatus.RESERVED) {
                        budgets.release(reservation.getReservationId());
                    }
                }
                throw failure;
            }
            for (int index = 0; index < ready.size(); index++) {
                final TaskNode task = ready.get(index);
                final BudgetReservation reservation = reservations.get(index);
                futures.add(schedulers.submit(() -> executeTask(plan, runId, task,
                        reservation, batchValues, deadline, steps)));
            }
            RuntimeException deferred = null;
            for (int index = 0; index < futures.size(); index++) {
                TaskNode task = ready.get(index);
                try {
                    TaskOutcome outcome = futures.get(index).get();
                    applyResult(task, outcome.result, values);
                    statuses.put(task.getTaskId(), TaskNodeStatus.SUCCEEDED);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    statuses.put(task.getTaskId(), TaskNodeStatus.FAILED);
                    if (deferred == null) deferred = unknown();
                } catch (ExecutionException failure) {
                    statuses.put(task.getTaskId(), TaskNodeStatus.FAILED);
                    Throwable cause = failure.getCause();
                    if (deferred == null) deferred = cause instanceof RuntimeException
                            ? (RuntimeException) cause
                            : PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategory.INTERNAL);
                }
            }
            if (deferred != null) throw deferred;
        }
        return new TaskPlanResult(plan.withStatus(PlanStatus.SUCCEEDED), PlanStatus.SUCCEEDED,
                statuses, values, steps.get());
    }

    public void close() { schedulers.shutdownNow(); calls.shutdownNow(); }

    private TaskOutcome executeTask(TaskPlan plan, String runId, TaskNode task,
            BudgetReservation reservation, Map<String, Object> values, long deadline,
            AtomicInteger steps) {
        String executionKey = runId + ":" + task.getIdempotencyKey();
        ExecutionRecord record = executions.recordIntent(executionKey,
                plan.getPlanId(), plan.getPlanVersion(), task.getTaskId());
        if (record.getStatus() == ExecutionRecordStatus.SUCCEEDED) {
            if (reservation.getStatus() == BudgetReservationStatus.RESERVED) {
                budgets.settle(reservation.getReservationId(), record.getResult().getActualUsage());
            }
            return new TaskOutcome(record.getResult());
        }
        if (record.getStatus() == ExecutionRecordStatus.UNKNOWN) {
            if (reservation.getStatus() == BudgetReservationStatus.RESERVED) {
                budgets.markUnknown(reservation.getReservationId());
            }
            throw unknown();
        }
        Throwable lastFailure = null;
        for (int attempt = record.getAttempts() + 1;
                attempt <= task.getRetryPolicy().getMaxAttempts(); attempt++) {
            if (steps.incrementAndGet() > validator.getLimits().getMaxPlanSteps()) {
                budgets.release(reservation.getReservationId());
                throw PlannerErrors.error(ErrorCodes.PLAN_LIMIT_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
            }
            executions.beginAttempt(executionKey);
            TaskExecutionResult result;
            try {
                result = invoke(task, plan, attempt, executionKey, values, deadline);
            } catch (UnknownSideEffectException failure) {
                executions.markUnknown(executionKey, failure);
                budgets.markUnknown(reservation.getReservationId()); throw unknown();
            } catch (Exception failure) {
                lastFailure = failure; executions.fail(executionKey, failure);
                if (attempt < task.getRetryPolicy().getMaxAttempts()) delay(task.getRetryPolicy().getDelayMillis());
                continue;
            }
            if (result == null || result.getOutputs().size() != 1
                    || !result.getOutputs().containsKey(task.getOutputKey())) {
                UnknownSideEffectException failure = new UnknownSideEffectException("task result is invalid");
                executions.markUnknown(executionKey, failure);
                budgets.markUnknown(reservation.getReservationId()); throw unknown();
            }
            executions.succeed(executionKey, result);
            budgets.settle(reservation.getReservationId(), result.getActualUsage());
            return new TaskOutcome(result);
        }
        budgets.release(reservation.getReservationId());
        if (lastFailure instanceof RuntimeException) throw (RuntimeException) lastFailure;
        throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategory.INTERNAL);
    }

    private TaskExecutionResult invoke(final TaskNode task, final TaskPlan plan, final int attempt,
            final String executionKey,
            final Map<String, Object> values, long totalDeadline) throws Exception {
        long remaining = Math.max(0L, totalDeadline - System.currentTimeMillis());
        long timeout = Math.min(task.getTimeoutMillis(), remaining);
        if (timeout <= 0L) throw new UnknownSideEffectException("plan timeout");
        Future<TaskExecutionResult> future = calls.submit(() -> task.getHandler().execute(
                new TaskExecutionContext(plan.getPlanId(), plan.getPlanVersion(), task.getTaskId(),
                        attempt, executionKey, values)));
        try { return future.get(timeout, TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) {
            future.cancel(true); throw new UnknownSideEffectException("task timeout");
        } catch (InterruptedException failure) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new UnknownSideEffectException("task interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof UnknownSideEffectException) throw (UnknownSideEffectException) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }
    private static void applyResult(TaskNode task, TaskExecutionResult result, Map<String, Object> values) {
        if (result == null || !result.getOutputs().containsKey(task.getOutputKey())) {
            throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategory.VALIDATION);
        }
        values.putAll(result.getOutputs());
    }
    private static List<TaskNode> readyTasks(TaskPlan plan, Map<String, TaskNodeStatus> statuses,
            int maximum) {
        List<TaskNode> result = new ArrayList<TaskNode>();
        for (TaskNode task : plan.getTasks()) {
            if (statuses.get(task.getTaskId()) != TaskNodeStatus.PENDING) continue;
            boolean ready = true;
            for (String dependency : task.getDependencies()) {
                if (statuses.get(dependency) != TaskNodeStatus.SUCCEEDED) { ready = false; break; }
            }
            if (ready) {
                result.add(task);
                if (result.size() == maximum) break;
            }
        }
        return result;
    }
    private static boolean allSucceeded(Map<String, TaskNodeStatus> statuses) {
        for (TaskNodeStatus value : statuses.values()) if (value != TaskNodeStatus.SUCCEEDED) return false;
        return true;
    }
    private static long deadline(long timeout) {
        long now = System.currentTimeMillis(); return Long.MAX_VALUE - now < timeout ? Long.MAX_VALUE : now + timeout;
    }
    private static void delay(long millis) {
        if (millis == 0L) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw unknown(); }
    }
    private static ClaraException unknown() {
        return PlannerErrors.error(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, ErrorCategory.UNKNOWN_SIDE_EFFECT);
    }
    private static final class PlannerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        private final String role;
        private PlannerThreadFactory() { this("call"); }
        private PlannerThreadFactory(String role) { this.role = role; }
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-planner-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }
    }
    private static final class TaskOutcome {
        final TaskExecutionResult result;
        TaskOutcome(TaskExecutionResult result) { this.result = result; }
    }
}

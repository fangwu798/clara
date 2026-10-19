package com.clara.workflow.planner;

import com.clara.core.planner.IBudgetLedger;
import com.clara.core.planner.IExecutionLedger;
import com.clara.core.planner.ITaskPlanEngine;
import com.clara.dto.planner.BudgetReservation;
import com.clara.dto.planner.ExecutionRecord;
import com.clara.dto.planner.TaskExecutionContext;
import com.clara.dto.planner.TaskExecutionResult;
import com.clara.dto.planner.TaskNode;
import com.clara.dto.planner.TaskPlan;
import com.clara.dto.planner.TaskPlanResult;
import com.clara.enums.planner.BudgetReservationStatusEnum;
import com.clara.enums.planner.ExecutionRecordStatusEnum;
import com.clara.enums.planner.PlanStatusEnum;
import com.clara.enums.planner.TaskNodeStatusEnum;
import com.clara.exception.UnknownSideEffectException;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 校验并执行任务 DAG，统一处理依赖调度、预算预留、重试、超时与执行幂等。
 */
public final class DefaultTaskPlanEngine implements ITaskPlanEngine {

    private final PlanValidator validator;
    private final IBudgetLedger budgets;
    private final IExecutionLedger executions;
    private final ExecutorService schedulers;
    private final ExecutorService calls;

    public DefaultTaskPlanEngine(PlanValidator validator, IBudgetLedger budgets,
            IExecutionLedger executions, int callThreads) {
        if (validator == null || budgets == null || executions == null || callThreads <= 0) {
            throw new IllegalArgumentException("engine dependencies are invalid");
        }
        this.validator = validator;
        this.budgets = budgets;
        this.executions = executions;
        this.schedulers = Executors.newFixedThreadPool(callThreads, new PlannerThreadFactory("schedule"));
        this.calls = Executors.newFixedThreadPool(callThreads, new PlannerThreadFactory());
    }

    @Override
    public TaskPlanResult execute(TaskPlan draft, String runId, Map<String, Object> inputs) {
        TaskPlan plan = validator.validate(draft);
        budgets.openRun(runId, validator.getLimits().getTotalBudget());
        Map<String, Object> values = inputs == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(inputs);
        Map<String, TaskNodeStatusEnum> statuses = new LinkedHashMap<>();
        for (TaskNode task : plan.getTasks()) {
            statuses.put(task.getTaskId(), TaskNodeStatusEnum.PENDING);
        }
        AtomicInteger steps = new AtomicInteger();
        long deadline = deadline(validator.getLimits().getTotalTimeoutMillis());
        while (!allSucceeded(statuses)) {
            List<TaskNode> ready = readyTasks(plan, statuses, validator.getLimits().getMaxParallelism());
            if (ready.isEmpty()) {
                throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategoryEnum.VALIDATION);
            }
            final Map<String, Object> batchValues = new LinkedHashMap<>(values);
            List<Future<TaskOutcome>> futures = new ArrayList<>();
            List<BudgetReservation> reservations = new ArrayList<>();
            try {
                for (TaskNode task : ready) {
                    statuses.put(task.getTaskId(), TaskNodeStatusEnum.READY);
                    reservations.add(budgets.reserve(runId, task.getTaskId(), task.getBudget()));
                }
            } catch (RuntimeException failure) {
                for (BudgetReservation reservation : reservations) {
                    if (reservation.getStatus() == BudgetReservationStatusEnum.RESERVED) {
                        budgets.release(reservation.getReservationId());
                    }
                }
                throw failure;
            }
            for (int index = 0; index < ready.size(); index++) {
                final TaskNode task = ready.get(index);
                final BudgetReservation reservation = reservations.get(index);
                futures.add(schedulers.submit(new Callable<TaskOutcome>() {
                    @Override
                    public TaskOutcome call() {
                        return executeTask(plan, runId, task, reservation, batchValues, deadline, steps);
                    }
                }));
            }
            RuntimeException deferred = null;
            for (int index = 0; index < futures.size(); index++) {
                TaskNode task = ready.get(index);
                try {
                    TaskOutcome outcome = futures.get(index).get();
                    applyResult(task, outcome.result, values);
                    statuses.put(task.getTaskId(), TaskNodeStatusEnum.SUCCEEDED);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    statuses.put(task.getTaskId(), TaskNodeStatusEnum.FAILED);
                    if (deferred == null) {
                        deferred = unknown();
                    }
                } catch (ExecutionException failure) {
                    statuses.put(task.getTaskId(), TaskNodeStatusEnum.FAILED);
                    Throwable cause = failure.getCause();
                    if (deferred == null) {
                        deferred = cause instanceof RuntimeException
                                ? (RuntimeException) cause
                                : PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategoryEnum.INTERNAL);
                    }
                }
            }
            if (deferred != null) {
                throw deferred;
            }
        }
        TaskPlan completed = new TaskPlan(plan.getGoal(), plan.getVersion(), plan.getTasks(),
                plan.getLimits(), PlanStatusEnum.SUCCEEDED);
        return new TaskPlanResult(completed, PlanStatusEnum.SUCCEEDED, statuses, values, steps.get());
    }

    @Override
    public void close() {
        schedulers.shutdownNow();
        calls.shutdownNow();
    }

    private TaskOutcome executeTask(TaskPlan plan, String runId, TaskNode task,
            BudgetReservation reservation, Map<String, Object> values, long deadline,
            AtomicInteger steps) {
        String executionKey = runId + ":" + task.getTaskId();
        ExecutionRecord record = executions.recordIntent(executionKey, plan.getGoal(),
                plan.getVersion(), task.getTaskId());
        if (record.getStatus() == ExecutionRecordStatusEnum.SUCCEEDED) {
            if (reservation.getStatus() == BudgetReservationStatusEnum.RESERVED) {
                budgets.settle(reservation.getReservationId(), record.getResult().getActualBudget());
            }
            return new TaskOutcome(record.getResult());
        }
        if (record.getStatus() == ExecutionRecordStatusEnum.UNKNOWN) {
            if (reservation.getStatus() == BudgetReservationStatusEnum.RESERVED) {
                budgets.markUnknown(reservation.getReservationId());
            }
            throw unknown();
        }
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= task.getRetryPolicy().getMaxAttempts(); attempt++) {
            if (steps.incrementAndGet() > validator.getLimits().getMaxPlanSteps()) {
                budgets.release(reservation.getReservationId());
                throw PlannerErrors.error(ErrorCodes.PLAN_LIMIT_EXCEEDED, ErrorCategoryEnum.RESOURCE_LIMIT);
            }
            executions.beginAttempt(executionKey);
            TaskExecutionResult result;
            try {
                result = invoke(task, plan, attempt, executionKey, values, deadline);
            } catch (UnknownSideEffectException failure) {
                executions.markUnknown(executionKey, failure);
                budgets.markUnknown(reservation.getReservationId());
                throw unknown();
            } catch (Exception failure) {
                lastFailure = failure;
                executions.fail(executionKey, failure);
                if (attempt < task.getRetryPolicy().getMaxAttempts()) {
                    delay(task.getRetryPolicy().getDelayMillis());
                }
                continue;
            }
            if (result == null || !result.getOutputs().containsKey(task.getOutputKey())) {
                UnknownSideEffectException failure = new UnknownSideEffectException("task result is invalid");
                executions.markUnknown(executionKey, failure);
                budgets.markUnknown(reservation.getReservationId());
                throw unknown();
            }
            executions.succeed(executionKey, result);
            budgets.settle(reservation.getReservationId(), result.getActualBudget());
            return new TaskOutcome(result);
        }
        budgets.release(reservation.getReservationId());
        if (lastFailure instanceof RuntimeException) {
            throw (RuntimeException) lastFailure;
        }
        throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategoryEnum.INTERNAL);
    }

    private TaskExecutionResult invoke(final TaskNode task, final TaskPlan plan, final int attempt,
            final String executionKey, final Map<String, Object> values, long totalDeadline)
            throws Exception {
        long remaining = Math.max(0L, totalDeadline - System.currentTimeMillis());
        long timeout = Math.min(task.getTimeoutMillis(), remaining);
        if (timeout <= 0L) {
            throw new UnknownSideEffectException("plan timeout");
        }
        Future<TaskExecutionResult> future = calls.submit(new Callable<TaskExecutionResult>() {
            @Override
            public TaskExecutionResult call() throws Exception {
                return task.getHandler().execute(
                        new TaskExecutionContext(plan, attempt, executionKey, values));
            }
        });
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new UnknownSideEffectException("task timeout");
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownSideEffectException("task interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof UnknownSideEffectException) {
                throw (UnknownSideEffectException) cause;
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void applyResult(TaskNode task, TaskExecutionResult result, Map<String, Object> values) {
        if (result == null || !result.getOutputs().containsKey(task.getOutputKey())) {
            throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategoryEnum.VALIDATION);
        }
        values.putAll(result.getOutputs());
    }

    private static List<TaskNode> readyTasks(TaskPlan plan, Map<String, TaskNodeStatusEnum> statuses,
            int maximum) {
        List<TaskNode> result = new ArrayList<>();
        for (TaskNode task : plan.getTasks()) {
            if (statuses.get(task.getTaskId()) != TaskNodeStatusEnum.PENDING) {
                continue;
            }
            boolean ready = true;
            for (String dependency : task.getDependencies()) {
                if (statuses.get(dependency) != TaskNodeStatusEnum.SUCCEEDED) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                result.add(task);
                if (result.size() == maximum) {
                    break;
                }
            }
        }
        return result;
    }

    private static boolean allSucceeded(Map<String, TaskNodeStatusEnum> statuses) {
        for (TaskNodeStatusEnum value : statuses.values()) {
            if (value != TaskNodeStatusEnum.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    private static long deadline(long timeout) {
        long now = System.currentTimeMillis();
        return Long.MAX_VALUE - now < timeout ? Long.MAX_VALUE : now + timeout;
    }

    private static void delay(long millis) {
        if (millis == 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unknown();
        }
    }

    private static ClaraException unknown() {
        return PlannerErrors.error(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, ErrorCategoryEnum.UNKNOWN_SIDE_EFFECT);
    }

    /**
     * 负责创建规划器线程实例。
     */
    private static final class PlannerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        private final String role;

        private PlannerThreadFactory() {
            this("call");
        }

        private PlannerThreadFactory(String role) {
            this.role = role;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-planner-" + role + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 表示任务的处理结果。
     */
    private static final class TaskOutcome {
        final TaskExecutionResult result;

        TaskOutcome(TaskExecutionResult result) {
            this.result = result;
        }
    }
}

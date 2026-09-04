package com.clara.planner;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TaskPlanEngineTest {
    private TaskPlanEngine engine;

    @After public void tearDown() { if (engine != null) engine.close(); }

    @Test
    public void executesDagRetriesAndReplaysSuccessfulIdempotentResults() {
        final AtomicInteger firstCalls = new AtomicInteger();
        final AtomicInteger secondCalls = new AtomicInteger();
        TaskNode first = node("first", Collections.<String>emptySet(), "firstOut",
                new RetryPolicy(2, 0L), new TaskHandler() {
                    public TaskExecutionResult execute(TaskExecutionContext context) {
                        if (firstCalls.incrementAndGet() == 1) throw new IllegalStateException("transient");
                        return result("firstOut", 4);
                    }
                });
        TaskNode second = node("second", Collections.singleton("first"), "secondOut",
                RetryPolicy.NONE, new TaskHandler() {
                    public TaskExecutionResult execute(TaskExecutionContext context) {
                        secondCalls.incrementAndGet(); return result("secondOut", ((Integer) context.get("firstOut")) + 1);
                    }
                });
        InMemoryBudgetLedger budgets = new InMemoryBudgetLedger(new BudgetAmount(100, 0, 0));
        InMemoryExecutionLedger executions = new InMemoryExecutionLedger();
        engine = new TaskPlanEngine(PlanValidatorTest.validator(new BudgetAmount(100, 0, 0),
                PlanValidatorTest.allowAll()), budgets, executions, 2);
        TaskPlan plan = PlanValidatorTest.plan(1, first, second);

        TaskPlanResult completed = engine.execute(plan, "run", Collections.<String, Object>emptyMap());
        assertEquals(PlanStatus.SUCCEEDED, completed.getStatus());
        assertEquals(5, completed.getOutputs().get("secondOut"));
        assertEquals(2, firstCalls.get()); assertEquals(1, secondCalls.get());

        TaskPlanResult replayed = engine.execute(plan, "run", Collections.<String, Object>emptyMap());
        assertEquals(5, replayed.getOutputs().get("secondOut"));
        assertEquals(2, firstCalls.get()); assertEquals(1, secondCalls.get());

        engine.execute(plan, "other-run", Collections.<String, Object>emptyMap());
        assertEquals(3, firstCalls.get()); assertEquals(2, secondCalls.get());
    }

    @Test
    public void timeoutMarksExecutionAndBudgetUnknown() {
        TaskNode slow = TaskNode.builder().taskId("slow").description("slow").outputKey("out")
                .idempotencyKey("slow-key").timeoutMillis(20L).maximumBudget(new BudgetAmount(10, 0, 0))
                .handler(new TaskHandler() {
                    public TaskExecutionResult execute(TaskExecutionContext context) throws Exception {
                        Thread.sleep(1000L); return result("out", true);
                    }
                }).build();
        InMemoryBudgetLedger budgets = new InMemoryBudgetLedger(new BudgetAmount(100, 0, 0));
        InMemoryExecutionLedger executions = new InMemoryExecutionLedger();
        engine = new TaskPlanEngine(PlanValidatorTest.validator(new BudgetAmount(100, 0, 0),
                PlanValidatorTest.allowAll()), budgets, executions, 1);
        try { engine.execute(PlanValidatorTest.plan(1, slow), "timeout", null); fail("expected timeout"); }
        catch (ClaraException exception) {
            assertEquals(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, exception.getError().getCode());
        }
        assertEquals(ExecutionRecordStatus.UNKNOWN, executions.find("timeout:slow-key").get().getStatus());
        assertEquals(new BudgetAmount(10, 0, 0), budgets.runSnapshot("timeout").getReserved());
    }

    @Test
    public void executesIndependentReadyTasksInParallel() throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        TaskNode left = node("left", Collections.<String>emptySet(), "leftOut", RetryPolicy.NONE,
                blocking(entered, release, "leftOut"));
        TaskNode right = node("right", Collections.<String>emptySet(), "rightOut", RetryPolicy.NONE,
                blocking(entered, release, "rightOut"));
        engine = new TaskPlanEngine(PlanValidatorTest.validator(new BudgetAmount(100, 0, 0),
                PlanValidatorTest.allowAll()), new InMemoryBudgetLedger(new BudgetAmount(100, 0, 0)),
                new InMemoryExecutionLedger(), 2);
        final TaskPlanResult[] result = new TaskPlanResult[1];
        Thread runner = new Thread(new Runnable() {
            public void run() { result[0] = engine.execute(PlanValidatorTest.plan(1, left, right), "parallel", null); }
        });
        runner.start(); assertEquals(true, entered.await(1L, TimeUnit.SECONDS));
        release.countDown(); runner.join(2000L);
        assertEquals(PlanStatus.SUCCEEDED, result[0].getStatus());
        assertEquals(Boolean.TRUE, result[0].getOutputs().get("leftOut"));
        assertEquals(Boolean.TRUE, result[0].getOutputs().get("rightOut"));
    }

    private static TaskNode node(String id, java.util.Set<String> dependencies, String output,
            RetryPolicy retry, TaskHandler handler) {
        return TaskNode.builder().taskId(id).description(id).dependencies(dependencies).outputKey(output)
                .retryPolicy(retry).maximumBudget(new BudgetAmount(10, 0, 0))
                .idempotencyKey(id + "-key").handler(handler).build();
    }
    private static TaskExecutionResult result(String key, Object value) {
        Map<String, Object> outputs = new LinkedHashMap<String, Object>(); outputs.put(key, value);
        return new TaskExecutionResult(outputs, new BudgetAmount(1, 0, 0));
    }
    private static TaskHandler blocking(final CountDownLatch entered,
            final CountDownLatch release, final String output) {
        return new TaskHandler() {
            public TaskExecutionResult execute(TaskExecutionContext context) throws Exception {
                entered.countDown(); release.await(1L, TimeUnit.SECONDS); return result(output, true);
            }
        };
    }
}

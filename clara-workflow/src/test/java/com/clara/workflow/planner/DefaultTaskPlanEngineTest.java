package com.clara.workflow.planner;

import com.clara.core.planner.ICapabilityAuthorizer;
import com.clara.core.planner.ITaskHandler;
import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.BudgetSnapshot;
import com.clara.dto.planner.PlanLimits;
import com.clara.dto.planner.RetryPolicy;
import com.clara.dto.planner.TaskExecutionResult;
import com.clara.dto.planner.TaskNode;
import com.clara.dto.planner.TaskPlan;
import com.clara.dto.planner.TaskPlanResult;
import com.clara.enums.planner.PlanStatusEnum;
import com.clara.exception.UnknownSideEffectException;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * {@link DefaultTaskPlanEngine} 的单元测试：依赖拓扑调度、执行幂等、重试、超时 / 未知副作用与预算预留结算。
 */
public class DefaultTaskPlanEngineTest {

    private static final ICapabilityAuthorizer ALLOW_ALL = capability -> true;
    private static final PlanLimits LIMITS = new PlanLimits(10, 100, 5, 4, 60000L,
            new BudgetAmount(100000L, 100000L, 100000L));

    private InMemoryBudgetLedger budgets;
    private InMemoryExecutionLedger executions;
    private DefaultTaskPlanEngine engine;

    @Before
    public void setUp() {
        budgets = new InMemoryBudgetLedger();
        executions = new InMemoryExecutionLedger();
        engine = new DefaultTaskPlanEngine(new PlanValidator(LIMITS, ALLOW_ALL), budgets, executions, 2);
    }

    @After
    public void tearDown() {
        engine.close();
    }

    private static TaskNode node(String id, List<String> dependencies, String outputKey,
            RetryPolicy retryPolicy, long timeoutMillis, ITaskHandler handler) {
        return new TaskNode(id, dependencies, "cap." + id, outputKey, retryPolicy, timeoutMillis,
                new BudgetAmount(10L, 10L, 10L), handler);
    }

    private static ITaskHandler returning(final String outputKey, final Object value,
            final BudgetAmount actual) {
        return context -> new TaskExecutionResult(
                Collections.<String, Object>singletonMap(outputKey, value), actual);
    }

    private static TaskPlan plan(TaskNode... tasks) {
        return new TaskPlan("goal", 0, Arrays.asList(tasks), LIMITS, PlanStatusEnum.DRAFT);
    }

    @Test
    public void executesTasksFollowingDependencies() {
        final List<String> order = new ArrayList<String>();
        TaskNode a = node("A", null, "outA", RetryPolicy.NONE, 1000L, context -> {
            order.add("A");
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outA", "va"),
                    BudgetAmount.ZERO);
        });
        TaskNode b = node("B", Collections.singletonList("A"), "outB", RetryPolicy.NONE, 1000L, context -> {
            order.add("B");
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outB", "vb"),
                    BudgetAmount.ZERO);
        });
        TaskNode c = node("C", Collections.singletonList("B"), "outC", RetryPolicy.NONE, 1000L, context -> {
            order.add("C");
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outC", "vc"),
                    BudgetAmount.ZERO);
        });

        TaskPlanResult result = engine.execute(plan(a, b, c), "run-topo", null);

        assertEquals(PlanStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(Arrays.asList("A", "B", "C"), order);
        assertEquals("va", result.getOutputs().get("outA"));
        assertEquals("vc", result.getOutputs().get("outC"));
    }

    @Test
    public void reusesSuccessfulResultByExecutionKey() {
        AtomicInteger calls = new AtomicInteger();
        TaskNode task = node("T", null, "outT", RetryPolicy.NONE, 1000L, context -> {
            calls.incrementAndGet();
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outT", "value"),
                    BudgetAmount.ZERO);
        });
        TaskPlan plan = plan(task);

        engine.execute(plan, "run-idem", null);
        TaskPlanResult second = engine.execute(plan, "run-idem", null);

        assertEquals(PlanStatusEnum.SUCCEEDED, second.getStatus());
        assertEquals(1, calls.get());
    }

    @Test
    public void retriesFailedTaskAccordingToPolicy() {
        AtomicInteger attempts = new AtomicInteger();
        TaskNode task = node("T", null, "outT", new RetryPolicy(3, 0L), 1000L, context -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outT", "ok"),
                    BudgetAmount.ZERO);
        });

        TaskPlanResult result = engine.execute(plan(task), "run-retry", null);

        assertEquals(PlanStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(3, attempts.get());
    }

    @Test
    public void unknownSideEffectIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        TaskNode task = node("T", null, "outT", new RetryPolicy(5, 0L), 1000L, context -> {
            attempts.incrementAndGet();
            throw new UnknownSideEffectException("uncertain side effect");
        });

        ClaraException failure = assertThrows(ClaraException.class,
                () -> engine.execute(plan(task), "run-unknown", null));

        assertEquals(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, failure.getError().getCode());
        assertEquals(1, attempts.get());
    }

    @Test
    public void timeoutIsTreatedAsUnknownSideEffect() {
        TaskNode task = node("T", null, "outT", new RetryPolicy(3, 0L), 50L, context -> {
            Thread.sleep(500L);
            return new TaskExecutionResult(Collections.<String, Object>singletonMap("outT", "late"),
                    BudgetAmount.ZERO);
        });

        ClaraException failure = assertThrows(ClaraException.class,
                () -> engine.execute(plan(task), "run-timeout", null));

        assertEquals(ErrorCodes.EXECUTION_SIDE_EFFECT_UNKNOWN, failure.getError().getCode());
    }

    @Test
    public void reservesAndSettlesBudget() {
        TaskNode task = node("T", null, "outT", RetryPolicy.NONE, 1000L,
                returning("outT", "value", new BudgetAmount(30L, 0L, 0L)));

        engine.execute(plan(task), "run-budget", null);

        BudgetSnapshot snapshot = budgets.runSnapshot("run-budget");
        assertEquals(new BudgetAmount(30L, 0L, 0L), snapshot.getUsed());
        assertEquals(BudgetAmount.ZERO, snapshot.getReserved());
        assertEquals(LIMITS.getTotalBudget(), snapshot.getLimit());
    }
}

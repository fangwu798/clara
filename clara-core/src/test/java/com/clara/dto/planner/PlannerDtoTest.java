package com.clara.dto.planner;

import com.clara.core.planner.ITaskHandler;
import com.clara.enums.planner.BudgetReservationStatusEnum;
import com.clara.enums.planner.ExecutionRecordStatusEnum;
import com.clara.enums.planner.PlanStatusEnum;
import com.clara.enums.planner.TaskNodeStatusEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 计划 DTO：预算运算、步骤渲染、任务节点校验与计划聚合。
 */
public class PlannerDtoTest {

    /**
     * 预算的加、减与超限判断，包含溢出与下溢保护。
     */
    @Test
    public void computesBudget() {
        BudgetAmount one = new BudgetAmount(1L, 2L, 3L);
        BudgetAmount two = new BudgetAmount(10L, 20L, 30L);

        assertEquals(new BudgetAmount(11L, 22L, 33L), one.plus(two));
        assertEquals(new BudgetAmount(9L, 18L, 27L), two.minus(one));
        assertEquals(1L, one.getTokens());
        assertEquals(2L, one.getCostMicros());
        assertEquals(3L, one.getWorkUnits());
        assertTrue(one.isExceededBy(two));
        assertFalse(two.isExceededBy(one));
        assertEquals(BudgetAmount.ZERO, BudgetAmount.ZERO.plus(BudgetAmount.ZERO));
        assertEquals(one, new BudgetAmount(1L, 2L, 3L));
        assertFalse(one.equals("x"));
        assertEquals(one.hashCode(), new BudgetAmount(1L, 2L, 3L).hashCode());

        assertThrows(IllegalArgumentException.class, () -> new BudgetAmount(-1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> one.plus(null));
        assertThrows(IllegalArgumentException.class, () -> one.minus(null));
        assertThrows(IllegalArgumentException.class, () -> one.isExceededBy(null));
        assertThrows(IllegalArgumentException.class, () -> one.minus(two));
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetAmount(Long.MAX_VALUE, 0L, 0L).plus(new BudgetAmount(1L, 0L, 0L)));
    }

    /**
     * 重试策略的边界校验。
     */
    @Test
    public void validatesRetryPolicy() {
        RetryPolicy policy = new RetryPolicy(3, 100L);
        assertEquals(3, policy.getMaxAttempts());
        assertEquals(100L, policy.getDelayMillis());
        assertEquals(1, RetryPolicy.NONE.getMaxAttempts());
        assertEquals(0L, RetryPolicy.NONE.getDelayMillis());
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 0L));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1L));
    }

    /**
     * 执行步骤的默认值与计划文本渲染。
     */
    @Test
    public void rendersExecutionPlan() {
        ExecutionStep first = new ExecutionStep(" s1 ", " 分析 ", "  ", null, "  ");
        assertEquals("s1", first.getId());
        assertEquals("分析", first.getTitle());
        assertEquals("分析", first.getDescription());
        assertTrue(first.getDependencies().isEmpty());
        assertEquals("", first.getExpectedOutput());
        assertThrows(UnsupportedOperationException.class, () -> first.getDependencies().add("x"));

        ExecutionStep second = new ExecutionStep("s2", "实现", "写代码",
                Arrays.asList("s1"), "补丁");
        assertEquals(Arrays.asList("s1"), second.getDependencies());

        assertThrows(IllegalArgumentException.class, () -> new ExecutionStep(" ", "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionStep("id", " ", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionStep("id", "t", null, Collections.singletonList(" "), null));

        ExecutionPlan plan = new ExecutionPlan(" 目标 ", Arrays.asList(first, second));
        assertEquals("目标", plan.getGoal());
        assertEquals(2, plan.getSteps().size());
        String prompt = plan.toPrompt();
        assertTrue(prompt.contains("本轮执行计划"));
        assertTrue(prompt.contains("[s1] 分析"));
        assertTrue(prompt.contains("依赖：s1"));
        assertTrue(prompt.contains("预期产出：补丁"));
        assertTrue(prompt.contains("依赖：无"));

        assertThrows(IllegalArgumentException.class, () -> new ExecutionPlan(" ", Collections.singletonList(first)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPlan("g", null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPlan("g", Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionPlan("g", Arrays.asList(first, new ExecutionStep("s1", "重复", null, null, null))));
    }

    /**
     * 任务节点构建：默认无依赖，字段可读，依赖列表不可变。
     */
    @Test
    public void buildsTaskNode() {
        ITaskHandler handler = context -> new TaskExecutionResult(Collections.singletonMap("ok", true), BudgetAmount.ZERO);
        TaskNode node = TaskNode.builder("t1", "text_search", "result")
                .dependencies(Collections.singletonList("t0"))
                .retryPolicy(new RetryPolicy(2, 10L))
                .timeoutMillis(1000L)
                .budget(new BudgetAmount(10L, 20L, 30L))
                .handler(handler)
                .build();

        assertEquals("t1", node.getTaskId());
        assertEquals("text_search", node.getCapability());
        assertEquals("result", node.getOutputKey());
        assertEquals(Collections.singletonList("t0"), node.getDependencies());
        assertEquals(2, node.getRetryPolicy().getMaxAttempts());
        assertEquals(1000L, node.getTimeoutMillis());
        assertEquals(30L, node.getBudget().getWorkUnits());
        assertEquals(handler, node.getHandler());
        assertThrows(UnsupportedOperationException.class, () -> node.getDependencies().add("x"));
    }

    /**
     * 任务节点缺少必填项时拒绝构建。
     */
    @Test
    public void rejectsInvalidTaskNode() {
        ITaskHandler handler = context -> new TaskExecutionResult(Collections.emptyMap(), BudgetAmount.ZERO);
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder(" ", "c", "o").build());
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder("t", "", "o").build());
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder("t", "c", " ").build());
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder("t", "c", "o").build());
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder("t", "c", "o")
                .retryPolicy(RetryPolicy.NONE).timeoutMillis(0L).budget(BudgetAmount.ZERO).handler(handler).build());
        assertThrows(IllegalArgumentException.class, () -> TaskNode.builder("t", "c", "o")
                .retryPolicy(RetryPolicy.NONE).timeoutMillis(1L).budget(BudgetAmount.ZERO).handler(handler)
                .dependencies(Collections.singletonList(" ")).build());
    }

    /**
     * 计划限制与任务计划的聚合校验。
     */
    @Test
    public void aggregatesTaskPlan() {
        PlanLimits limits = new PlanLimits(5, 10, 2, 2, 1000L, new BudgetAmount(100L, 0L, 0L));
        assertEquals(5, limits.getMaxTasks());
        assertEquals(10, limits.getMaxPlanSteps());
        assertEquals(2, limits.getMaxRePlans());
        assertEquals(2, limits.getMaxParallelism());
        assertEquals(1000L, limits.getTotalTimeoutMillis());
        assertEquals(100L, limits.getTotalBudget().getTokens());

        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(0, 1, 0, 1, 1L, BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(1, 0, 0, 1, 1L, BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(1, 1, -1, 1, 1L, BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(1, 1, 0, 0, 1L, BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(1, 1, 0, 1, 0L, BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PlanLimits(1, 1, 0, 1, 1L, null));

        TaskNode node = task("t1");
        TaskPlan plan = new TaskPlan(" goal ", 1, Collections.singletonList(node), limits, PlanStatusEnum.VALIDATED);
        assertEquals(" goal ", plan.getGoal());
        assertEquals(1, plan.getVersion());
        assertEquals(1, plan.getTasks().size());
        assertEquals(limits, plan.getLimits());
        assertEquals(PlanStatusEnum.VALIDATED, plan.getStatus());
        assertThrows(UnsupportedOperationException.class, () -> plan.getTasks().add(node));

        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("", 1, Collections.singletonList(node), limits, PlanStatusEnum.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("g", -1, Collections.singletonList(node), limits, PlanStatusEnum.DRAFT));
        assertThrows(IllegalArgumentException.class, () -> new TaskPlan("g", 1, null, limits, PlanStatusEnum.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("g", 1, Collections.emptyList(), limits, PlanStatusEnum.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("g", 1, Collections.singletonList(node), null, PlanStatusEnum.DRAFT));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("g", 1, Collections.singletonList(node), limits, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskPlan("g", 1, Arrays.asList(node, task("t1")), limits, PlanStatusEnum.DRAFT));
    }

    /**
     * 执行上下文/结果/记录等值对象可读。
     */
    @Test
    public void exposesExecutionValueObjects() {
        TaskPlan plan = new TaskPlan("g", 1, Collections.singletonList(task("t1")),
                new PlanLimits(1, 1, 0, 1, 1L, BudgetAmount.ZERO), PlanStatusEnum.RUNNING);

        TaskExecutionContext context = new TaskExecutionContext(plan, 2, "key",
                Collections.singletonMap("k", "v"));
        assertEquals(plan, context.getPlan());
        assertEquals(2, context.getAttempt());
        assertEquals("key", context.getIdempotencyKey());
        assertEquals("v", context.getValues().get("k"));

        TaskExecutionResult result = new TaskExecutionResult(Collections.singletonMap("out", 1),
                new BudgetAmount(1L, 2L, 3L));
        assertEquals(1, result.getOutputs().get("out"));
        assertEquals(3L, result.getActualBudget().getWorkUnits());

        ExecutionRecord record = new ExecutionRecord("key", "p1", 1, "t1",
                ExecutionRecordStatusEnum.SUCCEEDED, result, null);
        assertEquals("key", record.getIdempotencyKey());
        assertEquals("p1", record.getPlanId());
        assertEquals(1, record.getPlanVersion());
        assertEquals("t1", record.getTaskId());
        assertEquals(ExecutionRecordStatusEnum.SUCCEEDED, record.getStatus());
        assertEquals(result, record.getResult());

        BudgetReservation reservation = new BudgetReservation("r1", "run", "t1",
                new BudgetAmount(10L, 0L, 0L), new BudgetAmount(5L, 0L, 0L),
                BudgetReservationStatusEnum.SETTLED);
        assertEquals("r1", reservation.getReservationId());
        assertEquals("run", reservation.getRunId());
        assertEquals("t1", reservation.getTaskId());
        assertEquals(10L, reservation.getMaximum().getTokens());
        assertEquals(5L, reservation.getActual().getTokens());
        assertEquals(BudgetReservationStatusEnum.SETTLED, reservation.getStatus());

        BudgetSnapshot snapshot = new BudgetSnapshot(new BudgetAmount(10L, 0L, 0L),
                new BudgetAmount(3L, 0L, 0L), new BudgetAmount(2L, 0L, 0L));
        assertEquals(10L, snapshot.getLimit().getTokens());
        assertEquals(3L, snapshot.getReserved().getTokens());
        assertEquals(2L, snapshot.getUsed().getTokens());

        Map<String, TaskNodeStatusEnum> statuses = new HashMap<>();
        statuses.put("t1", TaskNodeStatusEnum.SUCCEEDED);
        TaskPlanResult planResult = new TaskPlanResult(plan, PlanStatusEnum.SUCCEEDED, statuses,
                Collections.singletonMap("out", 1), 1);
        assertEquals(plan, planResult.getPlan());
        assertEquals(PlanStatusEnum.SUCCEEDED, planResult.getStatus());
        assertEquals(TaskNodeStatusEnum.SUCCEEDED, planResult.getTaskStatuses().get("t1"));
        assertEquals(1, planResult.getOutputs().get("out"));
        assertEquals(1, planResult.getSteps());
    }

    private static TaskNode task(String id) {
        ITaskHandler handler = context -> new TaskExecutionResult(Collections.emptyMap(), BudgetAmount.ZERO);
        return TaskNode.builder(id, "cap", "out")
                .retryPolicy(RetryPolicy.NONE)
                .timeoutMillis(1000L)
                .budget(BudgetAmount.ZERO)
                .handler(handler)
                .build();
    }
}
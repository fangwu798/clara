package com.clara.workflow.planner;

import com.clara.core.planner.ICapabilityAuthorizer;
import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.PlanLimits;
import com.clara.dto.planner.RetryPolicy;
import com.clara.dto.planner.TaskExecutionResult;
import com.clara.dto.planner.TaskNode;
import com.clara.dto.planner.TaskPlan;
import com.clara.enums.planner.PlanStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * {@link PlanValidator} 的单元测试：合法计划通过，输出键重复 / 依赖非法 / 有环 / 超限 / 能力未授权各自被拒。
 */
public class PlanValidatorTest {

    private static final ICapabilityAuthorizer ALLOW_ALL = capability -> true;
    private static final ICapabilityAuthorizer DENY_ALL = capability -> false;

    private static PlanLimits limits(int maxTasks, int maxPlanSteps, BudgetAmount totalBudget) {
        return new PlanLimits(maxTasks, maxPlanSteps, 3, 4, 60000L, totalBudget);
    }

    private static TaskNode task(String id, List<String> dependencies, String outputKey, BudgetAmount budget) {
        return TaskNode.builder(id, "cap." + id, outputKey)
                .dependencies(dependencies)
                .retryPolicy(RetryPolicy.NONE)
                .timeoutMillis(1000L)
                .budget(budget)
                .handler(context -> new TaskExecutionResult(Collections.singletonMap(outputKey, "v"),
                        BudgetAmount.ZERO))
                .build();
    }

    private static TaskPlan plan(List<TaskNode> tasks, PlanLimits limits) {
        return new TaskPlan("goal", 0, tasks, limits, PlanStatusEnum.DRAFT);
    }

    private static void assertRejected(String code, PlanValidator validator, TaskPlan plan) {
        ClaraException error = assertThrows(ClaraException.class, () -> validator.validate(plan));
        assertEquals(code, error.getError().getCode());
    }

    @Test
    public void acceptsValidPlan() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        TaskPlan plan = plan(Arrays.asList(
                task("A", null, "outA", new BudgetAmount(1L, 1L, 1L)),
                task("B", Collections.singletonList("A"), "outB", new BudgetAmount(1L, 1L, 1L))), limits);

        TaskPlan validated = validator.validate(plan);

        assertEquals(PlanStatusEnum.VALIDATED, validated.getStatus());
        assertEquals(2, validated.getTasks().size());
    }

    @Test
    public void rejectsDuplicateOutputKeys() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        TaskPlan plan = plan(Arrays.asList(
                task("A", null, "out", new BudgetAmount(1L, 1L, 1L)),
                task("B", null, "out", new BudgetAmount(1L, 1L, 1L))), limits);

        assertRejected(ErrorCodes.PLAN_INVALID, validator, plan);
    }

    @Test
    public void rejectsUnknownDependency() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        TaskPlan plan = plan(Collections.singletonList(
                task("B", Collections.singletonList("missing"), "outB", new BudgetAmount(1L, 1L, 1L))), limits);

        assertRejected(ErrorCodes.PLAN_INVALID, validator, plan);
    }

    @Test
    public void rejectsSelfDependency() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        TaskPlan plan = plan(Collections.singletonList(
                task("A", Collections.singletonList("A"), "outA", new BudgetAmount(1L, 1L, 1L))), limits);

        assertRejected(ErrorCodes.PLAN_INVALID, validator, plan);
    }

    @Test
    public void rejectsCyclicDependency() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        BudgetAmount budget = new BudgetAmount(1L, 1L, 1L);
        TaskPlan plan = plan(Arrays.asList(
                task("A", Collections.singletonList("B"), "outA", budget),
                task("B", Collections.singletonList("A"), "outB", budget)), limits);

        assertRejected(ErrorCodes.PLAN_INVALID, validator, plan);
    }

    @Test
    public void rejectsPlanExceedingTaskLimit() {
        PlanLimits limits = limits(2, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        BudgetAmount budget = new BudgetAmount(1L, 1L, 1L);
        TaskPlan plan = plan(Arrays.asList(
                task("A", null, "outA", budget),
                task("B", null, "outB", budget),
                task("C", null, "outC", budget)), limits);

        assertRejected(ErrorCodes.PLAN_LIMIT_EXCEEDED, validator, plan);
    }

    @Test
    public void rejectsUnauthorizedCapability() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(1000L, 1000L, 1000L));
        PlanValidator validator = new PlanValidator(limits, DENY_ALL);
        TaskPlan plan = plan(Collections.singletonList(
                task("A", null, "outA", new BudgetAmount(1L, 1L, 1L))), limits);

        assertRejected(ErrorCodes.PLAN_CAPABILITY_DENIED, validator, plan);
    }

    @Test
    public void rejectsBudgetOverflow() {
        PlanLimits limits = limits(5, 10, new BudgetAmount(10L, 10L, 10L));
        PlanValidator validator = new PlanValidator(limits, ALLOW_ALL);
        BudgetAmount budget = new BudgetAmount(6L, 6L, 6L);
        TaskPlan plan = plan(Arrays.asList(
                task("A", null, "outA", budget),
                task("B", null, "outB", budget)), limits);

        assertRejected(ErrorCodes.BUDGET_EXCEEDED, validator, plan);
    }
}

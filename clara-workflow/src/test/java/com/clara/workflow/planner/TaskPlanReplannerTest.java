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
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * {@link TaskPlanReplanner} 的单元测试：保持已完成任务输出键 / 能力一致，破坏一致性时被拒。
 */
public class TaskPlanReplannerTest {

    private static final ICapabilityAuthorizer ALLOW_ALL = capability -> true;

    private static PlanLimits limits() {
        return new PlanLimits(10, 20, 3, 4, 60000L, new BudgetAmount(1000L, 1000L, 1000L));
    }

    private static TaskPlanReplanner replanner() {
        return new TaskPlanReplanner(new PlanValidator(limits(), ALLOW_ALL));
    }

    private static TaskNode task(String id, String outputKey, String capability) {
        return TaskNode.builder(id, capability, outputKey)
                .retryPolicy(RetryPolicy.NONE)
                .timeoutMillis(1000L)
                .budget(new BudgetAmount(1L, 1L, 1L))
                .handler(context -> new TaskExecutionResult(Collections.singletonMap(outputKey, "v"),
                        BudgetAmount.ZERO))
                .build();
    }

    private static TaskPlan plan(int version, PlanStatusEnum status, List<TaskNode> tasks) {
        return new TaskPlan("goal", version, tasks, limits(), status);
    }

    private void assertReplanRejected(TaskPlan current, TaskPlan replacement, int priorReplans,
            Set<String> completed) {
        ClaraException error = assertThrows(ClaraException.class,
                () -> replanner().replan(current, replacement, priorReplans, completed));
        assertEquals(ErrorCodes.PLAN_RE_PLAN_INVALID, error.getError().getCode());
    }

    @Test
    public void acceptsConsistentReplan() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Arrays.asList(
                task("A", "outA", "cap.a"), task("B", "outB", "cap.b")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Arrays.asList(
                task("A", "outA", "cap.a"), task("B", "outB", "cap.b"), task("C", "outC", "cap.c")));

        TaskPlan result = replanner().replan(current, replacement, 0, Collections.singleton("A"));

        assertEquals(PlanStatusEnum.VALIDATED, result.getStatus());
        assertEquals(3, result.getTasks().size());
    }

    @Test
    public void rejectsChangedOutputKeyForCompletedTask() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA2", "cap.a")));

        assertReplanRejected(current, replacement, 0, Collections.singleton("A"));
    }

    @Test
    public void rejectsChangedCapabilityForCompletedTask() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.changed")));

        assertReplanRejected(current, replacement, 0, Collections.singleton("A"));
    }

    @Test
    public void rejectsMissingCompletedTask() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.a")));

        assertReplanRejected(current, replacement, 0, Collections.singleton("Z"));
    }

    @Test
    public void rejectsVersionMismatch() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(2, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.a")));

        assertReplanRejected(current, replacement, 0, Collections.singleton("A"));
    }

    @Test
    public void rejectsWhenReplanBudgetExhausted() {
        TaskPlan current = plan(0, PlanStatusEnum.RUNNING, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.a")));

        assertReplanRejected(current, replacement, 3, Collections.singleton("A"));
    }

    @Test
    public void rejectsNonRunningCurrentPlan() {
        TaskPlan current = plan(0, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.a")));
        TaskPlan replacement = plan(1, PlanStatusEnum.DRAFT, Collections.singletonList(
                task("A", "outA", "cap.a")));

        assertReplanRejected(current, replacement, 0, Collections.singleton("A"));
    }
}

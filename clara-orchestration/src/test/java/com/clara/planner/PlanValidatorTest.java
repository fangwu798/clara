package com.clara.planner;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PlanValidatorTest {
    @Test
    public void validatesDagAndRejectsCycles() {
        PlanValidator validator = validator(new BudgetAmount(100, 100, 100), allowAll());
        TaskNode first = task("first", Collections.<String>emptySet(), "one", "cap", new BudgetAmount(1, 0, 0));
        TaskNode second = task("second", Collections.singleton("first"), "two", "cap", new BudgetAmount(1, 0, 0));
        assertEquals(PlanStatus.VALIDATED, validator.validate(plan(1, first, second)).getStatus());

        TaskNode cyclicOne = task("first", Collections.singleton("second"), "one", "cap", BudgetAmount.ZERO);
        assertError(ErrorCodes.PLAN_INVALID, new Runnable() {
            public void run() { validator.validate(plan(1, cyclicOne, second)); }
        });
    }

    @Test
    public void rejectsDeniedCapabilitiesAndWorstCaseBudget() {
        PlanValidator denied = validator(new BudgetAmount(10, 0, 0), new CapabilityAuthorizer() {
            public boolean isAllowed(String capability) { return false; }
        });
        final TaskPlan deniedPlan = plan(1, task("one", Collections.<String>emptySet(),
                "out", "dangerous", BudgetAmount.ZERO));
        assertError(ErrorCodes.PLAN_CAPABILITY_DENIED, new Runnable() {
            public void run() { denied.validate(deniedPlan); }
        });

        final PlanValidator limited = validator(new BudgetAmount(1, 0, 0), allowAll());
        final TaskPlan expensive = plan(1, task("one", Collections.<String>emptySet(),
                "out", null, new BudgetAmount(2, 0, 0)));
        assertError(ErrorCodes.BUDGET_EXCEEDED, new Runnable() {
            public void run() { limited.validate(expensive); }
        });
    }

    @Test
    public void replanningIncrementsVersionAndPinsCompletedTaskIdentity() {
        PlanValidator validator = validator(new BudgetAmount(100, 0, 0), allowAll());
        TaskNode old = task("done", Collections.<String>emptySet(), "out", null, BudgetAmount.ZERO);
        TaskPlan current = plan(1, old).withStatus(PlanStatus.RUNNING);
        TaskNode retained = task("done", Collections.<String>emptySet(), "out", null, BudgetAmount.ZERO);
        TaskNode added = task("new", Collections.singleton("done"), "newOut", null, BudgetAmount.ZERO);
        TaskPlan replacement = plan(2, retained, added);
        TaskPlanReplanner replanner = new TaskPlanReplanner(validator);
        assertEquals(2, replanner.replan(current, replacement, 0,
                Collections.singleton("done")).getPlanVersion());

        final TaskPlan badVersion = plan(4, retained);
        assertError(ErrorCodes.PLAN_REPLAN_INVALID, new Runnable() {
            public void run() { replanner.replan(current, badVersion, 0, Collections.singleton("done")); }
        });
    }

    static PlanValidator validator(BudgetAmount budget, CapabilityAuthorizer authorizer) {
        return new PlanValidator(new PlanLimits(20, 20, 3, 4, 5000L, budget), authorizer);
    }
    static CapabilityAuthorizer allowAll() {
        return new CapabilityAuthorizer() { public boolean isAllowed(String capability) { return true; } };
    }
    static TaskPlan plan(int version, TaskNode... tasks) {
        return new TaskPlan("plan", "goal", version, Arrays.asList(tasks),
                Collections.<String, String>emptyMap(), PlanStatus.DRAFT);
    }
    static TaskNode task(String id, java.util.Set<String> dependencies, String output,
            String capability, BudgetAmount budget) {
        return TaskNode.builder().taskId(id).description(id).dependencies(dependencies)
                .capability(capability).outputKey(output).maximumBudget(budget)
                .idempotencyKey("key-" + id).handler(new TaskHandler() {
                    public TaskExecutionResult execute(TaskExecutionContext context) {
                        return new TaskExecutionResult(Collections.singletonMap(output, id), BudgetAmount.ZERO);
                    }
                }).build();
    }
    static void assertError(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}

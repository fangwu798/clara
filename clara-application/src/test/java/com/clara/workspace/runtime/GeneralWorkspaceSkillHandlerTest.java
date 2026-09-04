package com.clara.workspace.runtime;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.planner.*;
import com.clara.skill.api.*;
import com.clara.skill.builtin.GeneralWorkspaceSkill;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class GeneralWorkspaceSkillHandlerTest {
    private TaskPlanEngine engine;

    @After public void close() { if (engine != null) engine.close(); }

    @Test public void dynamicallyComposesWorkspaceModelAndMcpTasks() {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        GeneralWorkspacePlanFactory factory = new GeneralWorkspacePlanFactory() {
            public TaskPlan create(SkillDefinition definition, SkillExecutionRequest request, Map<String,Object> feedback) {
                TaskNode scan = task("scan", "workspace.scan", "files", Collections.<String>emptySet(), calls);
                TaskNode model = task("model", "model.chat", "analysis", Collections.singleton("scan"), calls);
                TaskNode mcp = task("mcp", "mcp.call", "external", Collections.singleton("model"), calls);
                return new TaskPlan("dynamic-plan", (String) request.getInputs().get("goal"), 1,
                        Arrays.asList(scan, model, mcp), Collections.<String,String>emptyMap(), PlanStatus.DRAFT);
            }
        };
        GeneralWorkspaceSkillHandler handler = new GeneralWorkspaceSkillHandler(engine(), factory);
        SkillExecutionResult result = handler.execute(GeneralWorkspaceSkill.definition(), request());
        assertEquals(SkillExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(Arrays.asList("scan", "model", "mcp"), calls);
        assertEquals(Integer.valueOf(3), result.getOutputs().get("executedSteps"));
        Map<?,?> values = (Map<?,?>) result.getOutputs().get("values");
        assertEquals("mcp", values.get("external"));
    }

    @Test public void rejectsPlannerCapabilityEscalationBeforeExecution() {
        final int[] executions = {0};
        GeneralWorkspacePlanFactory factory = new GeneralWorkspacePlanFactory() {
            public TaskPlan create(SkillDefinition definition, SkillExecutionRequest request, Map<String,Object> feedback) {
                TaskNode task = TaskNode.builder().taskId("bad").description("bad")
                        .capability("shell.exec").outputKey("bad").idempotencyKey("bad")
                        .maximumBudget(BudgetAmount.ZERO).handler(new TaskHandler(){public TaskExecutionResult execute(TaskExecutionContext context){executions[0]++;return output("bad");}}).build();
                return new TaskPlan("bad-plan", "bad", 1, Collections.singletonList(task),
                        Collections.<String,String>emptyMap(), PlanStatus.DRAFT);
            }
        };
        final GeneralWorkspaceSkillHandler handler = new GeneralWorkspaceSkillHandler(engine(), factory);
        try { handler.execute(GeneralWorkspaceSkill.definition(), request()); fail("expected capability denial"); }
        catch (ClaraException failure) { assertEquals(ErrorCodes.PLAN_CAPABILITY_DENIED, failure.getError().getCode()); }
        assertEquals(0, executions[0]);
    }

    @Test public void resumesSameInstanceWithFeedbackAndNextVersion() {
        GeneralWorkspacePlanFactory factory = new GeneralWorkspacePlanFactory() {
            public TaskPlan create(SkillDefinition definition, SkillExecutionRequest request, Map<String,Object> feedback) {
                assertEquals("approved", feedback.get("decision"));
                TaskNode task = TaskNode.builder().taskId("preview").description("preview")
                        .capability("workspace.preview").outputKey("preview").idempotencyKey("preview-after-feedback")
                        .maximumBudget(BudgetAmount.ZERO).handler(new TaskHandler(){public TaskExecutionResult execute(TaskExecutionContext context){return output("preview");}}).build();
                return new TaskPlan("resume-plan", "resume", 2, Collections.singletonList(task),
                        Collections.<String,String>emptyMap(), PlanStatus.DRAFT);
            }
        };
        GeneralWorkspaceSkillHandler handler = new GeneralWorkspaceSkillHandler(engine(), factory);
        SkillResumeRequest resume = new SkillResumeRequest(request(), "same-instance", 4L,
                Collections.<String,Object>singletonMap("decision", "approved"));
        SkillExecutionResult result = handler.resume(GeneralWorkspaceSkill.definition(), resume);
        assertEquals("same-instance", result.getInstanceId());
        assertEquals(5L, result.getInstanceVersion());
        Map<?,?> values = (Map<?,?>) result.getOutputs().get("values");
        assertEquals(Collections.singletonMap("decision", "approved"), values.get("feedback"));
    }

    private TaskPlanEngine engine() {
        BudgetAmount total = new BudgetAmount(0, 0, 10);
        PlanLimits limits = new PlanLimits(10, 10, 1, 2, 5000L, total);
        engine = new TaskPlanEngine(new PlanValidator(limits, new CapabilityAuthorizer(){public boolean isAllowed(String capability){return true;}}),
                new InMemoryBudgetLedger(total), new InMemoryExecutionLedger(), 2);
        return engine;
    }

    private static TaskNode task(final String id, String capability, String output, Set<String> dependencies, final List<String> calls) {
        return TaskNode.builder().taskId(id).description(id).capability(capability).dependencies(dependencies)
                .outputKey(output).idempotencyKey(id).maximumBudget(new BudgetAmount(0,0,1))
                .handler(new TaskHandler(){public TaskExecutionResult execute(TaskExecutionContext context){calls.add(id);return new TaskExecutionResult(Collections.<String,Object>singletonMap(output,id),new BudgetAmount(0,0,1));}}).build();
    }

    private static TaskExecutionResult output(String key) { return new TaskExecutionResult(Collections.<String,Object>singletonMap(key,key), BudgetAmount.ZERO); }
    private static SkillExecutionRequest request() {
        InvocationContext context = new InvocationContext("tenant", "user", "run", "instance", "trace",
                System.currentTimeMillis()+5000L, new CancellationToken());
        return new SkillExecutionRequest(context, GeneralWorkspaceSkill.SKILL_ID, GeneralWorkspaceSkill.VERSION,
                Collections.<String,Object>singletonMap("goal", "inspect and enrich files"));
    }
}

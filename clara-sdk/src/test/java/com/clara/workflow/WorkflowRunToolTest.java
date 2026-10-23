package com.clara.workflow;

import com.clara.context.ConvContext;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.workflow.definition.WorkflowDefinitionLoader;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.clara.workflow.runtime.WorkflowRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * {@link WorkflowRunTool} 的入参校验与待确认登记测试。
 *
 * <p>工具是模型直接调用的入口，参数形状完全不可控；同时「新任务失败要清登记、
 * 续接失败要保留登记」决定了用户重新确认时能否接上同一个实例。
 */
public class WorkflowRunToolTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exposesToolName() throws Exception {
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FixedEngine(null), "demo"));
        assertEquals("run_workflow", tool.name());
    }

    @Test
    public void rejectsNullRunner() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRunTool(null));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRunTool(null, null));
    }

    @Test
    public void rejectsMissingOrMalformedArguments() throws Exception {
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FixedEngine(null), "demo"));

        assertThrows(IllegalArgumentException.class, () -> tool.execute(null));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("  "));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("not-json"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{}"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"workflow\":\"  \"}"));
        assertThrows(IllegalArgumentException.class, () -> tool.execute("{\"workflow\":123}"));
    }

    @Test
    public void runsWorkflowWithoutConvContext() throws Exception {
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FixedEngine(null), "demo"));

        String output = tool.execute("{\"workflow\":\"demo\",\"inputs\":{\"subject\":\"x\"}}");

        assertEquals(true, output.contains("SUCCEEDED"));
    }

    @Test
    public void runsWorkflowWithNullInputsNode() throws Exception {
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FixedEngine(null), "demo"));

        String output = tool.execute("{\"workflow\":\"demo\",\"inputs\":null}");

        assertEquals(true, output.contains("SUCCEEDED"));
    }

    @Test
    public void resumesWhenInstanceIdProvided() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        WorkflowRunTool tool = new WorkflowRunTool(runner(engine, "demo"));

        tool.execute("{\"workflow\":\"demo\",\"instanceId\":\" inst-1 \",\"inputs\":{\"approve\":true}}");

        assertEquals("inst-1", engine.resumedInstanceId);
        assertEquals(true, engine.resumedInputs.get("approve"));
    }

    @Test
    public void blankInstanceIdFallsBackToNewRun() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        WorkflowRunTool tool = new WorkflowRunTool(runner(engine, "demo"));

        tool.execute("{\"workflow\":\"demo\",\"instanceId\":\"   \"}");

        assertEquals(1, engine.starts);
        assertEquals(0, engine.resumes);
    }

    @Test
    public void failedNewTaskClearsPendingHint() throws Exception {
        PendingWorkflowRegistry pending = new PendingWorkflowRegistry();
        pending.update("conv-1", "demo", waitingJson("inst-old"));
        assertNotNull(pending.hint("conv-1"));
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FailingEngine(), "demo"), pending);

        try (ConvContext.Scope ignored = ConvContext.open(context("conv-1"))) {
            assertThrows(IllegalStateException.class, () -> tool.execute("{\"workflow\":\"demo\"}"));
        }

        assertNull(pending.hint("conv-1"));
    }

    @Test
    public void failedResumeKeepsPendingHint() throws Exception {
        PendingWorkflowRegistry pending = new PendingWorkflowRegistry();
        pending.update("conv-1", "demo", waitingJson("inst-old"));
        WorkflowRunTool tool = new WorkflowRunTool(runner(new FailingEngine(), "demo"), pending);

        try (ConvContext.Scope ignored = ConvContext.open(context("conv-1"))) {
            assertThrows(IllegalStateException.class,
                    () -> tool.execute("{\"workflow\":\"demo\",\"instanceId\":\"inst-old\"}"));
        }

        assertNotNull(pending.hint("conv-1"));
    }

    @Test
    public void waitingResultIsRegisteredForCurrentConversation() throws Exception {
        PendingWorkflowRegistry pending = new PendingWorkflowRegistry();
        WorkflowRunTool tool = new WorkflowRunTool(runner(new WaitingEngine(), "demo"), pending);

        try (ConvContext.Scope ignored = ConvContext.open(context("conv-9"))) {
            tool.execute("{\"workflow\":\"demo\"}");
        }

        String hint = pending.hint("conv-9");
        assertNotNull(hint);
        assertEquals(true, hint.contains("demo"));
    }

    private static ConvContext context(String convId) {
        return new ConvContext(null, convId, null, null);
    }

    private static String waitingJson(String instanceId) {
        return "{\"status\":\"WAITING_INPUT\",\"instanceId\":\"" + instanceId
                + "\",\"waitingInputKey\":\"promptFeedback\",\"outputs\":{\"prompt\":\"确认？\"}}";
    }

    private WorkflowRunner runner(IWorkflowEngine engine, String workflowId) throws Exception {
        Path directory = folder.newFolder().toPath();
        Files.write(directory.resolve(workflowId + ".yml"), definition(workflowId).getBytes(StandardCharsets.UTF_8));
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry(directory,
                new WorkflowDefinitionLoader(
                        Collections.<IWorkflowActionFactory>singletonList(new StubFactory())));
        return new WorkflowRunner(engine, registry);
    }

    private static String definition(String workflowId) {
        return "workflowId: " + workflowId + "\n"
                + "root:\n"
                + "  id: root\n"
                + "  type: SEQUENCE\n"
                + "  children:\n"
                + "    - id: scan\n"
                + "      type: TOOL\n"
                + "      outputKeys: [result]\n"
                + "      tool: file_ops\n"
                + "      arguments:\n"
                + "        op: scan";
    }

    private static final class StubFactory implements IWorkflowActionFactory {
        @Override
        public boolean supports(String actionKind) {
            return "TOOL".equalsIgnoreCase(actionKind);
        }

        @Override
        public IWorkflowAction create(WorkflowActionSpec spec) {
            return scope -> Collections.<String, Object>emptyMap();
        }
    }

    /** 固定成功的引擎，用于验证正常返回路径。 */
    private static class FixedEngine implements IWorkflowEngine {
        private final Map<String, Object> extra;

        private FixedEngine(Map<String, Object> extra) {
            this.extra = extra;
        }

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("instanceId", instanceId);
            if (extra != null) {
                values.putAll(extra);
            }
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.SUCCEEDED,
                    new ExecutionScope(values), 1L);
        }

        @Override
        public WorkflowResult resume(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            return start(definition, instanceId, inputs);
        }

        @Override
        public WorkflowResult resume(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs,
                long expectedCheckpointVersion) {
            return start(definition, instanceId, inputs);
        }

        @Override
        public WorkflowResult cancel(String instanceId) {
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.CANCELLED,
                    new ExecutionScope(Collections.<String, Object>emptyMap()), 1L);
        }

        @Override
        public WorkflowResult suspend(String instanceId) {
            return cancel(instanceId);
        }

        @Override
        public void close() {
            // 无外部资源
        }
    }

    /** 记录 start / resume 调用的引擎。 */
    private static final class RecordingEngine extends FixedEngine {
        private int starts;
        private int resumes;
        private String resumedInstanceId;
        private Map<String, Object> resumedInputs;

        private RecordingEngine() {
            super(null);
        }

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            starts++;
            return super.start(definition, instanceId, inputs);
        }

        @Override
        public WorkflowResult resume(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            resumes++;
            resumedInstanceId = instanceId;
            resumedInputs = new LinkedHashMap<>(inputs);
            return super.start(definition, instanceId, inputs);
        }
    }

    /** 固定失败的引擎，用于验证登记表清理规则。 */
    private static final class FailingEngine extends FixedEngine {
        private FailingEngine() {
            super(null);
        }

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            throw new IllegalStateException("boom");
        }
    }

    /** 固定返回暂停状态的引擎。 */
    private static final class WaitingEngine extends FixedEngine {
        private WaitingEngine() {
            super(null);
        }

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            return new WorkflowResult("inst-wait", WorkflowInstanceStatusEnum.WAITING_INPUT,
                    new ExecutionScope(inputs), 1L, "review", "promptFeedback");
        }
    }
}
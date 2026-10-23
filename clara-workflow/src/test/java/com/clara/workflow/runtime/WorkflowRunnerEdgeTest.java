package com.clara.workflow.runtime;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 补充覆盖 {@link WorkflowRunner} 的入参解析、输出裁剪与长度保护。
 *
 * <p>这些分支对应的都是「模型直连工具」时的真实误用：传了非 JSON、传了空实例号、
 * 或者工作流把整份源码塞进输出。任何一条漏掉都会把超大或错误内容回灌给模型。
 */
public class WorkflowRunnerEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void rejectsNullCollaborators() throws Exception {
        WorkflowDefinitionRegistry registry = registry(folder.newFolder("a").toPath());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRunner(null, registry));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRunner(new FixedEngine(null), null));
    }

    @Test
    public void rejectsNonJsonInputs() throws Exception {
        Path directory = folder.newFolder("b").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "input_flow.yml", definition("input_flow", ""));
        WorkflowRunner runner = new WorkflowRunner(new FixedEngine(null), registry);

        assertThrows(IllegalArgumentException.class, () -> runner.run("input_flow", "not-json"));
        assertThrows(IllegalArgumentException.class, () -> runner.run("input_flow", "[1,2]"));
    }

    @Test
    public void treatsBlankInputsAsEmptyObject() throws Exception {
        Path directory = folder.newFolder("c").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "blank_flow.yml", definition("blank_flow", ""));
        RecordingEngine engine = new RecordingEngine();
        WorkflowRunner runner = new WorkflowRunner(engine, registry);

        runner.run("blank_flow", "   ");
        runner.run("blank_flow", null);

        assertEquals(1, engine.inputs.size());
        assertTrue(engine.inputs.containsKey("instanceId"));
    }

    @Test
    public void keepsCallerSuppliedInstanceId() throws Exception {
        Path directory = folder.newFolder("d").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "keep_flow.yml", definition("keep_flow", ""));
        RecordingEngine engine = new RecordingEngine();
        WorkflowRunner runner = new WorkflowRunner(engine, registry);

        String result = runner.run("keep_flow", "{\"instanceId\":\"fixed-1\"}");

        assertEquals("fixed-1", engine.inputs.get("instanceId"));
        assertTrue(result.contains("keep_flow"));
    }

    @Test
    public void resumeRejectsBlankInstanceId() throws Exception {
        Path directory = folder.newFolder("e").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "resume_flow.yml", definition("resume_flow", ""));
        WorkflowRunner runner = new WorkflowRunner(new FixedEngine(null), registry);

        assertThrows(IllegalArgumentException.class, () -> runner.resume("resume_flow", null, "{}"));
        assertThrows(IllegalArgumentException.class, () -> runner.resume("resume_flow", "  ", "{}"));
    }

    @Test
    public void resumeNeverLetsInputsOverrideInstanceId() throws Exception {
        Path directory = folder.newFolder("f").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "resume2_flow.yml", definition("resume2_flow", ""));
        RecordingEngine engine = new RecordingEngine();
        WorkflowRunner runner = new WorkflowRunner(engine, registry);

        String result = runner.resume("resume2_flow", " inst-9 ", "{\"instanceId\":\"hacked\",\"k\":1}");

        assertEquals("inst-9", engine.instanceId);
        assertFalse(engine.inputs.containsKey("instanceId"));
        assertEquals(1, engine.inputs.get("k"));
        assertTrue(result.contains("\"instanceId\":\"inst-9\""));
    }

    @Test
    public void payloadFiltersUndeclaredOutputsAndTruncatesLongText() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 21000; index++) {
            longText.append('x');
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("result", longText.toString());
        variables.put("secret", "should-not-leak");
        variables.put("object", new Object());

        Path directory = folder.newFolder("g").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "trim_flow.yml", definition("trim_flow", "outputs: [result]\n"));

        WorkflowRunner runner = new WorkflowRunner(new FixedEngine(variables), registry);
        String result = runner.run("trim_flow", "{}");

        assertFalse(result.contains("should-not-leak"));
        assertTrue(result.contains("truncated"));
        // 结果里不能出现完整的 21000 字符原文。
        assertFalse(result.contains(longText.toString()));
    }

    @Test
    public void payloadConvertsNonScalarOutputsToText() throws Exception {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("result", new Object() {
            @Override
            public String toString() {
                return "custom-value";
            }
        });

        Path directory = folder.newFolder("h").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        write(directory, "text_flow.yml", definition("text_flow", "outputs: [result]\n"));

        WorkflowRunner runner = new WorkflowRunner(new FixedEngine(variables), registry);
        String result = runner.run("text_flow", "{}");

        assertTrue(result.contains("custom-value"));
    }

    private static WorkflowDefinitionRegistry registry(Path directory) {
        return new WorkflowDefinitionRegistry(directory, new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory())));
    }

    private static void write(Path directory, String name, String content) throws Exception {
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String definition(String workflowId, String extraTopLevel) {
        return "workflowId: " + workflowId + "\n"
                + extraTopLevel
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

    /** 记录 start 入参的引擎，用于验证运行器对输入的处理。 */
    private static class RecordingEngine implements IWorkflowEngine {
        private String instanceId;
        private Map<String, Object> inputs;

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            this.instanceId = instanceId;
            this.inputs = new LinkedHashMap<>(inputs);
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.SUCCEEDED,
                    new ExecutionScope(inputs), 1L);
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

    /** 固定返回一组变量的引擎，用于验证输出裁剪与截断。 */
    private static final class FixedEngine extends RecordingEngine {
        private final Map<String, Object> variables;

        private FixedEngine(Map<String, Object> variables) {
            this.variables = variables;
        }

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
            Map<String, Object> scopeValues = new LinkedHashMap<>();
            if (variables != null) {
                scopeValues.putAll(variables);
            }
            scopeValues.put("instanceId", instanceId);
            return new WorkflowResult(instanceId, WorkflowInstanceStatusEnum.SUCCEEDED,
                    new ExecutionScope(scopeValues), 1L);
        }
    }
}
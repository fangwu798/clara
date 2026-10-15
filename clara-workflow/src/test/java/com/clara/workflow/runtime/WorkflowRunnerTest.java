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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkflowRunner} 的运行测试：调用前按需刷新定义（运行期新写入的自定义工作流无需重启即可运行），
 * 以及标识不存在且存在非法定义文件时把加载失败原因一并回传。
 */
public class WorkflowRunnerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void runsDefinitionWrittenAfterRegistryWasLoaded() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        WorkflowRunner runner = new WorkflowRunner(new StubEngine(), registry);
        write(directory.resolve("custom"), "late_flow.yml", definition("late_flow"));

        String result = runner.run("late_flow", "{}");

        assertTrue(result.contains("late_flow"));
        assertTrue(result.contains("SUCCEEDED"));
    }

    @Test
    public void reportsLoadingFailureAlongWithUnknownIdentifier() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        WorkflowRunner runner = new WorkflowRunner(new StubEngine(), registry);
        write(directory.resolve("custom"), "broken.yml", "workflowId: broken_flow\nroot:\n  id: scan\n  type: TOOL\n");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> runner.run("missing_flow", null));

        assertTrue(failure.getMessage().contains("工作流不存在：missing_flow"));
        assertTrue(failure.getMessage().contains("broken.yml"));
    }

    private static WorkflowDefinitionRegistry registry(Path directory) {
        return new WorkflowDefinitionRegistry(directory, new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory())));
    }

    private static void write(Path directory, String name, String content) throws Exception {
        Files.createDirectories(directory);
        Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String definition(String workflowId) {
        return String.join("\n",
                "workflowId: " + workflowId,
                "description: 测试用工作流",
                "root:",
                "  id: root",
                "  type: SEQUENCE",
                "  children:",
                "    - id: scan",
                "      type: TOOL",
                "      outputKeys: [result]",
                "      tool: file_ops",
                "      arguments:",
                "        op: scan");
    }

    /**
     * 测试用 TOOL 动作工厂：只证明加载器能按类型找到工厂，不关心动作行为。
     */
    private static final class StubFactory implements IWorkflowActionFactory {

        @Override
        public boolean supports(String actionKind) {
            return "TOOL".equalsIgnoreCase(actionKind);
        }

        @Override
        public IWorkflowAction create(WorkflowActionSpec spec) {
            return scope -> {
                LinkedHashMap<String, Object> output = new LinkedHashMap<String, Object>();
                output.put("output", spec.getNodeId());
                return output;
            };
        }
    }

    /**
     * 测试用引擎：不真正执行节点，直接回传成功状态与携带输入的作用域，用于验证运行器的取定义与结果整理。
     */
    private static final class StubEngine implements IWorkflowEngine {

        @Override
        public WorkflowResult start(WorkflowDefinition definition, String instanceId, Map<String, Object> inputs) {
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
            // 无外部资源需要释放
        }
    }
}
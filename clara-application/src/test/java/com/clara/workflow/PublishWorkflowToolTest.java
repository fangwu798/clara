package com.clara.workflow;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.workflow.definition.WorkflowDefinitionLoader;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link PublishWorkflowTool} 的单元测试：发布前校验（非法定义不落盘）、写入自定义目录并立即生效、
 * 标识命名与重名校验、dryRun 只校验不落盘。
 */
public class PublishWorkflowToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void publishesDefinitionIntoCustomDirectoryAndMakesItImmediatelyAvailable() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);

        String result = tool.execute(arguments(definition("export_daily_report"), false));

        JsonNode payload = JSON.readTree(result);
        assertEquals("export_daily_report", payload.get("workflowId").asText());
        assertTrue(payload.get("path").asText().endsWith("custom/export_daily_report.yml"));
        assertFalse(payload.get("replaced").asBoolean());
        assertEquals("已发布并立即生效", payload.get("status").asText());
        assertTrue(Files.isRegularFile(directory.resolve("custom").resolve("export_daily_report.yml")));
        assertTrue(registry.ids().contains("export_daily_report"));
    }

    @Test
    public void rejectsInvalidDefinitionWithoutWritingAnything() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);
        String invalid = String.join("\n",
                "workflowId: broken_flow",
                "root:",
                "  id: scan",
                "  type: TOOL");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(arguments(invalid, false)));

        assertTrue(failure.getMessage().contains("待发布的自定义工作流"));
        assertTrue(failure.getMessage().contains("节点 scan"));
        assertFalse(Files.exists(directory.resolve("custom").resolve("broken_flow.yml")));
    }

    @Test
    public void dryRunValidatesOnly() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);

        JsonNode payload = JSON.readTree(tool.execute(arguments(definition("draft_flow"), true)));

        assertEquals("draft_flow", payload.get("workflowId").asText());
        assertEquals("校验通过（未落盘）", payload.get("status").asText());
        assertFalse(Files.exists(directory.resolve("custom").resolve("draft_flow.yml")));
        assertFalse(registry.ids().contains("draft_flow"));
    }

    @Test
    public void rejectsIdentifierAlreadyUsedByAnotherWorkflow() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        Files.write(directory.resolve("builtin.yml"),
                definition("builtin_flow").getBytes(StandardCharsets.UTF_8));
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(arguments(definition("builtin_flow"), false)));

        assertTrue(failure.getMessage().contains("已被其它工作流占用"));
    }

    @Test
    public void rejectsUnsafeIdentifier() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(arguments(definition("bad name"), false)));

        assertTrue(failure.getMessage().contains("只允许字母"));
    }

    @Test
    public void replacesExistingCustomDefinition() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        PublishWorkflowTool tool = new PublishWorkflowTool(registry);
        tool.execute(arguments(definition("iterating_flow"), false));

        JsonNode payload = JSON.readTree(tool.execute(arguments(definition("iterating_flow"), false)));

        assertTrue(payload.get("replaced").asBoolean());
        assertEquals(Collections.singletonList("iterating_flow"), registry.ids());
    }

    private static WorkflowDefinitionRegistry registry(Path directory) {
        return new WorkflowDefinitionRegistry(directory, new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory())));
    }

    private static String arguments(String yaml, boolean dryRun) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("yaml", yaml);
        payload.put("dryRun", Boolean.valueOf(dryRun));
        return JSON.writeValueAsString(payload);
    }

    private static String definition(String workflowId) {
        return String.join("\n",
                "workflowId: " + workflowId,
                "description: 测试用工作流",
                "outputs: [result]",
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
}

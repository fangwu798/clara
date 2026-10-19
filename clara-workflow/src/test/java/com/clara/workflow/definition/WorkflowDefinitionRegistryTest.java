package com.clara.workflow.definition;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.dto.workflow.WorkflowDefinition;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkflowDefinitionRegistry} 的运行期刷新测试：新写入的自定义工作流即时可用；新文件写坏时保留既有索引
 * 并回传失败原因；修好后自动重试成功。
 */
public class WorkflowDefinitionRegistryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void refreshPicksUpNewlyWrittenDefinition() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        assertTrue(registry.ids().isEmpty());

        write(directory.resolve("custom"), "export_daily_report.yml", definition("export_daily_report"));

        assertTrue(registry.refresh());
        assertEquals(Collections.singletonList("export_daily_report"), registry.ids());
        assertNull(registry.lastError());
    }

    @Test
    public void refreshKeepsPreviousIndexWhenNewFileIsInvalid() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        write(directory, "good.yml", definition("good_flow"));
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();

        write(directory, "broken.yml", "workflowId: broken_flow\nroot:\n  id: scan\n  type: TOOL\n");

        assertFalse(registry.refresh());
        assertEquals(Collections.singletonList("good_flow"), registry.ids());
        assertNotNull(registry.lastError());
    }

    @Test
    public void refreshRetriesAfterFileIsFixed() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        Path broken = write(directory, "broken.yml", "workflowId: broken_flow\nroot:\n  id: scan\n  type: TOOL\n");
        assertFalse(registry.refresh());

        Files.write(broken, definition("broken_flow").getBytes(StandardCharsets.UTF_8));

        assertTrue(registry.refresh());
        assertEquals(Collections.singletonList("broken_flow"), registry.ids());
        assertNull(registry.lastError());
    }

    @Test
    public void refreshWithoutChangesKeepsIndex() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        write(directory, "good.yml", definition("good_flow"));
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();

        assertTrue(registry.refresh());
        assertEquals(Collections.singletonList("good_flow"), registry.ids());
    }

    @Test
    public void parseDefinitionValidatesWithoutTouchingIndex() throws Exception {
        Path directory = folder.newFolder("workflow").toPath();
        WorkflowDefinitionRegistry registry = registry(directory);
        registry.reload();
        Path candidate = write(directory, "candidate.yml", definition("candidate_flow"));

        WorkflowDefinition definition = registry.parseDefinition(candidate);

        assertEquals("candidate_flow", definition.getWorkflowId());
        assertTrue(registry.ids().isEmpty());
        Path invalid = write(directory, "invalid.yml", "workflowId: x\n");
        assertThrows(IllegalArgumentException.class, () -> registry.parseDefinition(invalid));
    }

    private static WorkflowDefinitionRegistry registry(Path directory) {
        return new WorkflowDefinitionRegistry(directory, new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory())));
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
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
                LinkedHashMap<String, Object> output = new LinkedHashMap<>();
                output.put("output", spec.getNodeId());
                return output;
            };
        }
    }
}

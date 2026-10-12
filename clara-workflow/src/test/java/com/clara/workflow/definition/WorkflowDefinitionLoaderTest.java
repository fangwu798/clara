package com.clara.workflow.definition;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkflowDefinitionLoader} 对 REVIEW 节点的单元测试：加载成功、输出键约束与缺工厂失败。
 */
public class WorkflowDefinitionLoaderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadsReviewNode() throws Exception {
        Path file = write("review.yml", reviewYaml());
        WorkflowDefinitionLoader loader = new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory()));

        LoadedWorkflow loaded = loader.load(file);

        assertEquals("demo_review", loaded.getDefinition().getWorkflowId());
        assertEquals(WorkflowNodeTypeEnum.REVIEW, loaded.getDefinition().getRoot().getType());
        assertTrue(loaded.getDefinition().getRoot().getOutputKeys().contains("conclusion"));
        assertEquals(Collections.singletonList("conclusion"), loaded.getOutputs());
        assertEquals(1, loaded.getDefinition().getNodes().size());
    }

    @Test
    public void rejectsReviewNodeWithoutFactory() throws Exception {
        Path file = write("review.yml", reviewYaml());
        WorkflowDefinitionLoader loader = new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>emptyList());

        assertThrows(IllegalArgumentException.class, () -> loader.load(file));
    }

    @Test
    public void rejectsReviewNodeWithMultipleOutputKeys() throws Exception {
        Path file = write("review.yml", reviewYaml().replace("outputKeys: [conclusion]", "outputKeys: [a, b]"));
        WorkflowDefinitionLoader loader = new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new StubFactory()));

        assertThrows(IllegalArgumentException.class, () -> loader.load(file));
    }

    private Path write(String name, String content) throws Exception {
        Path file = folder.newFile(name).toPath();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String reviewYaml() {
        return String.join("\n",
                "workflowId: demo_review",
                "outputs: [conclusion]",
                "root:",
                "  id: panel",
                "  type: REVIEW",
                "  outputKeys: [conclusion]",
                "  prompt: review_panel",
                "  topic: \"${topic}\"",
                "  roles: [architect, reviewer]",
                "  moderator: architect",
                "  rounds: 2");
    }

    /**
     * 测试用 REVIEW 动作工厂：仅证明加载器能按类型找到工厂并接受 REVIEW 节点，不关心动作行为。
     */
    private static final class StubFactory implements IWorkflowActionFactory {

        @Override
        public boolean supports(String actionKind) {
            return "REVIEW".equalsIgnoreCase(actionKind);
        }

        @Override
        public IWorkflowAction create(WorkflowActionSpec spec) {
            return scope -> Collections.<String, Object>emptyMap();
        }
    }
}

package com.clara.workflow.definition;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.dto.workflow.ExecutionScope;
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
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Boundary validation for the configurable workflow DSL: invalid definitions must fail at load time
 * with a locatable error instead of failing halfway through a run.
 */
public class WorkflowDefinitionEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ExecutionScope scope = new ExecutionScope(Collections.<String, Object>emptyMap());

    @Test
    public void loaderRejectsNullConstructorAndFileArguments() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinitionLoader(null));
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(null));
    }

    @Test
    public void loaderRejectsUnreadableAndNonObjectRoots() throws Exception {
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(folder.getRoot().toPath().resolve("missing.yml")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("list.yml", "- a\n- b\n")));
    }

    @Test
    public void loaderRejectsMissingIdentityAndRoot() throws Exception {
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("no-id.yml",
                "root:\n  id: scan\n  type: SEQUENCE\n  children: [a]\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("no-root.yml", "workflowId: demo\n")));
    }

    @Test
    public void loaderRejectsUnknownNodeShape() throws Exception {
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("no-node-id.yml",
                "workflowId: demo\nroot:\n  type: SEQUENCE\n  children:\n"
                        + "    - id: a\n      type: TEXT\n      outputKeys: [x]\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("bad-type.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: NOPE\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("loop.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: LOOP\n")));
    }

    @Test
    public void loaderRejectsCompositionAndActionContractViolations() throws Exception {
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("no-children.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: SEQUENCE\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("bad-child.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: SEQUENCE\n  children: [1, 2]\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("no-output.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: TOOL\n  tool: file_ops\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("two-outputs.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: TOOL\n  outputKeys: [a, b]\n  tool: file_ops\n")));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDefinitionLoader(Collections.<IWorkflowActionFactory>emptyList())
                        .load(write("no-factory.yml",
                                "workflowId: demo\nroot:\n  id: root\n  type: TOOL\n"
                                        + "  outputKeys: [a]\n  tool: file_ops\n")));
    }

    @Test
    public void loaderValidatesHumanAndConditionNodes() throws Exception {
        WorkflowDefinitionLoader loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("human.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: HUMAN\n")));
        assertEquals("demo", loader.load(write("human-ok.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: HUMAN\n  inputKey: answer\n"))
                .getDefinition().getWorkflowId());

        assertThrows(IllegalArgumentException.class, () -> loader.load(write("condition-missing.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: CONDITION\n  whenTrue:\n"
                        + "    id: a\n    type: HUMAN\n    inputKey: x\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("condition-no-key.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: CONDITION\n  condition:\n"
                        + "    operator: equals\n  whenTrue:\n    id: a\n    type: HUMAN\n    inputKey: x\n")));
        assertThrows(IllegalArgumentException.class, () -> loader.load(write("condition-no-true.yml",
                "workflowId: demo\nroot:\n  id: root\n  type: CONDITION\n  condition:\n    key: k\n")));
    }

    @Test
    public void loaderAcceptsConditionBranchesAndSchemas() throws Exception {
        String yaml = "workflowId: conditional\n"
                + "description: condition demo\n"
                + "inputSchema: '{\"type\":\"object\"}'\n"
                + "outputSchema:\n  type: object\n"
                + "outputs: [result, '  ']\n"
                + "root:\n"
                + "  id: root\n"
                + "  type: CONDITION\n"
                + "  condition:\n    key: approved\n    operator: equals\n    value: true\n"
                + "  whenTrue:\n    id: yes_branch\n    type: HUMAN\n    inputKey: note\n"
                + "  whenFalse:\n    id: no_branch\n    type: HUMAN\n    inputKey: note\n";

        LoadedWorkflow loaded = loader().load(write("condition.yml", yaml));

        WorkflowDefinition definition = loaded.getDefinition();
        assertEquals("conditional", definition.getWorkflowId());
        assertEquals("{\"type\":\"object\"}", definition.getInputSchema());
        assertTrue(definition.getOutputSchema().contains("object"));
        assertEquals(Collections.singletonList("result"), loaded.getOutputs());
    }

    @Test
    public void scopeConditionSupportsExistsEqualsAndNotEquals() {
        ExecutionScope values = new ExecutionScope(map("approved", Boolean.TRUE, "mode", "fast"));

        assertTrue(new ConfigScopeCondition("approved", "exists", null).test(values));
        assertFalse(new ConfigScopeCondition("missing", "exists", null).test(values));
        assertTrue(new ConfigScopeCondition("mode", null, "fast").test(values));
        assertFalse(new ConfigScopeCondition("mode", "notEquals", "fast").test(values));
        assertTrue(new ConfigScopeCondition("mode", "notEquals", "slow").test(values));
        assertFalse("null scope must not match equals",
                new ConfigScopeCondition("mode", "equals", "fast").test(null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigScopeCondition("  ", "equals", "x"));
    }

    @Test
    public void outputKeyedActionRebuildsDelegateResult() {
        Map<String, Object> direct = map("result", "value");
        assertEquals(direct, new OutputKeyedAction(s -> direct, "result").execute(scope));

        assertEquals(map("result", "from-output"),
                new OutputKeyedAction(s -> map("output", "from-output"), "result").execute(scope));
        assertEquals(map("result", "only"),
                new OutputKeyedAction(s -> map("other", "only"), "result").execute(scope));
        assertEquals(map("result", null),
                new OutputKeyedAction(s -> map("a", 1, "b", 2), "result").execute(scope));
        assertEquals(map("result", null),
                new OutputKeyedAction(s -> null, "result").execute(scope));
    }

    @Test
    public void registryHandlesMissingDirectoryAndDuplicateIds() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDefinitionRegistry(folder.getRoot().toPath(), null));

        Path directory = folder.newFolder("workflow-registry").toPath();
        WorkflowDefinitionRegistry registry = new WorkflowDefinitionRegistry(directory, loader());
        registry.reload();
        assertTrue(registry.ids().isEmpty());
        assertEquals(directory, registry.directory());
        assertTrue(registry.outputs("unknown").isEmpty());
        assertNull(registry.lastError());
        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));

        write(directory, "a.yml", "workflowId: dup\nroot:\n  id: a\n  type: HUMAN\n  inputKey: x\n");
        write(directory.resolve("nested"), "b.yml",
                "workflowId: dup\nroot:\n  id: b\n  type: HUMAN\n  inputKey: x\n");
        assertThrows(IllegalArgumentException.class, registry::reload);
    }

    private WorkflowDefinitionLoader loader() {
        return new WorkflowDefinitionLoader(
                Collections.<IWorkflowActionFactory>singletonList(new AnyFactory()));
    }

    private Path write(String name, String content) throws Exception {
        return write(folder.newFolder().toPath(), name, content);
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            values.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return values;
    }

    private static final class AnyFactory implements IWorkflowActionFactory {

        @Override
        public boolean supports(String actionKind) {
            return true;
        }

        @Override
        public IWorkflowAction create(WorkflowActionSpec spec) {
            return scope -> Collections.<String, Object>emptyMap();
        }
    }
}

package com.clara.dto.workflow;

import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 工作流 DTO：执行作用域的累积与合并、定义图校验、结果与检查点的值语义。
 */
public class WorkflowDtoTest {

    /**
     * 执行作用域：输入可读、写操作递增版本、副本相互隔离。
     */
    @Test
    public void accumulatesScopeState() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("goal", "ship");
        ExecutionScope scope = new ExecutionScope(inputs);
        assertEquals(1, scope.getSchemaVersion());
        assertEquals(0L, scope.getVersion());
        assertTrue(scope.contains("goal"));
        assertEquals("ship", scope.get("goal"));
        assertThrows(UnsupportedOperationException.class, () -> scope.getVariables().put("x", 1));

        scope.addInputs(Collections.singletonMap("count", 2));
        assertEquals(1L, scope.getVersion());
        assertEquals(2, scope.get("count"));

        Map<String, Object> outputs = new HashMap<>();
        outputs.put("result", "ok");
        scope.complete("exec-1", "node-1", outputs);
        assertTrue(scope.isCompleted("exec-1"));
        assertEquals("ok", scope.get("result"));
        assertEquals("ok", scope.getNodeResults().get("node-1").get("result"));
        assertEquals(2L, scope.getVersion());

        scope.control(ExecutionScope.CONTROL_HUMAN_CONFIRMED, "node-1");
        assertEquals("node-1", scope.control(ExecutionScope.CONTROL_HUMAN_CONFIRMED));
        assertEquals(3L, scope.getVersion());

        ExecutionScope copy = scope.copy();
        copy.addInputs(Collections.singletonMap("goal", "changed"));
        assertEquals("ship", scope.get("goal"));
        assertEquals("changed", copy.get("goal"));
    }

    /**
     * 合并分支：分支新增或不同的变量写入当前作用域，相同值跳过。
     */
    @Test
    public void mergesBranchScope() {
        Map<String, Object> baseValues = new HashMap<>();
        baseValues.put("a", 1);
        baseValues.put("b", 2);
        ExecutionScope base = new ExecutionScope(baseValues);
        base.complete("e-base", "n-base", Collections.singletonMap("x", "base"));

        Map<String, Object> branchValues = new HashMap<>();
        branchValues.put("a", 1);
        branchValues.put("c", 3);
        ExecutionScope branch = new ExecutionScope(branchValues);
        branch.complete("e-branch", "n-branch", Collections.singletonMap("y", "branch"));
        branch.control("k", "v");

        ExecutionScope target = new ExecutionScope(Collections.emptyMap());
        target.merge(base, branch);
        assertFalse(target.contains("a"));
        assertEquals(3, target.get("c"));
        assertFalse(target.contains("b"));
        assertTrue(target.isCompleted("e-branch"));
        assertEquals("branch", target.getNodeResults().get("n-branch").get("y"));
        assertEquals("v", target.control("k"));
    }

    /**
     * 工作流定义递归收集节点并拒绝重复 ID 与环。
     */
    @Test
    public void validatesWorkflowGraph() {
        FakeNode leaf = new FakeNode("leaf");
        FakeNode root = new FakeNode("root", leaf);
        WorkflowDefinition definition = new WorkflowDefinition("wf", "v1", "{}", "{}", root);
        assertEquals("wf", definition.getWorkflowId());
        assertEquals("v1", definition.getVersion());
        assertEquals("{}", definition.getInputSchema());
        assertEquals("{}", definition.getOutputSchema());
        assertEquals(root, definition.getRoot());
        assertEquals(2, definition.getNodes().size());
        assertThrows(UnsupportedOperationException.class, () -> definition.getNodes().add(leaf));

        FakeNode duplicateA = new FakeNode("same");
        FakeNode duplicateRoot = new FakeNode("root", duplicateA, new FakeNode("same"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("wf", "v1", null, null, duplicateRoot));

        FakeNode cycle = new FakeNode("cycle");
        cycle.children.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("wf", "v1", null, null, cycle));

        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition(" ", "v1", null, null, leaf));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("wf", "", null, null, leaf));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("wf", "v1", null, null, null));
    }

    /**
     * 工作流结果：等待信息可选，值相等时 equals/hashCode 一致。
     */
    @Test
    public void comparesWorkflowResult() {
        ExecutionScope scope = new ExecutionScope(Collections.singletonMap("k", "v"));
        WorkflowResult done = new WorkflowResult("i1", WorkflowInstanceStatusEnum.SUCCEEDED, scope, 3L);
        assertNull(done.getWaitingNodeId());
        assertNull(done.getWaitingInputKey());
        assertEquals("v", done.getScope().get("k"));
        assertTrue(done.equals(done));
        assertFalse(done.equals("other"));
        assertEquals(done.hashCode(), done.hashCode());

        WorkflowResult waiting = new WorkflowResult("i1", WorkflowInstanceStatusEnum.WAITING_INPUT,
                scope, 3L, "node-1", "input");
        assertEquals("node-1", waiting.getWaitingNodeId());
        assertEquals("input", waiting.getWaitingInputKey());
        assertEquals(WorkflowInstanceStatusEnum.WAITING_INPUT, waiting.getStatus());
    }

    /**
     * 检查点复制作用域，且相等性按字段比较。
     */
    @Test
    public void comparesWorkflowCheckpoint() {
        ExecutionScope scope = new ExecutionScope(Collections.singletonMap("k", "v"));
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint("i1", "wf", "v1",
                WorkflowInstanceStatusEnum.RUNNING, scope, 2L);
        assertEquals("i1", checkpoint.getInstanceId());
        assertEquals("wf", checkpoint.getWorkflowId());
        assertEquals("v1", checkpoint.getWorkflowVersion());
        assertEquals(WorkflowInstanceStatusEnum.RUNNING, checkpoint.getStatus());
        assertEquals(2L, checkpoint.getVersion());
        assertNotNull(checkpoint.getScope());
        assertEquals("v", checkpoint.getScope().get("k"));

        assertTrue(checkpoint.equals(checkpoint));
        assertFalse(checkpoint.equals("other"));
        assertEquals(checkpoint.hashCode(), checkpoint.hashCode());

        assertThrows(IllegalArgumentException.class, () -> new WorkflowCheckpoint(" ", "wf", "v1",
                WorkflowInstanceStatusEnum.RUNNING, scope, 2L));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowCheckpoint("i1", "wf", "v1",
                null, scope, 2L));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowCheckpoint("i1", "wf", "v1",
                WorkflowInstanceStatusEnum.RUNNING, scope, -1L));
    }

    /**
     * 动作描述对象按属性名读取配置。
     */
    @Test
    public void readsWorkflowActionSpec() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("url", "https://example.com");
        WorkflowActionSpec spec = new WorkflowActionSpec("node-1", "tool", attributes);
        assertEquals("node-1", spec.getNodeId());
        assertEquals("tool", spec.getKind());
        assertEquals("https://example.com", spec.getAttribute("url"));
        assertNull(spec.getAttribute("missing"));
    }

    private static final class FakeNode implements IWorkflowNode {
        private final String id;
        private final List<IWorkflowNode> children = new ArrayList<>();

        private FakeNode(String id, IWorkflowNode... children) {
            this.id = id;
            Collections.addAll(this.children, children);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public WorkflowNodeTypeEnum getType() {
            return WorkflowNodeTypeEnum.SEQUENCE;
        }

        @Override
        public Set<String> getOutputKeys() {
            return Collections.emptySet();
        }

        @Override
        public List<IWorkflowNode> children() {
            return children;
        }
    }
}
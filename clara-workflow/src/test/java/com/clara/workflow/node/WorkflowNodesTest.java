package com.clara.workflow.node;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkflowNodes} 的单元测试：六类节点的工厂构造、类型与参数校验。
 */
public class WorkflowNodesTest {

    private static IWorkflowNode action(String id, String outputKey) {
        return WorkflowNodes.action(id, WorkflowNodeTypeEnum.TOOL, Collections.singleton(outputKey),
                "idem-" + id, scope -> Collections.emptyMap());
    }

    @Test
    public void buildsActionNode() {
        IWorkflowNode node = WorkflowNodes.action("a", WorkflowNodeTypeEnum.AGENT,
                Collections.singleton("out"), "idem", scope -> Collections.emptyMap());

        assertTrue(node instanceof ActionNode);
        assertEquals(WorkflowNodeTypeEnum.AGENT, node.getType());
        assertEquals(Collections.singleton("out"), node.getOutputKeys());
        assertEquals("idem", ((ActionNode) node).getIdempotencyKey());
    }

    @Test
    public void buildsSequenceNode() {
        IWorkflowNode node = WorkflowNodes.sequence("seq",
                Arrays.asList(action("a", "outA"), action("b", "outB")));

        assertTrue(node instanceof CompositeNode);
        assertEquals(WorkflowNodeTypeEnum.SEQUENCE, node.getType());
        assertEquals(2, node.children().size());
    }

    @Test
    public void buildsParallelNode() {
        IWorkflowNode node = WorkflowNodes.parallel("par",
                Arrays.asList(action("a", "outA"), action("b", "outB")));

        assertTrue(node instanceof CompositeNode);
        assertEquals(WorkflowNodeTypeEnum.PARALLEL, node.getType());
    }

    @Test
    public void buildsConditionNode() {
        IWorkflowNode node = WorkflowNodes.condition("c", scope -> true,
                action("t", "outT"), action("f", "outF"));

        assertTrue(node instanceof ConditionNode);
        assertEquals(WorkflowNodeTypeEnum.CONDITION, node.getType());
        assertEquals(2, node.children().size());
    }

    @Test
    public void buildsLoopNode() {
        IWorkflowNode node = WorkflowNodes.loop("l", action("body", "out"), 3, null);

        assertTrue(node instanceof LoopNode);
        assertEquals(WorkflowNodeTypeEnum.LOOP, node.getType());
        assertEquals(3, ((LoopNode) node).getMaxIterations());
    }

    @Test
    public void buildsHumanNode() {
        IWorkflowNode node = WorkflowNodes.human("h", "answer");

        assertTrue(node instanceof HumanNode);
        assertEquals(WorkflowNodeTypeEnum.HUMAN, node.getType());
        assertEquals(Collections.singleton("answer"), node.getOutputKeys());
    }

    @Test
    public void rejectsInvalidFactoryInputs() {
        Set<String> outputs = Collections.singleton("out");
        IWorkflowAction handler = scope -> Collections.emptyMap();
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.action("a", WorkflowNodeTypeEnum.SEQUENCE, outputs, "idem", handler));
        IWorkflowNode conditionChild = action("t", "out");
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.condition("c", null, conditionChild, null));
        IWorkflowNode loopBody = action("body", "out");
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.loop("l", loopBody, 0, null));
        List<IWorkflowNode> emptyChildren = Collections.emptyList();
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.parallel("par", emptyChildren));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.human("h", " "));
    }

    @Test
    public void rejectsDuplicateParallelOutputs() {
        IWorkflowNode first = action("a", "out");
        IWorkflowNode second = action("b", "out");
        List<IWorkflowNode> duplicates = Arrays.asList(first, second);
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.parallel("par", duplicates));
    }
}

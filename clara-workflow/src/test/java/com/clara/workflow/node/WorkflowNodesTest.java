package com.clara.workflow.node;

import com.clara.core.workflow.IWorkflowNode;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import org.junit.Test;

import java.util.Arrays;
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
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.action("a", WorkflowNodeTypeEnum.SEQUENCE,
                        Collections.singleton("out"), "idem", scope -> Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.condition("c", null, action("t", "out"), null));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.loop("l", action("body", "out"), 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.parallel("par", Collections.<IWorkflowNode>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.human("h", " "));
    }

    @Test
    public void rejectsDuplicateParallelOutputs() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowNodes.parallel("par",
                        Arrays.asList(action("a", "out"), action("b", "out"))));
    }
}

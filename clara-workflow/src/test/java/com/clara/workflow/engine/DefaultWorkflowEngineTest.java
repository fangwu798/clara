package com.clara.workflow.engine;

import com.clara.core.workflow.IScopeCondition;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.dto.workflow.WorkflowCheckpoint;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.workflow.checkpoint.InMemoryCheckpointStore;
import com.clara.workflow.node.WorkflowNodes;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultWorkflowEngine} 的单元测试：动作 / 顺序 / 并行 / 条件 / 循环 / 人工 六类节点执行，
 * 暂停、恢复、取消、检查点写入与资源释放。
 */
public class DefaultWorkflowEngineTest {

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    private final List<DefaultWorkflowEngine> engines = new ArrayList<DefaultWorkflowEngine>();

    @After
    public void tearDown() {
        for (DefaultWorkflowEngine engine : engines) {
            engine.close();
        }
    }

    private DefaultWorkflowEngine engine(int parallelism) {
        DefaultWorkflowEngine engine = new DefaultWorkflowEngine(store, parallelism);
        engines.add(engine);
        return engine;
    }

    private static WorkflowDefinition definition(IWorkflowNode root) {
        return new WorkflowDefinition("wf", "1", null, null, root);
    }

    private static IWorkflowNode action(final String id, final String outputKey, final List<String> ran) {
        return WorkflowNodes.action(id, WorkflowNodeTypeEnum.TOOL, Collections.singleton(outputKey),
                "idem-" + id, scope -> {
                    ran.add(id);
                    Map<String, Object> output = new LinkedHashMap<String, Object>();
                    output.put(outputKey, "value-" + id);
                    return output;
                });
    }

    private static IWorkflowNode countingAction(final String id, final String outputKey,
            final AtomicInteger counter) {
        return WorkflowNodes.action(id, WorkflowNodeTypeEnum.TOOL, Collections.singleton(outputKey),
                "idem-" + id, scope -> {
                    int value = counter.incrementAndGet();
                    Map<String, Object> output = new LinkedHashMap<String, Object>();
                    output.put(outputKey, value);
                    return output;
                });
    }

    @Test
    public void executesActionNodeAndWritesCheckpoint() {
        List<String> ran = new ArrayList<String>();
        WorkflowResult result = engine(2).start(definition(action("a1", "out", ran)), "inst-1",
                Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals("value-a1", result.getScope().get("out"));
        assertEquals(Collections.singletonList("a1"), ran);

        Optional<WorkflowCheckpoint> checkpoint = store.load("inst-1");
        assertTrue(checkpoint.isPresent());
        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, checkpoint.get().getStatus());
        assertEquals(2L, checkpoint.get().getVersion());
        Optional<WorkflowCheckpoint> running = store.load("inst-1", 1L);
        assertTrue(running.isPresent());
        assertEquals(WorkflowInstanceStatusEnum.RUNNING, running.get().getStatus());
    }

    @Test
    public void executesSequenceInOrder() {
        List<String> ran = new ArrayList<String>();
        IWorkflowNode root = WorkflowNodes.sequence("seq",
                Arrays.asList(action("a", "outA", ran), action("b", "outB", ran)));
        WorkflowResult result = engine(2).start(definition(root), "inst-seq", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(Arrays.asList("a", "b"), ran);
        assertEquals("value-a", result.getScope().get("outA"));
        assertEquals("value-b", result.getScope().get("outB"));
    }

    @Test
    public void executesParallelBranches() {
        List<String> ran = Collections.synchronizedList(new ArrayList<String>());
        IWorkflowNode root = WorkflowNodes.parallel("par",
                Arrays.asList(action("a", "outA", ran), action("b", "outB", ran)));
        WorkflowResult result = engine(2).start(definition(root), "inst-par", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals("value-a", result.getScope().get("outA"));
        assertEquals("value-b", result.getScope().get("outB"));
        assertEquals(2, ran.size());
        assertTrue(ran.containsAll(Arrays.asList("a", "b")));
    }

    @Test
    public void selectsTrueBranchWhenConditionHolds() {
        List<String> ran = new ArrayList<String>();
        IScopeCondition condition = scope -> Boolean.TRUE.equals(scope.get("flag"));
        IWorkflowNode root = WorkflowNodes.condition("cond", condition,
                action("t", "outT", ran), action("f", "outF", ran));
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("flag", Boolean.TRUE);

        WorkflowResult result = engine(2).start(definition(root), "inst-cond", inputs);

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(Collections.singletonList("t"), ran);
        assertEquals("value-t", result.getScope().get("outT"));
        assertFalse(result.getScope().contains("outF"));
    }

    @Test
    public void selectsFalseBranchWhenConditionFails() {
        List<String> ran = new ArrayList<String>();
        IScopeCondition condition = scope -> Boolean.TRUE.equals(scope.get("flag"));
        IWorkflowNode root = WorkflowNodes.condition("cond", condition,
                action("t", "outT", ran), action("f", "outF", ran));
        Map<String, Object> inputs = new LinkedHashMap<String, Object>();
        inputs.put("flag", Boolean.FALSE);

        WorkflowResult result = engine(2).start(definition(root), "inst-cond-false", inputs);

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(Collections.singletonList("f"), ran);
        assertEquals("value-f", result.getScope().get("outF"));
        assertFalse(result.getScope().contains("outT"));
    }

    @Test
    public void repeatsLoopBodyUpToMaxIterations() {
        AtomicInteger counter = new AtomicInteger();
        IWorkflowNode root = WorkflowNodes.loop("loop", countingAction("body", "out", counter), 3, null);

        WorkflowResult result = engine(2).start(definition(root), "inst-loop", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(3, counter.get());
    }

    @Test
    public void stopsLoopOnExitCondition() {
        AtomicInteger counter = new AtomicInteger();
        IScopeCondition exit = scope -> Integer.valueOf(2).equals(scope.get("out"));
        IWorkflowNode root = WorkflowNodes.loop("loop", countingAction("body", "out", counter), 5, exit);

        WorkflowResult result = engine(2).start(definition(root), "inst-loop-exit", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, result.getStatus());
        assertEquals(2, counter.get());
    }

    @Test
    public void pausesOnHumanNodeWhenInputMissing() {
        IWorkflowNode root = WorkflowNodes.human("h", "answer");

        WorkflowResult result = engine(2).start(definition(root), "inst-h", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.WAITING_INPUT, result.getStatus());
        assertEquals("h", result.getWaitingNodeId());
        assertEquals("answer", result.getWaitingInputKey());
        assertEquals(WorkflowInstanceStatusEnum.WAITING_INPUT, store.load("inst-h").get().getStatus());
    }

    @Test
    public void resumesHumanNodeAfterInputProvided() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("h", "answer"));
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition, "inst-h2", Collections.emptyMap());

        WorkflowResult resumed = engine.resume(definition, "inst-h2",
                Collections.<String, Object>singletonMap("answer", "yes"));

        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, resumed.getStatus());
        assertEquals("yes", resumed.getScope().get("answer"));
        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, store.load("inst-h2").get().getStatus());
    }

    @Test
    public void resumeChecksExpectedCheckpointVersion() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("h", "answer"));
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition, "inst-v", Collections.emptyMap());

        ClaraException conflict = assertThrows(ClaraException.class,
                () -> engine.resume(definition, "inst-v",
                        Collections.<String, Object>singletonMap("answer", "yes"), 99L));
        assertEquals(ErrorCodes.WORKFLOW_VERSION_CONFLICT, conflict.getError().getCode());

        WorkflowResult resumed = engine.resume(definition, "inst-v",
                Collections.<String, Object>singletonMap("answer", "yes"), 1L);
        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, resumed.getStatus());
    }

    @Test
    public void cancelsWaitingInstance() {
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition(WorkflowNodes.human("h", "answer")), "inst-c", Collections.emptyMap());

        WorkflowResult cancelled = engine.cancel("inst-c");

        assertEquals(WorkflowInstanceStatusEnum.CANCELLED, cancelled.getStatus());
        assertEquals(WorkflowInstanceStatusEnum.CANCELLED, store.load("inst-c").get().getStatus());
        assertEquals(WorkflowInstanceStatusEnum.CANCELLED, engine.cancel("inst-c").getStatus());
    }

    @Test
    public void suspendsAndResumesInstance() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("h", "answer"));
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition, "inst-s", Collections.emptyMap());

        assertEquals(WorkflowInstanceStatusEnum.SUSPENDED, engine.suspend("inst-s").getStatus());

        WorkflowResult resumed = engine.resume(definition, "inst-s",
                Collections.<String, Object>singletonMap("answer", "ok"));
        assertEquals(WorkflowInstanceStatusEnum.SUCCEEDED, resumed.getStatus());
    }

    @Test
    public void rejectsDuplicateStart() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("h", "answer"));
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition, "inst-dup", Collections.emptyMap());

        ClaraException conflict = assertThrows(ClaraException.class,
                () -> engine.start(definition, "inst-dup", Collections.emptyMap()));
        assertEquals(ErrorCodes.WORKFLOW_INVALID_STATE, conflict.getError().getCode());
    }

    @Test
    public void resumeUnknownInstanceFails() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("h", "answer"));

        ClaraException failure = assertThrows(ClaraException.class,
                () -> engine(2).resume(definition, "missing", Collections.emptyMap()));
        assertEquals(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, failure.getError().getCode());
    }

    @Test
    public void closeReleasesParallelExecutor() {
        IWorkflowNode root = WorkflowNodes.parallel("par",
                Arrays.asList(action("a", "outA", new ArrayList<String>()),
                        action("b", "outB", new ArrayList<String>())));
        WorkflowDefinition definition = definition(root);
        DefaultWorkflowEngine engine = engine(2);
        engine.start(definition, "inst-open", Collections.emptyMap());

        engine.close();

        assertThrows(RejectedExecutionException.class,
                () -> engine.start(definition, "inst-closed", Collections.emptyMap()));
    }
}

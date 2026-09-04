package com.clara.workflow;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WorkflowEngineTest {
    private WorkflowEngine engine;

    @After public void tearDown() { if (engine != null) engine.close(); }

    @Test
    public void executesSequenceAndConditionWithSharedScope() {
        WorkflowNode first = action("first", "value", new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) { return value("value", 2); }
        });
        WorkflowNode selected = action("selected", "result", new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) {
                return value("result", ((Integer) scope.get("value")) * 3);
            }
        });
        WorkflowNode skipped = action("skipped", "other", new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) { return value("other", true); }
        });
        WorkflowNode condition = WorkflowNodes.condition("choice", new ScopeCondition() {
            public boolean test(ExecutionScope scope) { return ((Integer) scope.get("value")) == 2; }
        }, selected, skipped);
        WorkflowDefinition definition = definition(WorkflowNodes.sequence("root",
                Arrays.asList(first, condition)));
        engine = new WorkflowEngine(new InMemoryCheckpointStore(), 2);

        WorkflowResult result = engine.start(definition, "instance-1", Collections.<String, Object>emptyMap());
        assertEquals(WorkflowInstanceStatus.SUCCEEDED, result.getStatus());
        assertEquals(6, result.getScope().get("result"));
        assertEquals(null, result.getScope().get("other"));
    }

    @Test
    public void runsParallelBranchesConcurrently() throws Exception {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        WorkflowNode left = action("left", "left", blocking(entered, release, "left"));
        WorkflowNode right = action("right", "right", blocking(entered, release, "right"));
        final WorkflowDefinition definition = definition(WorkflowNodes.parallel("root", Arrays.asList(left, right)));
        engine = new WorkflowEngine(new InMemoryCheckpointStore(), 2);
        final WorkflowResult[] result = new WorkflowResult[1];
        Thread runner = new Thread(new Runnable() {
            public void run() { result[0] = engine.start(definition, "parallel", Collections.<String, Object>emptyMap()); }
        });
        runner.start();
        assertTrue(entered.await(1L, TimeUnit.SECONDS)); release.countDown(); runner.join(2000L);
        assertEquals(WorkflowInstanceStatus.SUCCEEDED, result[0].getStatus());
        assertEquals(Boolean.TRUE, result[0].getScope().get("left"));
        assertEquals(Boolean.TRUE, result[0].getScope().get("right"));
    }

    @Test
    public void rejectsParallelOutputCollisions() {
        WorkflowNode one = action("one", "same", constant("same", 1));
        WorkflowNode two = action("two", "same", constant("same", 2));
        try {
            WorkflowNodes.parallel("parallel", Arrays.asList(one, two));
            fail("expected collision rejection");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("same")); }
    }

    @Test
    public void stopsLoopAtExitConditionAndMaximum() {
        WorkflowNode increment = action("increment", "count", new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) {
                Integer count = (Integer) scope.get("count"); return value("count", count == null ? 1 : count + 1);
            }
        });
        WorkflowNode loop = WorkflowNodes.loop("loop", increment, 10, new ScopeCondition() {
            public boolean test(ExecutionScope scope) {
                Integer count = (Integer) scope.get("count"); return count != null && count >= 3;
            }
        });
        engine = new WorkflowEngine(new InMemoryCheckpointStore(), 2);
        WorkflowResult result = engine.start(definition(loop), "loop-instance", Collections.<String, Object>emptyMap());
        assertEquals(3, result.getScope().get("count"));
    }

    @Test
    public void resumesHumanNodeWithoutRepeatingCompletedAction() {
        final AtomicInteger sideEffects = new AtomicInteger();
        WorkflowNode sideEffect = action("side-effect", "prepared", new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) {
                sideEffects.incrementAndGet(); return value("prepared", true);
            }
        });
        WorkflowDefinition definition = definition(WorkflowNodes.sequence("root",
                Arrays.asList(sideEffect, WorkflowNodes.human("approval", "approved"))));
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        engine = new WorkflowEngine(store, 2);

        WorkflowResult waiting = engine.start(definition, "human", Collections.<String, Object>emptyMap());
        assertEquals(WorkflowInstanceStatus.WAITING_INPUT, waiting.getStatus());
        assertEquals("approval", waiting.getWaitingNodeId());
        assertEquals("approved", waiting.getWaitingInputKey());
        assertEquals(1, sideEffects.get());
        WorkflowResult completed = engine.resume(definition, "human", value("approved", true));
        assertEquals(WorkflowInstanceStatus.SUCCEEDED, completed.getStatus());
        assertEquals(1, sideEffects.get());
        assertEquals(Boolean.TRUE, completed.getScope().get("approved"));
    }

    @Test
    public void checkpointStoreUsesExpectedVersion() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint("id", "flow", "1",
                WorkflowInstanceStatus.RUNNING, new ExecutionScope(null), 0L);
        store.save(checkpoint, -1L);
        assertEquals(WorkflowInstanceStatus.RUNNING, store.load("id", 0L).get().getStatus());
        try {
            store.save(new WorkflowCheckpoint("id", "flow", "1", WorkflowInstanceStatus.RUNNING,
                    new ExecutionScope(null), 1L), -1L);
            fail("expected version conflict");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.WORKFLOW_VERSION_CONFLICT, exception.getError().getCode());
        }
    }

    @Test
    public void suspendsAndResumesWaitingInstance() {
        WorkflowDefinition definition = definition(WorkflowNodes.human("human", "answer"));
        engine = new WorkflowEngine(new InMemoryCheckpointStore(), 1);
        assertEquals(WorkflowInstanceStatus.WAITING_INPUT,
                engine.start(definition, "suspend", null).getStatus());
        assertEquals(WorkflowInstanceStatus.SUSPENDED, engine.suspend("suspend").getStatus());
        assertEquals(WorkflowInstanceStatus.SUCCEEDED,
                engine.resume(definition, "suspend", value("answer", "ok")).getStatus());
    }

    private static WorkflowDefinition definition(WorkflowNode root) {
        return new WorkflowDefinition("flow", "1", "{}", "{}", root);
    }
    private static WorkflowNode action(String id, String output, WorkflowAction action) {
        return WorkflowNodes.action(id, WorkflowNodeType.TOOL, Collections.singleton(output), id + "-key", action);
    }
    private static WorkflowAction constant(final String key, final Object value) {
        return new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) { return value(key, value); }
        };
    }
    private static WorkflowAction blocking(final CountDownLatch entered,
            final CountDownLatch release, final String key) {
        return new WorkflowAction() {
            public Map<String, Object> execute(ExecutionScope scope) {
                entered.countDown();
                try { release.await(1L, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return value(key, true);
            }
        };
    }
    private static Map<String, Object> value(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result;
    }
}

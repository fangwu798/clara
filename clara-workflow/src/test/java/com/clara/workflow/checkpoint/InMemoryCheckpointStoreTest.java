package com.clara.workflow.checkpoint;

import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowCheckpoint;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryCheckpointStore} 的单元测试：保存 / 读取往返、缺失返回空、版本历史与覆盖冲突语义。
 */
public class InMemoryCheckpointStoreTest {

    private static WorkflowCheckpoint checkpoint(String instanceId, long version,
            WorkflowInstanceStatusEnum status) {
        return new WorkflowCheckpoint(instanceId, "wf", "1", status,
                new ExecutionScope(Collections.emptyMap()), version);
    }

    @Test
    public void savesAndLoadsCheckpoint() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.save(checkpoint("i", 0L, WorkflowInstanceStatusEnum.RUNNING), -1L);

        Optional<WorkflowCheckpoint> loaded = store.load("i");

        assertTrue(loaded.isPresent());
        assertEquals(0L, loaded.get().getVersion());
        assertEquals(WorkflowInstanceStatusEnum.RUNNING, loaded.get().getStatus());
        assertTrue(store.load("i", 0L).isPresent());
    }

    @Test
    public void returnsEmptyWhenAbsent() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        assertFalse(store.load("missing").isPresent());
        assertFalse(store.load("missing", 3L).isPresent());
    }

    @Test
    public void keepsHistoryAcrossVersions() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.save(checkpoint("i", 0L, WorkflowInstanceStatusEnum.RUNNING), -1L);
        store.save(checkpoint("i", 1L, WorkflowInstanceStatusEnum.WAITING_INPUT), 0L);

        assertEquals(1L, store.load("i").get().getVersion());
        assertEquals(WorkflowInstanceStatusEnum.RUNNING, store.load("i", 0L).get().getStatus());
        assertEquals(WorkflowInstanceStatusEnum.WAITING_INPUT, store.load("i", 1L).get().getStatus());
    }

    @Test
    public void rejectsStaleExpectedVersion() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.save(checkpoint("i", 0L, WorkflowInstanceStatusEnum.RUNNING), -1L);

        WorkflowCheckpoint next = checkpoint("i", 1L, WorkflowInstanceStatusEnum.RUNNING);
        ClaraException conflict = assertThrows(ClaraException.class, () -> store.save(next, -1L));
        assertEquals(ErrorCodes.WORKFLOW_VERSION_CONFLICT, conflict.getError().getCode());
    }

    @Test
    public void rejectsNonConsecutiveVersion() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        WorkflowCheckpoint skipped = checkpoint("i", 5L, WorkflowInstanceStatusEnum.RUNNING);
        ClaraException conflict = assertThrows(ClaraException.class, () -> store.save(skipped, -1L));
        assertEquals(ErrorCodes.WORKFLOW_VERSION_CONFLICT, conflict.getError().getCode());
    }

    @Test
    public void rejectsBlankInstanceId() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        assertThrows(IllegalArgumentException.class, () -> store.load("  "));
    }
}

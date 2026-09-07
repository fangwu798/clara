package com.clara.workflow;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 提供检查点存储器的内存实现。
 */
public final class InMemoryCheckpointStore implements CheckpointStore {
    private final ConcurrentMap<String, WorkflowCheckpoint> checkpoints =
            new ConcurrentHashMap<String, WorkflowCheckpoint>();
    private final ConcurrentMap<String, ConcurrentMap<Long, WorkflowCheckpoint>> history =
            new ConcurrentHashMap<String, ConcurrentMap<Long, WorkflowCheckpoint>>();

    public Optional<WorkflowCheckpoint> load(String instanceId) {
        if (blank(instanceId)) { throw new IllegalArgumentException("instanceId must not be blank"); }
        return Optional.ofNullable(checkpoints.get(instanceId));
    }

    public Optional<WorkflowCheckpoint> load(String instanceId, long version) {
        if (blank(instanceId) || version < 0L) { throw new IllegalArgumentException("instanceId and version are invalid"); }
        ConcurrentMap<Long, WorkflowCheckpoint> versions = history.get(instanceId);
        return versions == null ? Optional.<WorkflowCheckpoint>empty()
                : Optional.ofNullable(versions.get(Long.valueOf(version)));
    }

    public WorkflowCheckpoint save(WorkflowCheckpoint checkpoint, long expectedVersion) {
        if (checkpoint == null) { throw new IllegalArgumentException("checkpoint must not be null"); }
        synchronized (checkpoints) {
            WorkflowCheckpoint current = checkpoints.get(checkpoint.getInstanceId());
            long actual = current == null ? -1L : current.getVersion();
            if (actual != expectedVersion || checkpoint.getVersion() != expectedVersion + 1L) {
                throw conflict();
            }
            checkpoints.put(checkpoint.getInstanceId(), checkpoint);
            ConcurrentMap<Long, WorkflowCheckpoint> versions = history.get(checkpoint.getInstanceId());
            if (versions == null) {
                ConcurrentMap<Long, WorkflowCheckpoint> created =
                        new ConcurrentHashMap<Long, WorkflowCheckpoint>();
                ConcurrentMap<Long, WorkflowCheckpoint> raced = history.putIfAbsent(
                        checkpoint.getInstanceId(), created);
                versions = raced == null ? created : raced;
            }
            versions.put(Long.valueOf(checkpoint.getVersion()), checkpoint);
            return checkpoint;
        }
    }

    private static ClaraException conflict() {
        return new ClaraException(new ClaraError(ErrorCodes.WORKFLOW_VERSION_CONFLICT,
                ErrorCategory.CONFLICT, ErrorCodes.WORKFLOW_VERSION_CONFLICT, true,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}

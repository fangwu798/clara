package com.clara.workflow.checkpoint;

import com.clara.utils.StringUtils;
import com.clara.core.workflow.ICheckpointStore;
import com.clara.dto.workflow.WorkflowCheckpoint;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 检查点存储的内存实现。
 */
public final class InMemoryCheckpointStore implements ICheckpointStore {

    private final ConcurrentMap<String, WorkflowCheckpoint> checkpoints =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<Long, WorkflowCheckpoint>> history =
            new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowCheckpoint> load(String instanceId) {
        if (StringUtils.isBlank(instanceId)) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        return Optional.ofNullable(checkpoints.get(instanceId));
    }

    @Override
    public Optional<WorkflowCheckpoint> load(String instanceId, long version) {
        if (StringUtils.isBlank(instanceId) || version < 0L) {
            throw new IllegalArgumentException("instanceId and version are invalid");
        }
        ConcurrentMap<Long, WorkflowCheckpoint> versions = history.get(instanceId);
        return versions == null ? Optional.<WorkflowCheckpoint>empty()
                : Optional.ofNullable(versions.get(Long.valueOf(version)));
    }

    @Override
    public WorkflowCheckpoint save(WorkflowCheckpoint checkpoint, long expectedVersion) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
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
                        new ConcurrentHashMap<>();
                ConcurrentMap<Long, WorkflowCheckpoint> raced = history.putIfAbsent(
                        checkpoint.getInstanceId(), created);
                versions = raced == null ? created : raced;
            }
            versions.put(Long.valueOf(checkpoint.getVersion()), checkpoint);
            return checkpoint;
        }
    }

    private static ClaraException conflict() {
        return new ClaraException(ClaraError.builder(ErrorCodes.WORKFLOW_VERSION_CONFLICT, ErrorCategoryEnum.CONFLICT)
                .retryable(true)
                .build());
    }

}

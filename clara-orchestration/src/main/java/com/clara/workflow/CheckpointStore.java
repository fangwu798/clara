package com.clara.workflow;

import java.util.Optional;

public interface CheckpointStore {
    Optional<WorkflowCheckpoint> load(String instanceId);
    Optional<WorkflowCheckpoint> load(String instanceId, long version);
    WorkflowCheckpoint save(WorkflowCheckpoint checkpoint, long expectedVersion);
}

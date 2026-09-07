package com.clara.workflow;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行确定性工作流，并通过检查点支持暂停、人工输入、恢复和取消。
 *
 * <p>常规用法：为引擎配置检查点存储和最大并行度，首次执行调用 {@link #start}；当结果为
 * {@link WorkflowInstanceStatus#WAITING_INPUT} 或 {@link WorkflowInstanceStatus#SUSPENDED} 时，
 * 补充输入后调用 {@link #resume}。不再使用时应调用 {@link #close()} 释放并行执行线程。
 *
 * <p>注意：实例 ID 在检查点存储内必须唯一；恢复时工作流 ID 和版本必须与检查点一致。
 * 建议传入期望检查点版本以检测并发恢复。并行分支写入同一输出键会被视为冲突，已完成节点会根据检查点跳过。
 */
public final class WorkflowEngine implements Closeable {
    private final CheckpointStore checkpoints;
    private final ExecutorService parallelExecutor;

    public WorkflowEngine(CheckpointStore checkpoints, int maxParallelism) {
        if (checkpoints == null || maxParallelism <= 0) {
            throw new IllegalArgumentException("checkpoint store and positive maxParallelism are required");
        }
        this.checkpoints = checkpoints;
        this.parallelExecutor = Executors.newFixedThreadPool(maxParallelism, new WorkflowThreadFactory());
    }

    public WorkflowResult start(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs) {
        requireDefinition(definition); requireId(instanceId);
        if (checkpoints.load(instanceId).isPresent()) { throw error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategory.CONFLICT); }
        Run run = new Run(definition, instanceId, new ExecutionScope(inputs), -1L);
        run.checkpoint(WorkflowInstanceStatus.RUNNING);
        return execute(run);
    }

    public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs) {
        return resume(definition, instanceId, inputs, -1L);
    }

    public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs, long expectedCheckpointVersion) {
        requireDefinition(definition); requireId(instanceId);
        Optional<WorkflowCheckpoint> loaded = checkpoints.load(instanceId);
        if (!loaded.isPresent()) { throw error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategory.VALIDATION); }
        WorkflowCheckpoint checkpoint = loaded.get();
        if (expectedCheckpointVersion >= 0L
                && checkpoint.getVersion() != expectedCheckpointVersion) {
            throw error(ErrorCodes.WORKFLOW_VERSION_CONFLICT, ErrorCategory.CONFLICT);
        }
        if (!definition.getWorkflowId().equals(checkpoint.getWorkflowId())
                || !definition.getVersion().equals(checkpoint.getWorkflowVersion())) {
            throw error(ErrorCodes.WORKFLOW_DEFINITION_INVALID, ErrorCategory.VALIDATION);
        }
        if (checkpoint.getStatus() != WorkflowInstanceStatus.WAITING_INPUT
                && checkpoint.getStatus() != WorkflowInstanceStatus.SUSPENDED) {
            throw error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategory.CONFLICT);
        }
        ExecutionScope scope = checkpoint.getScope(); scope.addInputs(inputs);
        Run run = new Run(definition, instanceId, scope, checkpoint.getVersion());
        run.checkpoint(WorkflowInstanceStatus.RUNNING);
        return execute(run);
    }

    public WorkflowResult cancel(String instanceId) {
        requireId(instanceId);
        WorkflowCheckpoint checkpoint = checkpoints.load(instanceId).orElseThrow(
                () -> error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategory.VALIDATION));
        if (checkpoint.getStatus().isTerminal()) {
            return new WorkflowResult(instanceId, checkpoint.getStatus(),
                    checkpoint.getScope(), checkpoint.getVersion());
        }
        Run run = new Run(null, instanceId, checkpoint.getScope(), checkpoint.getVersion(),
                checkpoint.getWorkflowId(), checkpoint.getWorkflowVersion());
        run.checkpoint(WorkflowInstanceStatus.CANCELLED);
        return run.result(WorkflowInstanceStatus.CANCELLED);
    }

    public WorkflowResult suspend(String instanceId) {
        requireId(instanceId);
        WorkflowCheckpoint checkpoint = checkpoints.load(instanceId).orElseThrow(
                () -> error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategory.VALIDATION));
        if (checkpoint.getStatus().isTerminal()) {
            throw error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategory.CONFLICT);
        }
        if (checkpoint.getStatus() == WorkflowInstanceStatus.SUSPENDED) {
            return new WorkflowResult(instanceId, checkpoint.getStatus(),
                    checkpoint.getScope(), checkpoint.getVersion());
        }
        Run run = new Run(null, instanceId, checkpoint.getScope(), checkpoint.getVersion(),
                checkpoint.getWorkflowId(), checkpoint.getWorkflowVersion());
        run.checkpoint(WorkflowInstanceStatus.SUSPENDED);
        return run.result(WorkflowInstanceStatus.SUSPENDED);
    }

    public void close() { parallelExecutor.shutdownNow(); }

    private WorkflowResult execute(Run run) {
        try {
            executeNode(run.definition.getRoot(), run, "root/", true);
            run.checkpoint(WorkflowInstanceStatus.SUCCEEDED);
            return run.result(WorkflowInstanceStatus.SUCCEEDED);
        } catch (WaitingInput signal) {
            run.checkpoint(WorkflowInstanceStatus.WAITING_INPUT);
            return run.result(WorkflowInstanceStatus.WAITING_INPUT,
                    signal.nodeId, signal.inputKey);
        } catch (RuntimeException failure) {
            run.checkpoint(WorkflowInstanceStatus.FAILED);
            throw failure;
        }
    }

    private void executeNode(WorkflowNode node, Run run, String path, boolean persist) {
        String executionKey = path + node.getId();
        if (run.scope.isCompleted(executionKey)) return;
        if (node instanceof WorkflowNodes.ActionNode) executeAction((WorkflowNodes.ActionNode) node, run, executionKey, persist);
        else if (node instanceof WorkflowNodes.CompositeNode) executeComposite((WorkflowNodes.CompositeNode) node, run, executionKey, persist);
        else if (node instanceof WorkflowNodes.ConditionNode) executeCondition((WorkflowNodes.ConditionNode) node, run, executionKey, persist);
        else if (node instanceof WorkflowNodes.LoopNode) executeLoop((WorkflowNodes.LoopNode) node, run, executionKey, persist);
        else if (node instanceof WorkflowNodes.HumanNode) executeHuman((WorkflowNodes.HumanNode) node, run, executionKey, persist);
        else throw error(ErrorCodes.WORKFLOW_DEFINITION_INVALID, ErrorCategory.VALIDATION);
    }

    private void executeAction(WorkflowNodes.ActionNode node, Run run, String key, boolean persist) {
        Map<String, Object> outputs = node.action.execute(run.scope.copy());
        outputs = outputs == null ? Collections.<String, Object>emptyMap() : outputs;
        if (!node.getOutputKeys().containsAll(outputs.keySet())) {
            throw error(ErrorCodes.WORKFLOW_OUTPUT_CONFLICT, ErrorCategory.CONFLICT);
        }
        run.scope.complete(key, node.getId(), outputs); if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
    }

    private void executeComposite(WorkflowNodes.CompositeNode node, Run run, String key, boolean persist) {
        if (node.getType() == WorkflowNodeType.SEQUENCE) {
            for (WorkflowNode child : node.children) executeNode(child, run, key + "/", persist);
        } else {
            executeParallel(node, run, key);
        }
        run.scope.complete(key, node.getId(), Collections.<String, Object>emptyMap());
        if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
    }

    private void executeParallel(WorkflowNodes.CompositeNode node, final Run run, final String key) {
        final ExecutionScope base = run.scope.copy();
        List<Future<ExecutionScope>> futures = new ArrayList<Future<ExecutionScope>>();
        for (final WorkflowNode child : node.children) {
            futures.add(parallelExecutor.submit(new Callable<ExecutionScope>() {
                public ExecutionScope call() {
                    Run branch = run.branch(base.copy());
                    executeNode(child, branch, key + "/", false); return branch.scope;
                }
            }));
        }
        RuntimeException deferred = null;
        for (Future<ExecutionScope> future : futures) {
            try { run.scope.merge(base, future.get()); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt(); throw error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategory.CANCELLED);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (deferred == null) deferred = cause instanceof RuntimeException
                        ? (RuntimeException) cause
                        : error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategory.INTERNAL);
            }
        }
        run.checkpoint(WorkflowInstanceStatus.RUNNING);
        if (deferred != null) { throw deferred; }
    }

    private void executeCondition(WorkflowNodes.ConditionNode node, Run run, String key, boolean persist) {
        String selected = run.scope.control(key + ":branch");
        if (selected == null) {
            selected = node.condition.test(run.scope.copy()) ? "true" : "false";
            run.scope.control(key + ":branch", selected);
        }
        WorkflowNode branch = "true".equals(selected) ? node.whenTrue : node.whenFalse;
        if (branch != null) executeNode(branch, run, key + "/" + selected + "/", persist);
        run.scope.complete(key, node.getId(), Collections.<String, Object>emptyMap());
        if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
    }

    private void executeLoop(WorkflowNodes.LoopNode node, Run run, String key, boolean persist) {
        String stored = run.scope.control(key + ":iteration");
        int iteration = stored == null ? 0 : Integer.parseInt(stored);
        while (iteration < node.maxIterations) {
            if (node.exitCondition != null && node.exitCondition.test(run.scope.copy())) break;
            executeNode(node.body, run, key + "/" + iteration + "/", persist);
            iteration++; run.scope.control(key + ":iteration", String.valueOf(iteration));
            if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
        }
        run.scope.complete(key, node.getId(), Collections.<String, Object>emptyMap());
        if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
    }

    private void executeHuman(WorkflowNodes.HumanNode node, Run run, String key, boolean persist) {
        if (!run.scope.contains(node.inputKey)) { throw new WaitingInput(node.getId(), node.inputKey); }
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put(node.inputKey, run.scope.get(node.inputKey));
        run.scope.complete(key, node.getId(), output); if (persist) run.checkpoint(WorkflowInstanceStatus.RUNNING);
    }

    private static void requireDefinition(WorkflowDefinition value) {
        if (value == null) { throw new IllegalArgumentException("definition must not be null"); }
    }
    private static void requireId(String value) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException("instanceId must not be blank"); }
    }
    private static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }

    /**
     * 表示。
     */
    private final class Run {
        final WorkflowDefinition definition; final String instanceId; final ExecutionScope scope;
        final String workflowId; final String workflowVersion; long checkpointVersion;
        Run(WorkflowDefinition definition, String instanceId, ExecutionScope scope, long version) {
            this(definition, instanceId, scope, version, definition.getWorkflowId(), definition.getVersion());
        }
        Run(WorkflowDefinition definition, String instanceId, ExecutionScope scope, long version,
                String workflowId, String workflowVersion) {
            this.definition = definition; this.instanceId = instanceId; this.scope = scope;
            this.checkpointVersion = version; this.workflowId = workflowId; this.workflowVersion = workflowVersion;
        }
        void checkpoint(WorkflowInstanceStatus status) {
            WorkflowCheckpoint value = new WorkflowCheckpoint(instanceId, workflowId, workflowVersion,
                    status, scope, checkpointVersion + 1L);
            checkpoints.save(value, checkpointVersion); checkpointVersion++;
        }
        Run branch(ExecutionScope branchScope) {
            return new Run(definition, instanceId, branchScope, checkpointVersion, workflowId, workflowVersion);
        }
        WorkflowResult result(WorkflowInstanceStatus status) {
            return new WorkflowResult(instanceId, status, scope, checkpointVersion);
        }
        WorkflowResult result(WorkflowInstanceStatus status, String waitingNodeId,
                String waitingInputKey) {
            return new WorkflowResult(instanceId, status, scope, checkpointVersion,
                    waitingNodeId, waitingInputKey);
        }
    }
    /**
     * 表示。
     */
    private static final class WaitingInput extends RuntimeException {
        final String nodeId; final String inputKey;
        WaitingInput(String nodeId, String inputKey) {
            this.nodeId = nodeId; this.inputKey = inputKey;
        }
    }
    /**
     * 负责创建工作流线程实例。
     */
    private static final class WorkflowThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-workflow-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }
    }
}

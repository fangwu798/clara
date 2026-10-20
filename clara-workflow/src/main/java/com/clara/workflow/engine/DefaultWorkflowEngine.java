package com.clara.workflow.engine;

import com.clara.core.workflow.ICheckpointStore;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowCheckpoint;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.workflow.node.ActionNode;
import com.clara.workflow.node.CompositeNode;
import com.clara.workflow.node.ConditionNode;
import com.clara.workflow.node.HumanNode;
import com.clara.workflow.node.LoopNode;



import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行确定性工作流，支持暂停、人工输入、恢复与取消。
 */
public final class DefaultWorkflowEngine implements IWorkflowEngine {

    private final ICheckpointStore checkpoints;
    private final ExecutorService parallelExecutor;

    public DefaultWorkflowEngine(ICheckpointStore checkpoints, int maxParallelism) {
        if (checkpoints == null || maxParallelism <= 0) {
            throw new IllegalArgumentException("checkpoint store and positive maxParallelism are required");
        }
        this.checkpoints = checkpoints;
        this.parallelExecutor = Executors.newFixedThreadPool(maxParallelism, new WorkflowThreadFactory());
    }

    @Override
    public WorkflowResult start(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs) {
        requireDefinition(definition);
        requireId(instanceId);
        if (checkpoints.load(instanceId).isPresent()) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.CONFLICT);
        }
        Run run = new Run(definition, instanceId, new ExecutionScope(inputs), -1L);
        run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        return execute(run);
    }

    @Override
    public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs) {
        return resume(definition, instanceId, inputs, -1L);
    }

    @Override
    public WorkflowResult resume(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs, long expectedCheckpointVersion) {
        requireDefinition(definition);
        requireId(instanceId);
        Optional<WorkflowCheckpoint> loaded = checkpoints.load(instanceId);
        if (!loaded.isPresent()) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategoryEnum.VALIDATION);
        }
        WorkflowCheckpoint checkpoint = loaded.get();
        if (expectedCheckpointVersion >= 0L
                && checkpoint.getVersion() != expectedCheckpointVersion) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_VERSION_CONFLICT, ErrorCategoryEnum.CONFLICT);
        }
        if (!definition.getWorkflowId().equals(checkpoint.getWorkflowId())
                || !definition.getVersion().equals(checkpoint.getWorkflowVersion())) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_DEFINITION_INVALID, ErrorCategoryEnum.VALIDATION);
        }
        if (checkpoint.getStatus() != WorkflowInstanceStatusEnum.WAITING_INPUT
                && checkpoint.getStatus() != WorkflowInstanceStatusEnum.SUSPENDED) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.CONFLICT);
        }
        ExecutionScope scope = checkpoint.getScope();
        scope.addInputs(inputs);
        Run run = new Run(definition, instanceId, scope, checkpoint.getVersion());
        run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        return execute(run);
    }

    @Override
    public WorkflowResult cancel(String instanceId) {
        requireId(instanceId);
        WorkflowCheckpoint checkpoint = checkpoints.load(instanceId).orElseThrow(
                () -> WorkflowErrors.error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategoryEnum.VALIDATION));
        if (checkpoint.getStatus().isTerminal()) {
            return new WorkflowResult(instanceId, checkpoint.getStatus(),
                    checkpoint.getScope(), checkpoint.getVersion());
        }
        Run run = new Run(null, instanceId, checkpoint.getScope(), checkpoint.getVersion(),
                checkpoint.getWorkflowId(), checkpoint.getWorkflowVersion());
        run.checkpoint(WorkflowInstanceStatusEnum.CANCELLED);
        return run.result(WorkflowInstanceStatusEnum.CANCELLED);
    }

    @Override
    public WorkflowResult suspend(String instanceId) {
        requireId(instanceId);
        WorkflowCheckpoint checkpoint = checkpoints.load(instanceId).orElseThrow(
                () -> WorkflowErrors.error(ErrorCodes.WORKFLOW_INSTANCE_NOT_FOUND, ErrorCategoryEnum.VALIDATION));
        if (checkpoint.getStatus().isTerminal()) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.CONFLICT);
        }
        if (checkpoint.getStatus() == WorkflowInstanceStatusEnum.SUSPENDED) {
            return new WorkflowResult(instanceId, checkpoint.getStatus(),
                    checkpoint.getScope(), checkpoint.getVersion());
        }
        Run run = new Run(null, instanceId, checkpoint.getScope(), checkpoint.getVersion(),
                checkpoint.getWorkflowId(), checkpoint.getWorkflowVersion());
        run.checkpoint(WorkflowInstanceStatusEnum.SUSPENDED);
        return run.result(WorkflowInstanceStatusEnum.SUSPENDED);
    }

    @Override
    public void close() {
        parallelExecutor.shutdownNow();
    }

    private WorkflowResult execute(Run run) {
        try {
            executeNode(run.definition.getRoot(), run, "root/", true);
            run.checkpoint(WorkflowInstanceStatusEnum.SUCCEEDED);
            return run.result(WorkflowInstanceStatusEnum.SUCCEEDED);
        } catch (WaitingInput signal) {
            run.checkpoint(WorkflowInstanceStatusEnum.WAITING_INPUT);
            return run.result(WorkflowInstanceStatusEnum.WAITING_INPUT, signal.nodeId, signal.inputKey);
        } catch (RuntimeException failure) {
            run.checkpoint(WorkflowInstanceStatusEnum.FAILED);
            throw failure;
        }
    }

    private void executeNode(IWorkflowNode node, Run run, String path, boolean persist) {
        String executionKey = path + node.getId();
        if (run.scope.isCompleted(executionKey)) {
            return;
        }
        if (node instanceof ActionNode) {
            executeAction((ActionNode) node, run, executionKey, persist);
        } else if (node instanceof CompositeNode) {
            executeComposite((CompositeNode) node, run, executionKey, persist);
        } else if (node instanceof ConditionNode) {
            executeCondition((ConditionNode) node, run, executionKey, persist);
        } else if (node instanceof LoopNode) {
            executeLoop((LoopNode) node, run, executionKey, persist);
        } else if (node instanceof HumanNode) {
            executeHuman((HumanNode) node, run, executionKey, persist);
        } else {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_DEFINITION_INVALID, ErrorCategoryEnum.VALIDATION);
        }
    }

    private void executeAction(ActionNode node, Run run, String key, boolean persist) {
        Map<String, Object> outputs = node.getAction().execute(run.scope.copy());
        outputs = outputs == null ? Collections.emptyMap() : outputs;
        if (!node.getOutputKeys().containsAll(outputs.keySet())) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_OUTPUT_CONFLICT, ErrorCategoryEnum.CONFLICT);
        }
        run.scope.complete(key, node.getId(), outputs);
        if (persist) {
            run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        }
    }

    private void executeComposite(CompositeNode node, Run run, String key, boolean persist) {
        if (node.getType() == WorkflowNodeTypeEnum.SEQUENCE) {
            for (IWorkflowNode child : node.getChildren()) {
                executeNode(child, run, key + "/", persist);
            }
        } else {
            executeParallel(node, run, key);
        }
        run.scope.complete(key, node.getId(), Collections.emptyMap());
        if (persist) {
            run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        }
    }

    private void executeParallel(CompositeNode node, final Run run, final String key) {
        final ExecutionScope base = run.scope.copy();
        List<Future<ExecutionScope>> futures = new ArrayList<>();
        for (final IWorkflowNode child : node.getChildren()) {
            futures.add(parallelExecutor.submit(() -> {
                Run branch = run.branch(base.copy());
                executeNode(child, branch, key + "/", false);
                return branch.scope;
            }));
        }
        RuntimeException deferred = null;
        for (Future<ExecutionScope> future : futures) {
            try {
                run.scope.merge(base, future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.CANCELLED);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (deferred == null) {
                    deferred = cause instanceof RuntimeException
                            ? (RuntimeException) cause
                            : WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.INTERNAL);
                }
            }
        }
        run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        if (deferred != null) {
            throw deferred;
        }
    }

    private void executeCondition(ConditionNode node, Run run, String key, boolean persist) {
        String selected = run.scope.control(key + ":branch");
        if (selected == null) {
            selected = node.getCondition().test(run.scope.copy()) ? "true" : "false";
            run.scope.control(key + ":branch", selected);
        }
        IWorkflowNode branch = "true".equals(selected) ? node.getWhenTrue() : node.getWhenFalse();
        if (branch != null) {
            executeNode(branch, run, key + "/" + selected + "/", persist);
        }
        run.scope.complete(key, node.getId(), Collections.emptyMap());
        if (persist) {
            run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        }
    }

    private void executeLoop(LoopNode node, Run run, String key, boolean persist) {
        String stored = run.scope.control(key + ":iteration");
        int iteration = stored == null ? 0 : Integer.parseInt(stored);
        while (iteration < node.getMaxIterations()) {
            if (node.getExitCondition() != null && node.getExitCondition().test(run.scope.copy())) {
                break;
            }
            executeNode(node.getBody(), run, key + "/" + iteration + "/", persist);
            iteration++;
            run.scope.control(key + ":iteration", String.valueOf(iteration));
            if (persist) {
                run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
            }
        }
        run.scope.complete(key, node.getId(), Collections.emptyMap());
        if (persist) {
            run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        }
    }

    private void executeHuman(HumanNode node, Run run, String key, boolean persist) {
        if (!run.scope.contains(node.getInputKey())) {
            throw new WaitingInput(node.getId(), node.getInputKey());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(node.getInputKey(), run.scope.get(node.getInputKey()));
        run.scope.complete(key, node.getId(), output);
        // 记录「本实例已收到人工输入」：视频等高价能力在动作层据此拒绝未经确认的调用。
        run.scope.control(ExecutionScope.CONTROL_HUMAN_CONFIRMED, node.getId());
        if (isApproval(run.scope.get(node.getInputKey()))) {
            run.scope.control(ExecutionScope.CONTROL_HUMAN_APPROVED, node.getId());
        }
        if (persist) {
            run.checkpoint(WorkflowInstanceStatusEnum.RUNNING);
        }
    }

    /**
     * 判断人工输入是否为明确批准；批准值大小写不敏感，兼容中英文常见写法。
     *
     * @param value 人工输入值，可为 null
     * @return 明确批准时返回 true
     */
    private static boolean isApproval(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "approve".equalsIgnoreCase(text) || "approved".equalsIgnoreCase(text)
                || "yes".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)
                || "ok".equalsIgnoreCase(text) || "确认".equals(text) || "同意".equals(text);
    }

    private static void requireDefinition(WorkflowDefinition value) {
        if (value == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
    }

    private static void requireId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }

    /**
     * 表示单次执行过程的状态。
     */
    private final class Run {
        final WorkflowDefinition definition;
        final String instanceId;
        final ExecutionScope scope;
        final String workflowId;
        final String workflowVersion;
        long checkpointVersion;

        Run(WorkflowDefinition definition, String instanceId, ExecutionScope scope, long version) {
            this(definition, instanceId, scope, version, definition.getWorkflowId(), definition.getVersion());
        }

        Run(WorkflowDefinition definition, String instanceId, ExecutionScope scope, long version,
                String workflowId, String workflowVersion) {
            this.definition = definition;
            this.instanceId = instanceId;
            this.scope = scope;
            this.checkpointVersion = version;
            this.workflowId = workflowId;
            this.workflowVersion = workflowVersion;
        }

        void checkpoint(WorkflowInstanceStatusEnum status) {
            WorkflowCheckpoint value = new WorkflowCheckpoint(instanceId, workflowId, workflowVersion,
                    status, scope, checkpointVersion + 1L);
            checkpoints.save(value, checkpointVersion);
            checkpointVersion++;
        }

        Run branch(ExecutionScope branchScope) {
            return new Run(definition, instanceId, branchScope, checkpointVersion, workflowId, workflowVersion);
        }

        WorkflowResult result(WorkflowInstanceStatusEnum status) {
            return new WorkflowResult(instanceId, status, scope, checkpointVersion);
        }

        WorkflowResult result(WorkflowInstanceStatusEnum status, String waitingNodeId, String waitingInputKey) {
            return new WorkflowResult(instanceId, status, scope, checkpointVersion, waitingNodeId, waitingInputKey);
        }
    }

    /**
     * 表示人工输入等待信号。
     */
    private static final class WaitingInput extends RuntimeException {
        final String nodeId;
        final String inputKey;

        WaitingInput(String nodeId, String inputKey) {
            this.nodeId = nodeId;
            this.inputKey = inputKey;
        }
    }

    /**
     * 负责创建工作流线程实例。
     */
    private static final class WorkflowThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-workflow-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

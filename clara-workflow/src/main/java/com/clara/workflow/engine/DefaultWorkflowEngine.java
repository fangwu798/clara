package com.clara.workflow.engine;

import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetryContext;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
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

/**
 * 执行确定性工作流，支持暂停、人工输入、恢复与取消。
 */
public final class DefaultWorkflowEngine implements IWorkflowEngine {

    private final ICheckpointStore checkpoints;
    private final ExecutorService parallelExecutor;
    /** 链路追踪入口；未接入时为空实现。 */
    private final Telemetry telemetry;

    /**
     * 创建工作流引擎（不接入链路追踪）。
     *
     * @param checkpoints    检查点存储，不可为 null
     * @param maxParallelism 并行分支上限，必须为正
     */
    public DefaultWorkflowEngine(ICheckpointStore checkpoints, int maxParallelism) {
        this(checkpoints, maxParallelism, Telemetry.noop());
    }

    /**
     * 创建工作流引擎并接入链路追踪。
     *
     * @param checkpoints    检查点存储，不可为 null
     * @param maxParallelism 并行分支上限，必须为正
     * @param telemetry      链路追踪入口，可为 null（按空实现处理）
     * @throws IllegalArgumentException 检查点存储为 null 或并行上限非正时
     */
    public DefaultWorkflowEngine(ICheckpointStore checkpoints, int maxParallelism, Telemetry telemetry) {
        if (checkpoints == null || maxParallelism <= 0) {
            throw new IllegalArgumentException("checkpoint store and positive maxParallelism are required");
        }
        this.checkpoints = checkpoints;
        this.parallelExecutor = Executors.newFixedThreadPool(maxParallelism, new WorkflowThreadFactory());
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;
    }

    @Override
    public WorkflowResult start(WorkflowDefinition definition, String instanceId,
            Map<String, Object> inputs) {
        requireDefinition(definition);
        requireId(instanceId);
        if (checkpoints.load(instanceId).isPresent()) {
            throw WorkflowErrors.error(ErrorCodes.WORKFLOW_INVALID_STATE, ErrorCategoryEnum.CONFLICT);
        }
        WorkflowRun run = new WorkflowRun(checkpoints, definition, instanceId,
                new ExecutionScope(inputs), -1L);
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
        WorkflowRun run = new WorkflowRun(checkpoints, definition, instanceId, scope,
                checkpoint.getVersion());
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
        WorkflowRun run = new WorkflowRun(checkpoints, null, instanceId, checkpoint.getScope(),
                checkpoint.getVersion(),
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
        WorkflowRun run = new WorkflowRun(checkpoints, null, instanceId, checkpoint.getScope(),
                checkpoint.getVersion(),
                checkpoint.getWorkflowId(), checkpoint.getWorkflowVersion());
        run.checkpoint(WorkflowInstanceStatusEnum.SUSPENDED);
        return run.result(WorkflowInstanceStatusEnum.SUSPENDED);
    }

    @Override
    public void close() {
        parallelExecutor.shutdownNow();
    }

    private WorkflowResult execute(WorkflowRun run) {
        TelemetryContext parent = telemetry.currentContext();
        try (TelemetrySpan workflow = telemetry.startSpan("workflow.execute", TelemetrySpanKindEnum.WORKFLOW, parent)) {
            workflow.attribute("workflow", run.workflowId)
                    .attribute("instance", run.instanceId);
            // 节点统一挂在本 Span 下；并行分支用显式上下文跨线程延续同一条 trace。
            run.telemetryContext = telemetry.currentContext();
            try {
                executeNode(run.definition.getRoot(), run, "root/", true);
                run.checkpoint(WorkflowInstanceStatusEnum.SUCCEEDED);
                return run.result(WorkflowInstanceStatusEnum.SUCCEEDED);
            } catch (WaitingInputSignal signal) {
                workflow.attribute("paused", true)
                        .attribute("waiting_node", signal.nodeId);
                run.checkpoint(WorkflowInstanceStatusEnum.WAITING_INPUT);
                return run.result(WorkflowInstanceStatusEnum.WAITING_INPUT, signal.nodeId, signal.inputKey);
            } catch (RuntimeException failure) {
                workflow.error(failure);
                run.checkpoint(WorkflowInstanceStatusEnum.FAILED);
                throw failure;
            }
        }
    }

    private void executeNode(IWorkflowNode node, WorkflowRun run, String path, boolean persist) {
        String executionKey = path + node.getId();
        if (run.scope.isCompleted(executionKey)) {
            return;
        }
        try (TelemetrySpan span = telemetry.startSpan("workflow.node", TelemetrySpanKindEnum.WORKFLOW,
                run.telemetryContext)) {
            span.attribute("workflow", run.workflowId)
                    .attribute("node", node.getId())
                    .attribute("node_type", node.getType() == null ? null : node.getType().name());
            try {
                dispatchNode(node, run, executionKey, persist);
            } catch (WaitingInputSignal signal) {
                span.attribute("paused", true);
                throw signal;
            } catch (RuntimeException failure) {
                span.error(failure);
                throw failure;
            }
        }
    }

    private void dispatchNode(IWorkflowNode node, WorkflowRun run, String executionKey, boolean persist) {
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

    private void executeAction(ActionNode node, WorkflowRun run, String key, boolean persist) {
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

    private void executeComposite(CompositeNode node, WorkflowRun run, String key, boolean persist) {
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

    private void executeParallel(CompositeNode node, final WorkflowRun run, final String key) {
        final ExecutionScope base = run.scope.copy();
        List<Future<ExecutionScope>> futures = new ArrayList<>();
        for (final IWorkflowNode child : node.getChildren()) {
            futures.add(parallelExecutor.submit(() -> {
                WorkflowRun branch = run.branch(base.copy());
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

    private void executeCondition(ConditionNode node, WorkflowRun run, String key, boolean persist) {
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

    private void executeLoop(LoopNode node, WorkflowRun run, String key, boolean persist) {
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

    private void executeHuman(HumanNode node, WorkflowRun run, String key, boolean persist) {
        if (!run.scope.contains(node.getInputKey())) {
            throw new WaitingInputSignal(node.getId(), node.getInputKey());
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
            return (Boolean) value;
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

}
